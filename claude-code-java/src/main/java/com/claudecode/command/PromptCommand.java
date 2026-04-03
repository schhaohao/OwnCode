package com.claudecode.command;

import com.claudecode.command.loader.SkillFrontmatter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PromptCommand — Skill 的核心实现类（type='prompt' 的 Command）
 *
 * =============================================
 *  这是什么？
 * =============================================
 *
 * PromptCommand 是整个 Skill 系统的核心数据载体。
 * 它代表一个已经被加载并解析完毕的 Skill，包含了：
 * - 从 SKILL.md 的 YAML frontmatter 中解析出的元数据（名称、描述、控制参数等）
 * - 从 SKILL.md 的 Markdown 正文中读取的原始提示词内容
 * - Skill 文件在磁盘上的路径信息
 *
 * =============================================
 *  生命周期
 * =============================================
 *
 * 一个 PromptCommand 实例从创建到被使用，经历以下阶段：
 *
 * 1. 加载阶段（由 SkillLoader 完成）：
 *    - SkillLoader 扫描磁盘上的 SKILL.md 文件
 *    - 解析 YAML frontmatter → 得到 SkillFrontmatter 对象
 *    - 读取 Markdown 正文 → 得到 rawContent 字符串
 *    - 调用 PromptCommand.fromFrontmatter() 工厂方法创建实例
 *
 * 2. 注册阶段（由 CommandRegistry 完成）：
 *    - PromptCommand 实例被注册到 CommandRegistry 中
 *    - 同名的 Skill 根据 source 优先级决定是否覆盖
 *
 * 3. 发现阶段（在构建 API 请求时）：
 *    - AgentLoop 调用 CommandRegistry.buildSkillListing()
 *    - 生成所有可用 Skill 的 name + description 列表
 *    - 列表被注入到系统提示词中，LLM 通过阅读列表了解有哪些 Skill 可用
 *
 * 4. 执行阶段（当 Skill 被触发时）：
 *    - 用户输入 /name 或 LLM 调用 SkillTool
 *    - CommandRenderer 对 rawContent 进行渲染：
 *      a. 执行 !`command` shell 预处理
 *      b. 替换 $ARGUMENTS、${CLAUDE_SKILL_DIR} 等变量
 *    - 渲染后的内容被注入到对话上下文中（inline 模式）
 *      或发送到子代理执行（fork 模式）
 *
 * =============================================
 *  与 SkillFrontmatter 的关系
 * =============================================
 *
 * SkillFrontmatter 是「解析中间产物」，负责承接 YAML 解析的原始结果。
 * PromptCommand 是「最终使用对象」，在 SkillFrontmatter 的基础上增加了：
 * - rawContent（Markdown 正文）
 * - skillDir（文件路径）
 * - 各种便捷方法
 *
 * 为什么分成两个类？
 *   因为 SnakeYAML 的解析方式要求目标对象必须是简单的 JavaBean（public 无参构造 + setter）。
 *   而 PromptCommand 作为核心业务对象，可能需要更复杂的构造逻辑和不可变性保证。
 *   分离后各自职责更清晰。
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/types/command.ts 中的 PromptCommand 类型
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see Command
 * @see SkillFrontmatter
 * @see CommandRenderer
 * @see com.claudecode.command.loader.SkillLoader
 */
public class PromptCommand implements Command {

    // ==================== 基础标识字段 ====================

    /** Skill 名称，也是斜杠命令名。例如 "simplify" 对应 /simplify */
    private final String name;

    /** Skill 描述，注入 system-reminder 供 LLM 判断是否匹配（≤250 字符） */
    private final String description;

    /** Skill 的来源（BUNDLED / DISK / MCP / PLUGIN） */
    private final CommandSource source;

    /** SKILL.md 文件所在目录的路径，用于 ${CLAUDE_SKILL_DIR} 变量替换 */
    private final Path skillDir;

    // ==================== 执行控制字段 ====================

    /**
     * 执行模式："inline"（默认）或 "fork"
     *
     * inline：Skill 内容注入当前对话上下文，LLM 在当前对话中执行
     * fork：Skill 在独立子代理中执行，结果返回主对话
     */
    private final String context;

    /**
     * Fork 模式下的子代理类型
     * 仅在 context="fork" 时有意义
     */
    private final String agent;

    /**
     * Skill 激活时免权限审批的工具列表
     * 例如 ["Read", "Bash(npm run *)"]
     */
    private final List<String> allowedTools;

    // ==================== 触发控制字段 ====================

    /**
     * 是否禁止 LLM 自动调用
     * true = 仅用户可通过 /name 调用
     */
    private final boolean disableModelInvocation;

    /**
     * 是否允许用户通过 / 菜单调用
     * false = 从 / 菜单隐藏，仅 LLM 可调用
     */
    private final boolean userInvocable;

    /** 参数提示，自动补全时展示，如 "[issue-number]" */
    private final String argumentHint;

    // ==================== 内容字段 ====================

    /**
     * SKILL.md 的 Markdown 正文内容（未渲染的原始内容）
     *
     * 这段内容中可能包含：
     * - $ARGUMENTS：运行时会被替换为用户传入的参数
     * - $0, $1, $2...：按位置引用参数
     * - ${CLAUDE_SKILL_DIR}：替换为 SKILL.md 所在目录的绝对路径
     * - !`command`：shell 预处理，执行命令并用输出替换
     *
     * 渲染由 CommandRenderer 负责，本类只存储原始内容。
     */
    private final String rawContent;

    // ==================== 构造函数 ====================

    /**
     * 全参数构造函数（私有，通过 Builder 或工厂方法创建）
     */
    private PromptCommand(String name, String description, CommandSource source,
                          Path skillDir, String context, String agent,
                          List<String> allowedTools, boolean disableModelInvocation,
                          boolean userInvocable, String argumentHint, String rawContent) {
        this.name = name;
        this.description = description;
        this.source = source;
        this.skillDir = skillDir;
        this.context = context != null ? context : "inline";
        this.agent = agent;
        this.allowedTools = allowedTools != null ? new ArrayList<>(allowedTools) : new ArrayList<>();
        this.disableModelInvocation = disableModelInvocation;
        this.userInvocable = userInvocable;
        this.argumentHint = argumentHint;
        this.rawContent = rawContent != null ? rawContent : "";
    }

    // ==================== 工厂方法 ====================

    /**
     * 从 SkillFrontmatter + 正文内容 + 路径信息 构建 PromptCommand
     *
     * 这是创建 PromptCommand 的标准方式。SkillLoader 解析完 SKILL.md 后，
     * 调用此方法将解析结果组装为最终可用的 PromptCommand 对象。
     *
     * @param frontmatter YAML frontmatter 解析结果
     * @param rawContent  Markdown 正文内容（未渲染）
     * @param skillDir    SKILL.md 所在目录的路径
     * @param source      来源（BUNDLED / DISK 等）
     * @param defaultName 默认名称（通常是目录名，当 frontmatter 中未指定 name 时使用）
     * @return 构建好的 PromptCommand 实例
     */
    public static PromptCommand fromFrontmatter(SkillFrontmatter frontmatter,
                                                 String rawContent,
                                                 Path skillDir,
                                                 CommandSource source,
                                                 String defaultName) {
        // 名称：优先使用 frontmatter 中声明的，否则使用目录名
        String name = frontmatter.getName() != null && !frontmatter.getName().isEmpty()
                ? frontmatter.getName()
                : defaultName;

        // 描述：截断到 250 字符（官方限制）
        String description = frontmatter.getDescription();
        if (description != null && description.length() > 250) {
            description = description.substring(0, 247) + "...";
        }

        return new PromptCommand(
                name,
                description != null ? description : "",
                source,
                skillDir,
                frontmatter.getContext(),
                frontmatter.getAgent(),
                frontmatter.getAllowedTools(),
                frontmatter.isDisableModelInvocation(),
                frontmatter.isUserInvocable(),
                frontmatter.getArgumentHint(),
                rawContent
        );
    }

    /**
     * 快捷工厂方法：创建一个简单的 Bundled Skill
     *
     * 用于在代码中直接创建内置 Skill（不需要 SKILL.md 文件）。
     * 主要在 BundledSkillLoader 中使用。
     *
     * @param name        Skill 名称
     * @param description 描述
     * @param rawContent  提示词正文
     * @return 构建好的 PromptCommand 实例
     */
    public static PromptCommand bundled(String name, String description, String rawContent) {
        return new PromptCommand(
                name, description, CommandSource.BUNDLED,
                null,       // bundled skill 没有磁盘路径
                "inline",   // 默认 inline 模式
                null,       // 无子代理
                new ArrayList<>(),  // 无特殊权限
                false,      // 允许 LLM 自动调用
                true,       // 允许用户调用
                null,       // 无参数提示
                rawContent
        );
    }

    // ==================== Command 接口实现 ====================

    /**
     * {@inheritDoc}
     *
     * 返回 Skill 的名称，也是 / 命令的触发词。
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * {@inheritDoc}
     *
     * 返回 Skill 的描述，用于注入 system-reminder。
     */
    @Override
    public String description() {
        return description;
    }

    /**
     * {@inheritDoc}
     *
     * Skill 的类型始终是 PROMPT。
     */
    @Override
    public CommandType type() {
        return CommandType.PROMPT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CommandSource source() {
        return source;
    }

    // ==================== 业务方法 ====================

    /**
     * 判断这个 Skill 是否应该出现在 system-reminder 的可用 Skill 列表中
     *
     * 条件：
     * 1. 必须有描述（否则 LLM 无法判断是否匹配）
     * 2. 不能设置了 disableModelInvocation（否则 LLM 不应该看到它）
     *
     * 注意：即使 userInvocable=false 的 Skill 也应该出现在列表中，
     * 因为它们是「仅 LLM 可调用」的，LLM 需要知道它们的存在。
     *
     * @return true 如果应该出现在 Skill 列表中
     */
    public boolean shouldListForModel() {
        return description != null && !description.isEmpty() && !disableModelInvocation;
    }

    /**
     * 判断这个 Skill 是否允许被 LLM 通过 SkillTool 调用
     *
     * @return true 如果 LLM 可以调用
     */
    public boolean isModelInvocable() {
        return !disableModelInvocation;
    }

    /**
     * 判断执行模式是否为 fork
     *
     * @return true 如果是 fork 模式
     */
    public boolean isFork() {
        return "fork".equalsIgnoreCase(context);
    }

    // ==================== Getter ====================

    public Path getSkillDir() {
        return skillDir;
    }

    public String getContext() {
        return context;
    }

    public String getAgent() {
        return agent;
    }

    public List<String> getAllowedTools() {
        return new ArrayList<>(allowedTools);
    }

    public boolean isDisableModelInvocation() {
        return disableModelInvocation;
    }

    public boolean isUserInvocable() {
        return userInvocable;
    }

    public String getArgumentHint() {
        return argumentHint;
    }

    public String getRawContent() {
        return rawContent;
    }

    @Override
    public String toString() {
        return "PromptCommand{" +
                "name='" + name + '\'' +
                ", source=" + source +
                ", context='" + context + '\'' +
                ", description='" + (description != null && description.length() > 50
                    ? description.substring(0, 50) + "..." : description) + '\'' +
                '}';
    }
}

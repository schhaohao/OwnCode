package com.claudecode.command;

import com.claudecode.command.loader.SkillLoader;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CommandRegistry — 命令/技能注册中心
 *
 * =============================================
 *  核心职责
 * =============================================
 *
 * CommandRegistry 是整个 Skill 系统的「大脑」，负责：
 *
 * 1. 汇聚所有来源的 Skill：
 *    调用 SkillLoader 从磁盘加载（~/.claude-code-java/skills/ 和 .claude-code-java/skills/），
 *    后续还可以从 Bundled、MCP、Plugin 等来源加载。
 *    所有来源最终汇聚到这一个注册中心。
 *    （对应 Claude Code 源码中 src/commands.ts 的 getCommands() 函数）
 *
 * 2. 提供 Skill 查找：
 *    SkillTool 和 Repl 通过 getCommand(name) 查找特定 Skill。
 *
 * 3. 生成 Skill 列表摘要：
 *    调用 buildSkillListing() 生成所有可用 Skill 的 name + description 列表，
 *    注入到系统提示词中，让 LLM 知道有哪些 Skill 可用。
 *
 * =============================================
 *  与 ToolRegistry 的关系
 * =============================================
 *
 * 项目中有两个 Registry：
 *
 * | 维度         | ToolRegistry             | CommandRegistry          |
 * |-------------|--------------------------|--------------------------|
 * | 管理对象     | Tool（工具）              | Command（命令/Skill）     |
 * | 被谁调用     | AgentLoop（执行工具）     | SkillTool + Repl          |
 * | 注册内容     | Read, Bash, Edit 等工具   | simplify, commit 等 Skill |
 * | 注入方式     | API 请求的 tools 字段     | 系统提示词的 system-reminder |
 *
 * 它们通过 SkillTool 桥接：
 *   SkillTool 注册在 ToolRegistry 中（是一个 Tool），
 *   但它的 execute() 方法内部调用 CommandRegistry 来查找和执行 Skill。
 *
 * =============================================
 *  初始化流程
 * =============================================
 *
 * 在 ClaudeCode.main() 中：
 *   CommandRegistry registry = new CommandRegistry(workingDirectory);
 *   registry.initialize();  // 扫描所有来源，加载 Skill
 *
 * initialize() 内部做了什么：
 *   1. 创建 SkillLoader，扫描 ~/.claude/skills/ 和 .claude/skills/
 *   2. 加载所有 SKILL.md 文件，解析为 PromptCommand 列表
 *   3. 按 name 注册到内部 Map 中
 *   4. （后续扩展）加载 Bundled Skill、MCP Skill 等
 *
 * =============================================
 *  预算控制（Skill 列表大小限制）
 * =============================================
 *
 * Skill 的 name + description 列表会注入到系统提示词中，占用上下文窗口。
 * 官方的预算控制策略：
 * - 列表总字符数不超过上下文窗口的 1%（或 8000 字符）
 * - 单个 Skill 描述最多 250 字符
 * - 超预算时优先保留 Bundled Skill，截断自定义 Skill 的描述
 *
 * 当前版本暂未实现预算控制（P3 阶段），但已在 PromptCommand 构造时截断描述到 250 字符。
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/commands.ts 中的 getCommands() 和
 *   formatCommandsWithinBudget() 函数
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see PromptCommand
 * @see SkillLoader
 * @see com.claudecode.tool.impl.SkillTool
 */
public class CommandRegistry {

    /**
     * 命令存储：name → PromptCommand
     *
     * 使用 LinkedHashMap 保持注册顺序（先 Bundled，再 Disk），
     * 方便生成稳定的 Skill 列表。
     * 同名 Skill 后注册的会覆盖先注册的（实现优先级覆盖）。
     */
    private final Map<String, PromptCommand> commands = new LinkedHashMap<>();

    /** 工作目录（项目根目录），传递给 SkillLoader 用于定位项目级 .claude/skills/ */
    private final String workingDirectory;

    /**
     * 构造 CommandRegistry
     *
     * 注意：构造后需要调用 {@link #initialize()} 才能加载 Skill。
     * 构造函数本身不做任何 I/O 操作。
     *
     * @param workingDirectory 当前工作目录（项目根目录）
     */
    public CommandRegistry(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * 初始化：扫描并加载所有来源的 Skill
     *
     * 这是启动时的核心方法。调用链路：
     *   ClaudeCode.main()
     *     → new CommandRegistry(workDir)
     *     → registry.initialize()
     *       → 加载 Bundled Skill（TODO: P1 阶段实现）
     *       → 加载 Disk Skill（SkillLoader）
     *
     * 加载顺序决定了覆盖优先级：
     *   先加载的会被后加载的同名 Skill 覆盖。
     *   所以：Bundled（先）→ Disk 用户级（中）→ Disk 项目级（后）
     *
     * 这个方法是幂等的：多次调用会清空并重新加载。
     */
    public void initialize() {
        commands.clear();

        // ---- 阶段 1：加载 Bundled Skill（优先级最低）----
        // TODO: P1 阶段实现 BundledSkillLoader
        // loadBundledSkills();

        // ---- 阶段 2：加载磁盘 Skill（优先级较高，会覆盖 Bundled）----
        loadDiskSkills();

        // 打印加载结果
        if (!commands.isEmpty()) {
            System.err.println("[Skill] Loaded " + commands.size() + " skill(s): "
                    + commands.keySet().stream().collect(Collectors.joining(", ")));
        }
    }

    /**
     * 从磁盘加载 Skill（用户级 + 项目级）
     *
     * 通过 SkillLoader 扫描 ~/.claude-code-java/skills/ 和 .claude-code-java/skills/ 目录。
     * SkillLoader 内部已经处理了两级目录的优先级（项目级覆盖用户级）。
     */
    private void loadDiskSkills() {
        SkillLoader loader = new SkillLoader(workingDirectory);
        List<PromptCommand> diskSkills = loader.loadAll();

        for (PromptCommand skill : diskSkills) {
            // 直接 put，同名的 Bundled Skill 会被覆盖
            commands.put(skill.name(), skill);
        }
    }

    // ==================== 查找方法 ====================

    /**
     * 根据名称查找命令
     *
     * @param name 命令名称（如 "simplify"）
     * @return 找到的 PromptCommand，不存在时返回 null
     */
    public PromptCommand getCommand(String name) {
        return commands.get(name);
    }

    /**
     * 判断是否存在指定名称的命令
     *
     * @param name 命令名称
     * @return true 如果命令存在
     */
    public boolean hasCommand(String name) {
        return commands.containsKey(name);
    }

    /**
     * 获取所有已注册的命令
     *
     * @return 不可修改的命令列表（保持注册顺序）
     */
    public List<PromptCommand> getAllCommands() {
        return Collections.unmodifiableList(new ArrayList<>(commands.values()));
    }

    /**
     * 获取所有允许用户通过 / 调用的命令
     *
     * 过滤掉 userInvocable=false 的命令（这些命令只允许 LLM 调用）。
     *
     * @return 用户可调用的命令列表
     */
    public List<PromptCommand> getUserInvocableCommands() {
        return commands.values().stream()
                .filter(PromptCommand::isUserInvocable)
                .collect(Collectors.toList());
    }

    // ==================== System Prompt 注入 ====================

    /**
     * 生成 Skill 列表摘要，用于注入系统提示词
     *
     * 这是 Skill 系统最关键的方法之一。它生成的文本会被追加到系统提示词末尾，
     * 让 LLM 知道有哪些 Skill 可用、每个 Skill 做什么。
     *
     * LLM 根据这个列表来决定：
     * - 是否需要调用某个 Skill（自动触发）
     * - 用户输入 /name 时是否合法
     *
     * 生成格式（与官方一致）：
     * <pre>
     * &lt;system-reminder&gt;
     * The following skills are available for use with the Skill tool:
     *
     * - simplify: Review changed code for reuse, quality, and efficiency
     * - commit: Create a git commit with a good message
     * - xhs-note-creator: 小红书笔记素材创作技能
     * &lt;/system-reminder&gt;
     * </pre>
     *
     * 如果没有任何可用的 Skill，返回空字符串（不注入任何内容）。
     *
     * @return 格式化的 Skill 列表文本，或空字符串
     */
    public String buildSkillListing() {
        // 过滤出应该展示给 LLM 的 Skill
        // 条件：有描述 && 没有禁止 LLM 调用
        List<PromptCommand> listable = commands.values().stream()
                .filter(PromptCommand::shouldListForModel)
                .collect(Collectors.toList());

        if (listable.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n<system-reminder>\n");
        sb.append("The following skills are available for use with the Skill tool:\n\n");

        for (PromptCommand cmd : listable) {
            sb.append("- ").append(cmd.name());
            if (cmd.description() != null && !cmd.description().isEmpty()) {
                sb.append(": ").append(cmd.description());
            }
            sb.append("\n");
        }

        sb.append("</system-reminder>");
        return sb.toString();
    }

    /**
     * 已注册命令的数量
     *
     * @return 命令数量
     */
    public int size() {
        return commands.size();
    }
}

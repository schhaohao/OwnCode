package com.claudecode.command.loader;

import java.util.ArrayList;
import java.util.List;

/**
 * SkillFrontmatter — SKILL.md 文件中 YAML 前置元数据的解析目标对象
 *
 * =============================================
 *  什么是 Frontmatter？
 * =============================================
 *
 * Frontmatter（前置元数据）是 Markdown 文件开头由 --- 包裹的 YAML 格式配置段。
 * 它用来声明这个 Skill 的元数据和行为控制参数，而不是 Skill 的实际内容。
 *
 * 一个完整的 SKILL.md 文件结构如下：
 *
 * <pre>
 * ---                                    ← Frontmatter 开始标记
 * name: my-skill                         ← Skill 名称（可选，默认用目录名）
 * description: 这个 Skill 做什么          ← 描述（非常重要，LLM 据此判断是否匹配）
 * allowed-tools:                         ← 激活时免审批的工具列表
 *   - Read
 *   - Bash
 * context: inline                        ← 执行模式：inline 或 fork
 * user-invocable: true                   ← 是否允许用户通过 / 调用
 * disable-model-invocation: false        ← 是否禁止 LLM 自动调用
 * argument-hint: "[file-path]"           ← 参数提示
 * ---                                    ← Frontmatter 结束标记
 * 这里是 Skill 的 Markdown 正文内容...     ← 这部分不在 Frontmatter 中
 * 支持 $ARGUMENTS 变量替换...
 * </pre>
 *
 * =============================================
 *  这个类的作用
 * =============================================
 *
 * 这个类是 YAML 解析的「目标容器」。SkillLoader 读取 SKILL.md 文件时，
 * 会先提取 --- 之间的 YAML 文本，然后使用 SnakeYAML 将其解析为这个类的实例。
 *
 * 因为 YAML 字段名使用连字符（如 allowed-tools），而 Java 字段名使用驼峰，
 * 所以需要在 SkillLoader 中做字段名映射，或者使用 SnakeYAML 的自定义配置。
 *
 * =============================================
 *  字段说明
 * =============================================
 *
 * 每个字段都对应 SKILL.md frontmatter 中的一个配置项。
 * 所有字段都有合理的默认值，最小化的 SKILL.md 只需要一个 description 即可。
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/types/command.ts 中 PromptCommand 的 frontmatter 字段
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see com.claudecode.command.PromptCommand
 */
public class SkillFrontmatter {

    /**
     * Skill 名称
     *
     * 也是斜杠命令的名字。例如 name="simplify" 对应 /simplify 命令。
     * 如果不指定，SkillLoader 会使用 SKILL.md 所在目录的目录名作为默认值。
     *
     * 命名规范：只允许小写字母、数字和连字符（a-z, 0-9, -）
     */
    private String name;

    /**
     * Skill 描述（非常重要！）
     *
     * 这个描述有两个关键作用：
     * 1. 注入到 system-reminder 中，LLM 通过阅读这个描述来判断是否要自动调用
     * 2. 在 /help 或补全菜单中展示给用户看
     *
     * 官方限制最多 250 个字符，超出会被截断。
     * 如果描述为空或太模糊，LLM 可能永远不会自动调用这个 Skill。
     *
     * 好的描述示例：
     *   "Review changed code for reuse, quality, and efficiency, then fix any issues found"
     * 不好的描述示例：
     *   "A useful skill"（太模糊，LLM 不知道什么时候该用它）
     */
    private String description;

    /**
     * 是否禁止 LLM 自动调用此 Skill
     *
     * - false（默认）：LLM 可以根据用户请求自动判断并调用
     * - true：只有用户通过 /name 显式输入才能触发，LLM 不会主动调用
     *
     * 使用场景：
     *   当一个 Skill 的触发条件比较敏感（比如发布到生产环境），
     *   你不希望 LLM 在不合适的时候自动触发它。
     *
     * 对应 YAML 字段名：disable-model-invocation
     */
    private boolean disableModelInvocation = false;

    /**
     * 是否允许用户通过 / 菜单调用
     *
     * - true（默认）：出现在 / 命令补全列表中，用户可以输入 /name 触发
     * - false：从 / 菜单中隐藏，只有 LLM 可以通过 SkillTool 调用
     *
     * 使用场景：
     *   有些 Skill 是「辅助性」的，只在特定条件下由 LLM 自动触发，
     *   不需要暴露给用户直接使用。
     *
     * 对应 YAML 字段名：user-invocable
     */
    private boolean userInvocable = true;

    /**
     * Skill 激活时免权限审批的工具列表
     *
     * 正常情况下，BashTool、EditFileTool 等写入类工具需要用户确认才能执行。
     * 通过在这里列出工具名，可以让 Skill 执行期间自动跳过这些工具的权限审批。
     *
     * 支持的格式：
     * - 精确匹配："Bash"（免审批所有 Bash 命令）
     * - 带参数过滤："Bash(npm:*)"（只免审批 npm 开头的 Bash 命令）
     *
     * 示例：
     *   allowed-tools:
     *     - Read
     *     - Bash(npm run *)
     *     - Edit
     *
     * 安全说明：
     *   这个功能要谨慎使用。授权范围越大，Skill 的自动化程度越高，
     *   但同时用户失去的控制也越多。
     *
     * 对应 YAML 字段名：allowed-tools
     */
    private List<String> allowedTools = new ArrayList<>();

    /**
     * 执行模式：inline 或 fork
     *
     * - "inline"（默认）：
     *   Skill 的 prompt 内容被展开为消息注入当前对话上下文。
     *   LLM 在当前对话中继续执行 Skill 的指令。
     *   适合大多数 Skill。
     *
     * - "fork"：
     *   Skill 在独立的子 Agent 中执行，有自己的 token 预算和上下文。
     *   执行完毕后，结果文本返回主对话。
     *   适合需要大量上下文的复杂 Skill，避免污染主对话。
     *
     * 类比：
     *   inline 就像在当前函数中直接写代码，
     *   fork 就像调用一个新的子函数——有自己的局部变量和作用域。
     */
    private String context = "inline";

    /**
     * Fork 模式下使用的子代理类型
     *
     * 仅在 context="fork" 时生效。
     * 可选值：
     * - "general-purpose"（默认）：通用代理，可以使用所有工具
     * - "Explore"：专门用于代码探索和搜索的快速代理
     * - "Plan"：专门用于设计实现方案的架构师代理
     *
     * 如果 context="inline"，此字段被忽略。
     */
    private String agent;

    /**
     * 参数提示，用于自动补全时展示
     *
     * 告诉用户这个 Skill 期望什么参数。
     * 例如：
     *   argument-hint: "[issue-number]"  → 用户看到 /review-pr [issue-number]
     *   argument-hint: "[file-path]"     → 用户看到 /lint [file-path]
     *
     * 纯展示用途，不影响实际执行。
     *
     * 对应 YAML 字段名：argument-hint
     */
    private String argumentHint;

    /**
     * 条件激活的文件 glob 模式列表
     *
     * 当设置了 paths 字段后，这个 Skill 只在 LLM 访问匹配 paths 模式的文件时
     * 才会被激活（出现在可用 Skill 列表中）。
     *
     * 示例：
     *   paths:
     *     - "*.py"          → 只在操作 Python 文件时激活
     *     - "src/api/**"    → 只在操作 src/api 目录下文件时激活
     *
     * 如果不设置 paths，Skill 始终可用。
     *
     * 当前版本暂未实现条件激活逻辑，但预留此字段。
     */
    private List<String> paths;

    // ==================== Getter & Setter ====================
    // SnakeYAML 需要 public 的 setter 方法来注入解析后的值

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isDisableModelInvocation() {
        return disableModelInvocation;
    }

    public void setDisableModelInvocation(boolean disableModelInvocation) {
        this.disableModelInvocation = disableModelInvocation;
    }

    public boolean isUserInvocable() {
        return userInvocable;
    }

    public void setUserInvocable(boolean userInvocable) {
        this.userInvocable = userInvocable;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools != null ? allowedTools : new ArrayList<>();
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getArgumentHint() {
        return argumentHint;
    }

    public void setArgumentHint(String argumentHint) {
        this.argumentHint = argumentHint;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    @Override
    public String toString() {
        return "SkillFrontmatter{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", context='" + context + '\'' +
                ", userInvocable=" + userInvocable +
                ", disableModelInvocation=" + disableModelInvocation +
                ", allowedTools=" + allowedTools +
                '}';
    }
}

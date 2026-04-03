package com.claudecode.command;

/**
 * Command — 命令接口，整个命令/技能体系的顶层抽象
 *
 * =============================================
 *  核心概念：Skill 就是 Command
 * =============================================
 *
 * 在 Claude Code 的架构中，一个非常重要的设计决策是：
 *   **Skill 不是一个独立的概念，而是 type='prompt' 的 Command。**
 *
 * 也就是说，Skill 复用了整个 Command 基础设施（注册、发现、路由、执行），
 * 只不过它的执行方式是「将提示词注入对话」而非「直接运行代码」。
 *
 * 这个接口定义了所有命令共有的基础契约，无论它是 PROMPT 类型（Skill）还是 BUILTIN 类型。
 *
 * =============================================
 *  Command 的两种类型
 * =============================================
 *
 * 1. PROMPT 类型（Skill）：
 *    实现类：{@link PromptCommand}
 *    执行方式：加载 SKILL.md → 渲染变量 → 注入对话上下文 → LLM 按指令执行
 *    触发方式：用户输入 /name 或 LLM 通过 SkillTool 主动调用
 *    示例：/simplify, /commit, /review-pr
 *
 * 2. BUILTIN 类型：
 *    执行方式：直接调用 Java 方法
 *    触发方式：仅用户输入
 *    示例：/help, /clear, /exit
 *    注：当前版本 BUILTIN 命令仍在 Repl 中硬编码，暂不通过此接口管理
 *
 * =============================================
 *  与 Tool 的区别
 * =============================================
 *
 * 初学者容易混淆 Command 和 Tool，它们的区别在于：
 *
 * | 维度     | Tool（工具）                          | Command（命令/Skill）                   |
 * |----------|---------------------------------------|----------------------------------------|
 * | 触发者   | LLM 在对话中主动调用                   | 用户通过 /name 或 LLM 通过 SkillTool    |
 * | 执行内容 | 具体的操作（读文件、执行命令、搜索等）    | 一段提示词（指导 LLM 如何完成任务）       |
 * | 返回值   | 操作结果（文件内容、命令输出等）         | 渲染后的提示词文本                       |
 * | 注册位置 | ToolRegistry                          | CommandRegistry                         |
 * | 接口定义 | com.claudecode.tool.Tool              | com.claudecode.command.Command           |
 *
 * 但它们通过 SkillTool 产生了桥接：
 *   SkillTool 是一个 Tool（注册在 ToolRegistry 中），
 *   它的 execute() 方法内部会调用 CommandRegistry 来查找和执行 Skill。
 *   这样 LLM 就可以通过标准的工具调用机制来触发 Skill 了。
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/types/command.ts 中的 Command 类型定义
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see PromptCommand
 * @see CommandType
 * @see CommandSource
 * @see CommandRegistry
 */
public interface Command {

    /**
     * 返回命令名称
     *
     * 这个名称有多重用途：
     * 1. 作为斜杠命令的标识符：用户输入 /name 来触发
     * 2. 作为 SkillTool 的参数：LLM 通过 Skill tool 的 skill 参数引用此名称
     * 3. 作为 CommandRegistry 的注册 key：用于查找和去重
     *
     * 命名规范：
     * - 只允许小写字母、数字和连字符（a-z, 0-9, -）
     * - 例如："simplify", "review-pr", "xhs-note-creator"
     * - 插件来源的 Skill 使用 "pluginName:skillName" 格式
     *
     * @return 命令名称，不为 null 且不为空
     */
    String name();

    /**
     * 返回命令的描述信息
     *
     * 这个描述非常重要，因为它承担了两个关键职责：
     *
     * 1. 注入 system-reminder：
     *    所有可用 Skill 的 name + description 会被拼接成列表，
     *    注入到对话的系统提示中。LLM 通过阅读这个列表来判断
     *    当前用户的请求是否匹配某个 Skill。
     *
     * 2. 决定是否被自动触发：
     *    如果描述太模糊，LLM 可能永远不会主动调用这个 Skill。
     *    所以描述应该具体、准确地说明这个 Skill 做什么、什么时候该用。
     *
     * 长度限制：
     *    官方限制为最多 250 个字符。超出部分会被截断。
     *
     * @return 命令描述，建议不超过 250 字符
     */
    String description();

    /**
     * 返回命令类型
     *
     * @return {@link CommandType#PROMPT} 表示 Skill（提示词类型），
     *         {@link CommandType#BUILTIN} 表示内置命令
     * @see CommandType
     */
    CommandType type();

    /**
     * 返回命令的来源
     *
     * 来源决定了优先级：同名命令时，高优先级来源覆盖低优先级来源。
     *
     * @return 命令来源枚举值
     * @see CommandSource
     */
    CommandSource source();
}

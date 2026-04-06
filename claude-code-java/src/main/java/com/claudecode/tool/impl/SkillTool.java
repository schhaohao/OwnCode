package com.claudecode.tool.impl;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.CommandRenderer;
import com.claudecode.command.PromptCommand;
import com.claudecode.core.ForkExecutor;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.util.HashMap;
import java.util.Map;

/**
 * SkillTool — Skill 系统与 Tool 系统之间的桥梁
 *
 * =============================================
 *  这个类为什么存在？
 * =============================================
 *
 * 在 Claude Code 的架构中，LLM 只能通过「工具调用」（tool_use）与外部世界交互。
 * 而 Skill 是一种「提示词注入」机制，不是传统意义上的工具。
 *
 * 问题来了：LLM 怎么触发 Skill？
 *
 * 答案就是 SkillTool——它是一个注册在 ToolRegistry 中的标准 Tool，
 * LLM 可以像调用 Read、Bash 等工具一样调用它。
 * 但它的 execute() 方法不执行具体操作，而是：
 *   1. 从 CommandRegistry 中查找对应的 Skill
 *   2. 通过 CommandRenderer 渲染 Skill 内容（变量替换、shell 预处理）
 *   3. 将渲染后的提示词文本作为 ToolResult 返回
 *   4. AgentLoop 将这段文本注入到对话上下文中
 *   5. LLM 在下一轮对话中看到这段提示词，按照其中的指令继续执行
 *
 * 这就是 Skill 系统的核心运作原理：
 *   **通过一个 Tool 的外壳，将提示词注入到对话中。**
 *
 * =============================================
 *  两条触发路径
 * =============================================
 *
 * 路径 A：用户直接输入 /skill-name
 *   1. Repl 捕获 / 开头的输入
 *   2. 检查 CommandRegistry 是否有匹配的 Skill
 *   3. 将 /skill-name args 转化为普通用户消息交给 AgentLoop
 *   4. LLM 看到消息后调用 SkillTool（因为 system-reminder 中有说明）
 *   5. SkillTool.execute() 返回渲染后的 Skill 内容
 *
 * 路径 B：LLM 主动调用
 *   1. LLM 阅读 system-reminder 中的 Skill 列表
 *   2. 判断当前用户请求匹配某个 Skill
 *   3. 生成 tool_use: { name: "Skill", input: { skill: "simplify" } }
 *   4. AgentLoop 将 tool_use 路由到 SkillTool.execute()
 *   5. 返回渲染后的 Skill 内容
 *
 * 两条路径最终都汇聚到 SkillTool.execute()。
 *
 * =============================================
 *  Inline vs Fork
 * =============================================
 *
 * 当前版本实现了 Inline 和 Fork 两种模式：
 *
 * Inline 模式（默认）：
 * - Skill 的渲染内容作为 ToolResult 返回
 * - AgentLoop 将其包裹为 <command-name> 标签注入对话
 * - LLM 在当前对话上下文中继续执行
 *
 * Fork 模式：
 * - 通过 {@link ForkExecutor} 创建独立的子 AgentLoop
 * - Skill 渲染内容作为子 Agent 的初始 user message
 * - 子 Agent 有独立的对话历史、上下文管理和 maxTurns 限制（默认 30 轮）
 * - 子 Agent 执行完毕后，最终文本回复作为 ToolResult 返回主对话
 * - 子 Agent 不传入 CommandRegistry，无法调用 Skill，避免递归 fork
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/tools/SkillTool/SkillTool.ts（约 1100 行）
 *   Java 版做了大幅简化，保留核心链路
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see CommandRegistry
 * @see CommandRenderer
 * @see PromptCommand
 */
public class SkillTool implements Tool {

    /** 命令注册中心，用于查找 Skill */
    private final CommandRegistry commandRegistry;

    /** 内容渲染器，负责变量替换和 shell 预处理 */
    private final CommandRenderer renderer;

    /**
     * Fork 执行器（可选）
     *
     * <p>当 Skill 的 context="fork" 时，通过此执行器创建独立子 Agent 执行。
     * 如果为 null，则 fork 模式不可用，调用 fork Skill 时会返回错误。</p>
     *
     * @see ForkExecutor
     */
    private final ForkExecutor forkExecutor;

    /**
     * 构造 SkillTool（不支持 Fork 模式）
     *
     * <p>使用此构造函数创建的 SkillTool 只支持 Inline 模式。
     * 如果遇到 fork 模式的 Skill，将返回错误信息。</p>
     *
     * @param commandRegistry 命令注册中心（不能为 null）
     */
    public SkillTool(CommandRegistry commandRegistry) {
        this(commandRegistry, null);
    }

    /**
     * 构造 SkillTool（完整版，支持 Inline + Fork 两种模式）
     *
     * <p>生产环境应使用此构造函数，传入 ForkExecutor 以支持 Fork 模式。
     * ForkExecutor 由 {@link com.claudecode.ClaudeCode} 入口类组装并注入。</p>
     *
     * @param commandRegistry 命令注册中心（不能为 null）
     * @param forkExecutor    Fork 执行器（可以为 null，为 null 时不支持 fork 模式）
     */
    public SkillTool(CommandRegistry commandRegistry, ForkExecutor forkExecutor) {
        this.commandRegistry = commandRegistry;
        this.renderer = new CommandRenderer();
        this.forkExecutor = forkExecutor;
    }

    // ==================== Tool 接口实现 ====================

    /**
     * 工具名称：Skill
     *
     * LLM 通过 tool_use: { name: "Skill", ... } 来调用此工具。
     * 这个名称与官方 Claude Code 保持一致。
     */
    @Override
    public String name() {
        return "Skill";
    }

    /**
     * 工具描述（给 LLM 看的）
     *
     * 这段描述告诉 LLM：
     * - SkillTool 是做什么的
     * - 如何调用（参数格式）
     * - 什么时候应该调用
     * - 注意事项
     *
     * 与官方的 SkillTool 描述保持一致。
     */
    @Override
    public String description() {
        return "Execute a skill within the main conversation.\n\n"
                + "When users ask you to perform tasks, check if any of the available skills match. "
                + "Skills provide specialized capabilities and domain knowledge.\n\n"
                + "When users reference a \"slash command\" or \"/<something>\" (e.g., \"/commit\", \"/review-pr\"), "
                + "they are referring to a skill. Use this tool to invoke it.\n\n"
                + "How to invoke:\n"
                + "- Use this tool with the skill name and optional arguments\n"
                + "- Examples:\n"
                + "  - skill: \"pdf\" - invoke the pdf skill\n"
                + "  - skill: \"commit\", args: \"-m 'Fix bug'\" - invoke with arguments\n\n"
                + "Important:\n"
                + "- Available skills are listed in system-reminder messages in the conversation\n"
                + "- When a skill matches the user's request, invoke the relevant Skill tool "
                + "BEFORE generating any other response about the task\n"
                + "- NEVER mention a skill without actually calling this tool\n"
                + "- If you see a <command-name> tag in the current conversation turn, "
                + "the skill has ALREADY been loaded - follow the instructions directly";
    }

    /**
     * 输入参数的 JSON Schema 定义
     *
     * 参数说明：
     * - skill（必需）：要调用的 Skill 名称，如 "simplify"、"commit"
     * - args（可选）：传给 Skill 的参数字符串，如 "-m 'Fix bug'"
     *
     * LLM 发送的 tool_use 格式示例：
     * <pre>
     * {
     *   "type": "tool_use",
     *   "name": "Skill",
     *   "input": {
     *     "skill": "simplify",
     *     "args": "src/main/java"
     *   }
     * }
     * </pre>
     */
    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // skill 参数：Skill 名称（必需）
        Map<String, Object> skillProp = new HashMap<>();
        skillProp.put("type", "string");
        skillProp.put("description", "The skill name to invoke. "
                + "E.g., \"commit\", \"review-pr\", or \"plugin-name:skill-name\"");
        properties.put("skill", skillProp);

        // args 参数：传给 Skill 的参数（可选）
        Map<String, Object> argsProp = new HashMap<>();
        argsProp.put("type", "string");
        argsProp.put("description", "Optional arguments for the skill");
        properties.put("args", argsProp);

        schema.put("properties", properties);
        schema.put("required", new String[]{"skill"});

        return schema;
    }

    /**
     * SkillTool 本身不需要权限审批
     *
     * 为什么？
     * - SkillTool 只是查找 Skill 并返回渲染后的提示词文本
     * - 它不执行任何有副作用的操作（不写文件、不执行命令）
     * - 实际的工具调用（如 Bash、Edit）会在 LLM 执行 Skill 指令时触发，
     *   那时候由各自工具的 requiresPermission() 来控制权限
     *
     * 但注意：Skill 中的 shell 预处理（!`command`）会在本地执行命令，
     * 这是一个安全隐患。未来应该对来自不信任来源的 Skill 增加权限检查。
     */
    @Override
    public boolean requiresPermission() {
        return false;
    }

    /**
     * 执行 Skill：查找、渲染、返回
     *
     * 这是 SkillTool 的核心方法，完整执行流程：
     *
     * 1. 从 input 中提取 skill 名称和 args 参数
     * 2. 在 CommandRegistry 中查找对应的 PromptCommand
     * 3. 验证调用权限（是否允许 LLM 调用）
     * 4. 检查执行模式（inline 或 fork）
     * 5. 通过 CommandRenderer 渲染 Skill 内容
     * 6. 返回渲染后的文本作为 ToolResult
     *
     * 返回的文本会被 AgentLoop 包裹为 <command-name> 标签注入对话，
     * LLM 在下一轮对话中会看到并遵循这些指令。
     *
     * @param input LLM 传入的参数 Map，包含 "skill" 和可选的 "args"
     * @return 渲染后的 Skill 内容（成功），或错误信息（失败）
     */
    @Override
    public ToolResult execute(Map<String, Object> input) {
        // ---- 步骤 1：提取参数 ----
        String skillName = (String) input.get("skill");
        if (skillName == null || skillName.trim().isEmpty()) {
            return ToolResult.error("Missing required parameter: 'skill'. "
                    + "Please specify the skill name to invoke.");
        }
        skillName = skillName.trim();

        String args = "";
        if (input.containsKey("args") && input.get("args") != null) {
            args = String.valueOf(input.get("args")).trim();
        }

        // ---- 步骤 2：查找 Skill ----
        PromptCommand command = commandRegistry.getCommand(skillName);
        if (command == null) {
            return ToolResult.error("Unknown skill: '" + skillName + "'. "
                    + "Available skills: "
                    + String.join(", ", getAvailableSkillNames()));
        }

        // ---- 步骤 3：验证调用权限 ----
        // 如果 Skill 设置了 disableModelInvocation，只有用户通过 /name 才能触发
        // 这里的调用可能来自 LLM 的 tool_use，需要检查
        if (command.isDisableModelInvocation()) {
            return ToolResult.error("Skill '" + skillName + "' can only be invoked by the user "
                    + "via /" + skillName + " command, not by the model.");
        }

        // ---- 步骤 4：检查执行模式并分发 ----
        if (command.isFork()) {
            // ---- Fork 模式：在独立子 Agent 中执行 ----
            // Fork 模式将 Skill 内容发送到一个全新的子 AgentLoop，
            // 子 Agent 有独立的对话历史和 token 预算，
            // 执行完毕后，子 Agent 的最终文本回复作为 ToolResult 返回主对话。
            //
            // 前置条件：ForkExecutor 必须已注入（非 null）
            if (forkExecutor == null) {
                return ToolResult.error("Skill '" + skillName + "' requires fork execution mode, "
                        + "but ForkExecutor is not available. "
                        + "This is likely a configuration issue.");
            }

            try {
                // 先渲染 Skill 内容（与 inline 模式相同的渲染逻辑）
                String rendered = renderer.render(command, args);
                // 委托 ForkExecutor 创建子 Agent 执行
                return forkExecutor.execute(command, rendered);
            } catch (Exception e) {
                return ToolResult.error("Failed to execute fork skill '" + skillName + "': "
                        + e.getMessage());
            }
        }

        // ---- 步骤 5：渲染 Skill 内容 ----
        try {
            String rendered = renderer.render(command, args);

            // ---- 步骤 6：返回渲染后的内容 ----
            // AgentLoop 会将这段文本包裹为 <command-name> 标签注入对话
            return ToolResult.success(rendered);

        } catch (Exception e) {
            return ToolResult.error("Failed to render skill '" + skillName + "': " + e.getMessage());
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取所有可用 Skill 的名称列表
     *
     * 用于在错误信息中展示可用选项，帮助 LLM 修正调用。
     *
     * @return Skill 名称列表
     */
    private java.util.List<String> getAvailableSkillNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (PromptCommand cmd : commandRegistry.getAllCommands()) {
            names.add(cmd.name());
        }
        return names;
    }
}

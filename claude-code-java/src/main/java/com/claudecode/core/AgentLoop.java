package com.claudecode.core;

import com.claudecode.api.ClaudeApiClient;
import com.claudecode.api.model.*;
import com.claudecode.cli.TerminalRenderer;
import com.claudecode.command.CommandRegistry;
import com.claudecode.memory.MemoryManager;
import com.claudecode.permission.PermissionManager;
import com.claudecode.permission.PermissionManager.PermissionResult;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Agent Loop — 整个应用最核心的类，驱动 "思考-行动" 循环
 *
 * 核心原理：
 *   这是一个 while(true) 循环，每一轮做以下事情：
 *   1. 把 [系统提示 + 工具定义 + 对话历史] 组装成 API 请求
 *   2. 调用 Claude API（流式），实时输出文本到终端
 *   3. 等 API 响应完毕，检查 stop_reason：
 *      - "end_turn"  → LLM认为任务完成，退出循环，返回最终文本
 *      - "tool_use"  → LLM想要调用工具，进入步骤4
 *      - "max_tokens"→ 输出被截断，可以提示用户或自动续写
 *   4. 从响应中提取所有 tool_use 块，逐个执行（如果可以实现并行执行更好）：
 *      a. 先调用 PermissionManager 检查权限
 *      b. 权限通过则调用 ToolRegistry.execute() 执行工具
 *      c. 收集所有 ToolResult
 *   5. 将 assistant 消息（含tool_use）和 tool_result 追加到对话历史
 *   6. 回到步骤1，继续循环
 *
 * 依赖的组件（通过构造函数注入）：
 * - ClaudeApiClient：负责与Claude API通信
 * - ToolRegistry：负责工具的查找和执行
 * - PermissionManager：负责工具调用前的权限审批
 * - ConversationHistory：负责维护对话消息列表
 *
 * 需要实现的方法：
 *
 * 1. public String run(String userInput)
 *    - 接收用户输入，驱动完整的 agent loop，返回最终文本回复
 *    - 这是外部调用的唯一入口
 *
 * 2. private ApiRequest buildRequest()
 *    - 组装 API 请求：system prompt + tool definitions + messages
 *    - 从 ToolRegistry 获取所有工具的 ToolDefinition
 *    - 从 ConversationHistory 获取消息列表
 *
 * 3. private List<ToolResultBlock> executeTools(List<ToolUseBlock> toolUses)
 *    - 遍历所有 tool_use 块
 *    - 对每个 tool_use：权限检查 → 执行 → 收集结果
 *    - 如果权限被拒绝，返回 error 类型的 ToolResult
 *
 * 4. private boolean checkPermission(ToolUseBlock toolUse)
 *    - 调用 PermissionManager 评估权限
 *    - 如果需要用户审批（ASK），在终端提示用户确认
 *    - 返回 true=允许执行，false=拒绝
 *
 * 设计要点：
 * - 设置最大轮次限制（如 maxTurns=50），防止无限循环
 * - 每轮循环前检查上下文是否接近窗口限制，必要时触发压缩
 * - 文本delta通过回调函数实时输出（传给 ClaudeApiClient）
 */
public class AgentLoop {

    /** 主 Agent 默认最大循环轮次 */
    private static final int DEFAULT_MAX_TURNS = 50;

    /** Claude API 通信客户端 */
    private final ClaudeApiClient apiClient;

    /** 工具注册中心 */
    private final ToolRegistry toolRegistry;

    /** 权限管理器（Human-in-the-loop） */
    private final PermissionManager permissionManager;

    /** 对话历史 */
    private final ConversationHistory history;

    /** 上下文窗口管理器 */
    private final ContextManager contextManager;

    /** 系统提示（告诉 LLM 它的角色和行为规范） */
    private final String systemPrompt;

    /** 记忆系统门面（可选）。 */
    private final MemoryManager memoryManager;

    /** 命令/技能注册中心（管理所有 Skill，生成 Skill 列表注入系统提示词） */
    private final CommandRegistry commandRegistry;

    /** 模型名称（显式传递给 API 请求） */
    private final String model;

    /** 终端渲染器（统一管理输出格式和颜色） */
    private final TerminalRenderer renderer;

    /** 文本增量回调（实时输出到终端） */
    private final Consumer<String> outputCallback;

    /**
     * 最大循环轮次（实例级别，支持子 Agent 配置不同的值）
     *
     * <p>主 Agent 默认 50 轮，Fork 子 Agent 通常设置为更小的值（如 30 轮），
     * 以限制子 Agent 的 token 预算和执行范围。</p>
     */
    private final int maxTurns;

    /**
     * 最简构造函数（无 CommandRegistry，默认渲染器和输出回调）
     */
    public AgentLoop(ClaudeApiClient apiClient,
                     ToolRegistry toolRegistry,
                     PermissionManager permissionManager,
                     String systemPrompt) {
        this(apiClient, toolRegistry, permissionManager, systemPrompt, null,
             null, new TerminalRenderer(), System.out::print, DEFAULT_MAX_TURNS);
    }

    /**
     * 带 CommandRegistry 的便捷构造函数
     */
    public AgentLoop(ClaudeApiClient apiClient,
                     ToolRegistry toolRegistry,
                     PermissionManager permissionManager,
                     String systemPrompt,
                     CommandRegistry commandRegistry) {
        this(apiClient, toolRegistry, permissionManager, systemPrompt, commandRegistry,
             null, new TerminalRenderer(), System.out::print, DEFAULT_MAX_TURNS);
    }

    /**
     * 带自定义渲染器和输出回调的构造函数（maxTurns 使用默认值）
     */
    public AgentLoop(ClaudeApiClient apiClient,
                     ToolRegistry toolRegistry,
                     PermissionManager permissionManager,
                     String systemPrompt,
                     CommandRegistry commandRegistry,
                     TerminalRenderer renderer,
                     Consumer<String> outputCallback) {
        this(apiClient, toolRegistry, permissionManager, systemPrompt, commandRegistry,
             null, renderer, outputCallback, DEFAULT_MAX_TURNS);
    }

    /**
     * 带记忆系统的便捷构造函数（maxTurns 使用默认值）。
     */
    public AgentLoop(ClaudeApiClient apiClient,
                     ToolRegistry toolRegistry,
                     PermissionManager permissionManager,
                     String systemPrompt,
                     CommandRegistry commandRegistry,
                     MemoryManager memoryManager,
                     TerminalRenderer renderer,
                     Consumer<String> outputCallback) {
        this(apiClient, toolRegistry, permissionManager, systemPrompt, commandRegistry,
             memoryManager, renderer, outputCallback, DEFAULT_MAX_TURNS);
    }

    /**
     * 全参数构造函数 — 支持完整配置，包括自定义 maxTurns
     *
     * <p>此构造函数主要供 {@link com.claudecode.core.ForkExecutor} 在创建子 Agent 时使用，
     * 子 Agent 通常需要比主 Agent 更小的 maxTurns 值来限制执行范围和 token 预算。</p>
     *
     * @param apiClient        Claude API 通信客户端（父子 Agent 共享同一实例）
     * @param toolRegistry     工具注册中心（子 Agent 可能使用过滤后的工具集）
     * @param permissionManager 权限管理器（父子 Agent 共享同一实例）
     * @param systemPrompt     系统提示词（子 Agent 使用专用的简化提示词）
     * @param commandRegistry  命令/技能注册中心（子 Agent 通常传 null，避免递归 fork）
     * @param renderer         终端渲染器
     * @param outputCallback   文本增量回调
     * @param maxTurns         最大循环轮次（主 Agent 默认 50，子 Agent 建议 30）
     */
    public AgentLoop(ClaudeApiClient apiClient,
                     ToolRegistry toolRegistry,
                     PermissionManager permissionManager,
                     String systemPrompt,
                     CommandRegistry commandRegistry,
                     TerminalRenderer renderer,
                     Consumer<String> outputCallback,
                     int maxTurns) {
        this(apiClient, toolRegistry, permissionManager, systemPrompt, commandRegistry,
             null, renderer, outputCallback, maxTurns);
    }

    public AgentLoop(ClaudeApiClient apiClient,
                     ToolRegistry toolRegistry,
                     PermissionManager permissionManager,
                     String systemPrompt,
                     CommandRegistry commandRegistry,
                     MemoryManager memoryManager,
                     TerminalRenderer renderer,
                     Consumer<String> outputCallback,
                     int maxTurns) {
        this.apiClient = apiClient;
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
        this.systemPrompt = systemPrompt;
        this.commandRegistry = commandRegistry;
        this.memoryManager = memoryManager;
        this.model = apiClient.getDefaultModel();
        this.renderer = renderer;
        this.outputCallback = outputCallback;
        this.maxTurns = maxTurns;
        this.history = new ConversationHistory();
        this.contextManager = memoryManager != null ? memoryManager.getContextManager() : new ContextManager();
    }

    // ==================== 核心循环 ====================

    /**
     * 接收用户输入，驱动完整的 agent loop，返回最终文本回复
     *
     * 流程：
     *   1. 将用户输入追加到对话历史
     *   2. 进入 while 循环：
     *      a. 检查上下文窗口 → 必要时压缩
     *      b. 构建 API 请求 → 流式调用 Claude
     *      c. 将 assistant 响应追加到历史
     *      d. 检查 stop_reason：
     *         - end_turn → 退出循环，返回文本
     *         - tool_use → 执行工具，追加结果到历史，继续循环
     *         - max_tokens → 退出循环，提示截断
     *
     * @param userInput 用户输入的文本
     * @return LLM 最终的文本回复
     */
    public String run(String userInput) throws Exception {
        if (memoryManager != null) {
            memoryManager.beginTurn();
        }

        // 1. 将用户输入追加到对话历史
        history.addUserMessage(userInput);

        int turns = 0;
        while (turns < maxTurns) {
            // 2a. 上下文窗口检查与压缩
            if (memoryManager != null) {
                memoryManager.beforeApiCall(history);
            } else if (contextManager.isNearLimit(history)) {
                System.err.println("[Context] Approaching token limit, compacting history...");
                contextManager.compact(history);
            }

            // 2b. 构建请求并流式调用 Claude API
            // 包装回调：在第一个 text_delta 到来时自动输出 ◆ 前缀
            AtomicBoolean textStarted = new AtomicBoolean(false);
            Consumer<String> wrappedCallback = text -> {
                if (textStarted.compareAndSet(false, true)) {
                    renderer.renderTextPrefix();
                }
                outputCallback.accept(text);
            };

            ApiRequest request = buildRequest(userInput);
            ApiResponse response;
            try {
                response = apiClient.sendMessageStream(request, wrappedCallback);
            } catch (Exception e) {
                System.err.println("\n[Error] API call failed: " + e.getMessage());
                throw e;
            }

            // 流式文本结束后输出换行
            if (textStarted.get()) {
                renderer.renderTextSuffix();
            }

            // 2c. 将 assistant 响应追加到对话历史
            history.addAssistantResponse(response);

            // 2d. 检查 stop_reason，决定是否继续循环
            String stopReason = response.getStopReason();

            if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason)) {
                // LLM 认为任务完成 → 退出循环
                if (memoryManager != null) {
                    memoryManager.afterTurn(history);
                }
                return response.getTextContent();
            }

            if ("max_tokens".equals(stopReason)) {
                // 输出被截断
                if (memoryManager != null) {
                    memoryManager.afterTurn(history);
                }
                return response.getTextContent() + "\n[Warning] Response was truncated (max_tokens reached).";
            }

            if (!"tool_use".equals(stopReason)) {
                // 未知的 stop_reason，安全退出
                if (memoryManager != null) {
                    memoryManager.afterTurn(history);
                }
                return response.getTextContent();
            }

            // ---- stop_reason == "tool_use" → 执行工具 ----

            // 提取所有工具调用块
            List<ContentBlock> toolUseBlocks = response.getToolUseBlocks();
            List<ToolUseBlock> toolUses = new ArrayList<>();
            for (ContentBlock block : toolUseBlocks) {
                toolUses.add((ToolUseBlock) block);
            }

            // 执行工具（带权限检查）
            List<ToolResultBlock> results = executeTools(toolUses);

            // 将工具结果追加到对话历史（作为 user 消息）
            history.addToolResults(results);
            if (memoryManager != null) {
                memoryManager.afterTurn(history);
            }

            turns++;
        }

        // 达到最大轮次限制
        System.err.println("[Warning] Reached maximum turns (" + maxTurns + "). Stopping agent loop.");
        return "[Agent loop stopped] Reached maximum turns (" + maxTurns + ").";
    }

    // ==================== 请求构建 ====================

    /**
     * 组装 API 请求：system prompt + skill listing + tool definitions + messages
     *
     * 相比原版新增了 Skill 列表注入：
     * 如果 CommandRegistry 中有注册的 Skill，会将它们的 name + description 列表
     * 以 <system-reminder> 格式追加到系统提示词末尾。
     * 这样 LLM 就能知道有哪些 Skill 可用，并在合适的时候主动调用 SkillTool。
     */
    private ApiRequest buildRequest(String latestUserInput) {
        // 拼接系统提示词 + Skill 列表摘要
        String skillListing = commandRegistry != null ? commandRegistry.buildSkillListing() : "";
        String fullSystemPrompt;
        if (memoryManager != null) {
            fullSystemPrompt = memoryManager.buildSystemPrompt(systemPrompt, skillListing, latestUserInput);
        } else {
            fullSystemPrompt = systemPrompt;
            if (!skillListing.isEmpty()) {
                fullSystemPrompt = systemPrompt + "\n" + skillListing;
            }
        }

        return ApiRequest.builder()
                .model(model)
                .system(fullSystemPrompt)
                .tools(toolRegistry.getAllDefinitions())
                .messages(new ArrayList<>(history.getMessages()))
                .build();
    }

    // ==================== 工具执行 ====================

    /**
     * 遍历所有 tool_use 块，逐个执行
     *
     * 对每个 tool_use：
     *   1. 在终端显示工具调用信息
     *   2. 权限检查 → ALLOW/DENY/ASK
     *   3. 权限通过则执行工具
     *   4. 收集 ToolResultBlock
     *
     * 特殊处理：当工具名为 "Skill" 时，将返回结果包裹为 &lt;command-name&gt; 标签。
     * 这个标签告诉 LLM：「这段内容是一个 Skill 的指令，请按照其中的指示执行」，
     * 而不是普通的工具输出数据。
     */
    private List<ToolResultBlock> executeTools(List<ToolUseBlock> toolUses) {
        List<ToolResultBlock> results = new ArrayList<>();

        for (ToolUseBlock toolUse : toolUses) {
            String toolName = toolUse.getName();

            if (memoryManager != null) {
                memoryManager.onToolCall();
            }

            // 通过 TerminalRenderer 统一渲染工具调用信息
            renderer.renderToolCall(toolName, toolUse.getInput());

            // 权限检查
            if (!checkPermission(toolUse)) {
                ToolResult denied = ToolResult.error("Permission denied by user for tool: " + toolName);
                renderer.renderToolResult(toolName, denied);
                results.add(new ToolResultBlock(
                        toolUse.getId(),
                        denied.getContent(),
                        true));
                continue;
            }

            // 执行工具
            ToolResult result = toolRegistry.execute(toolName, toolUse.getInput());

            // ---- Skill 结果特殊处理 ----
            // 当工具是 SkillTool 且执行成功时，将渲染后的 Skill 内容包裹为 <command-name> 标签。
            // 这个标签的作用：
            // 1. 告诉 LLM 这是一个已加载的 Skill 指令（不是普通数据）
            // 2. LLM 看到 <command-name> 标签后会直接遵循其中的指令，不会再重复调用 SkillTool
            // 3. 与官方 Claude Code 的行为一致
            if ("Skill".equals(toolName) && !result.isError()) {
                String skillName = toolUse.getInput() != null
                        ? String.valueOf(toolUse.getInput().get("skill"))
                        : "unknown";
                String wrapped = "<command-name>" + skillName + "</command-name>\n"
                        + result.getContent();
                result = ToolResult.success(wrapped);
            }

            // 通过 TerminalRenderer 统一渲染执行结果
            renderer.renderToolResult(toolName, result);

            results.add(ToolResultBlock.from(toolUse.getId(), result));
        }

        return results;
    }

    /**
     * 权限检查：调用 PermissionManager 评估，必要时提示用户审批
     *
     * @return true=允许执行，false=拒绝
     */
    private boolean checkPermission(ToolUseBlock toolUse) {
        Tool tool = toolRegistry.getTool(toolUse.getName());
        boolean requiresPermission = tool != null && tool.requiresPermission();

        PermissionResult result = permissionManager.evaluate(
                toolUse.getName(), toolUse.getInput(), requiresPermission);

        switch (result) {
            case ALLOW:
                return true;
            case DENY:
                return false;
            case ASK:
                return permissionManager.promptUser(toolUse.getName(), toolUse.getInput());
            default:
                return false;
        }
    }

    // ==================== Getter ====================

    /**
     * 获取对话历史（供 Repl 清空历史等操作使用）
     */
    public ConversationHistory getHistory() {
        return history;
    }

    /**
     * 获取 Claude API 客户端
     *
     * <p>供 {@link ForkExecutor} 创建子 AgentLoop 时复用同一个 API 连接。</p>
     */
    public ClaudeApiClient getApiClient() {
        return apiClient;
    }

    /**
     * 获取工具注册中心
     *
     * <p>供 {@link ForkExecutor} 创建子 Agent 的过滤工具集时使用。</p>
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 获取权限管理器
     *
     * <p>供 {@link ForkExecutor} 创建子 AgentLoop 时共享同一个权限管理器。</p>
     */
    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * 获取系统提示词
     *
     * <p>供 {@link ForkExecutor} 构建子 Agent 的系统提示词时作为参考或基础。</p>
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 获取终端渲染器
     *
     * <p>供 {@link ForkExecutor} 创建子 AgentLoop 时复用终端渲染能力。</p>
     */
    public TerminalRenderer getRenderer() {
        return renderer;
    }

    /**
     * 获取文本输出回调
     *
     * <p>供 {@link ForkExecutor} 创建子 AgentLoop 时复用输出通道。</p>
     */
    public Consumer<String> getOutputCallback() {
        return outputCallback;
    }
}

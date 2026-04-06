package com.claudecode.core;

import com.claudecode.api.ClaudeApiClient;
import com.claudecode.cli.TerminalRenderer;
import com.claudecode.command.PromptCommand;
import com.claudecode.permission.PermissionManager;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.ToolResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * ForkExecutor — Skill Fork 执行模式的核心执行器
 *
 * <h2>一、Fork 模式是什么？</h2>
 *
 * <p>在 Claude Code 的 Skill 系统中，有两种执行模式：</p>
 *
 * <table border="1">
 *   <tr><th>模式</th><th>行为</th><th>适用场景</th></tr>
 *   <tr>
 *     <td><b>Inline</b>（默认）</td>
 *     <td>Skill 的提示词内容被展开为消息，注入当前对话上下文，
 *         LLM 在当前对话中继续执行</td>
 *     <td>简单指令，需要访问当前对话上下文的任务</td>
 *   </tr>
 *   <tr>
 *     <td><b>Fork</b></td>
 *     <td>创建一个独立的子 Agent，Skill 内容作为子 Agent 的初始提示词，
 *         子 Agent 独立执行，完成后将文本结果返回主对话</td>
 *     <td>独立任务，不需要主对话上下文，如代码分析、文件搜索、专项调研</td>
 *   </tr>
 * </table>
 *
 * <h2>二、为什么需要 Fork 模式？</h2>
 *
 * <ol>
 *   <li><b>上下文隔离</b>：子 Agent 有独立的对话历史，不会污染主对话的上下文窗口。
 *       对于探索性任务（如搜索代码、分析文件），子 Agent 可能产生大量中间结果，
 *       这些中间结果不应该占用主对话的宝贵上下文空间。</li>
 *   <li><b>Token 预算控制</b>：子 Agent 有独立的 maxTurns 限制（默认 30 轮），
 *       防止失控的子任务消耗过多 API 调用。</li>
 *   <li><b>工具范围限制</b>：子 Agent 可以使用 Skill 声明的 allowedTools 子集，
 *       减少不必要的工具调用风险。</li>
 *   <li><b>结果聚焦</b>：主对话只看到子 Agent 的最终结论，不需要关心中间过程。</li>
 * </ol>
 *
 * <h2>三、执行流程</h2>
 *
 * <pre>
 * SkillTool.execute() 检测到 fork 模式
 *   │
 *   ▼
 * ForkExecutor.execute(command, renderedPrompt)
 *   │
 *   ├── 1. 构建子 Agent 的系统提示词（简化版，不含 Skill 列表）
 *   │
 *   ├── 2. 构建子 Agent 的工具集
 *   │      └── 有 allowedTools？ → 过滤工具集
 *   │          无 allowedTools？ → 继承父 Agent 全部工具
 *   │
 *   ├── 3. 创建子 AgentLoop（独立 ConversationHistory、独立 ContextManager）
 *   │      └── maxTurns = FORK_MAX_TURNS (30)
 *   │      └── commandRegistry = null （防止递归 fork）
 *   │
 *   ├── 4. 在终端显示 Fork 开始标记
 *   │
 *   ├── 5. childAgentLoop.run(renderedPrompt)
 *   │      └── 子 Agent 独立执行，实时输出到终端
 *   │
 *   ├── 6. 在终端显示 Fork 结束标记
 *   │
 *   └── 7. 返回 ToolResult.success(子 Agent 的最终文本回复)
 *          └── 主对话的 AgentLoop 将此结果作为 tool_result 继续对话
 * </pre>
 *
 * <h2>四、与主 Agent 共享的资源</h2>
 *
 * <ul>
 *   <li>{@link ClaudeApiClient} — 共享同一个 HTTP 客户端和 API 连接</li>
 *   <li>{@link PermissionManager} — 共享同一套权限规则和会话记忆
 *       （用户在主对话中批准过的操作，子 Agent 也不会重复询问）</li>
 *   <li>{@link TerminalRenderer} — 共享终端渲染器</li>
 * </ul>
 *
 * <h2>五、子 Agent 独立的资源</h2>
 *
 * <ul>
 *   <li>{@link ConversationHistory} — 全新的对话历史（由子 AgentLoop 内部创建）</li>
 *   <li>{@link ContextManager} — 独立的上下文窗口管理（由子 AgentLoop 内部创建）</li>
 *   <li>{@link ToolRegistry} — 可能是过滤后的工具子集</li>
 *   <li>系统提示词 — 子 Agent 专用的简化版本</li>
 * </ul>
 *
 * <h2>六、安全性考量</h2>
 *
 * <ul>
 *   <li>子 Agent 不传入 CommandRegistry，因此无法调用 SkillTool，
 *       从根本上杜绝了递归 fork 的风险</li>
 *   <li>子 Agent 共享父 Agent 的 PermissionManager，
 *       所有需要权限审批的工具调用仍然需要用户确认</li>
 *   <li>子 Agent 有独立的 maxTurns 限制（默认 30 轮），
 *       即使任务无法完成也会自动终止</li>
 * </ul>
 *
 * @author sunchenhao
 * @date 2026/4/6
 * @see AgentLoop
 * @see com.claudecode.tool.impl.SkillTool
 * @see PromptCommand#isFork()
 */
public class ForkExecutor {

    /**
     * 子 Agent 的默认最大循环轮次
     *
     * <p>比主 Agent 的默认值（50 轮）更小，作为 token 预算控制手段。
     * 30 轮足以完成大多数独立任务（如代码搜索、文件分析），
     * 同时防止失控的子任务无限消耗 API 调用。</p>
     */
    private static final int FORK_MAX_TURNS = 30;

    // ==================== 共享依赖（与主 AgentLoop 复用） ====================

    /** Claude API 通信客户端（复用父 Agent 的 HTTP 连接） */
    private final ClaudeApiClient apiClient;

    /**
     * 父 Agent 的工具注册中心
     *
     * <p>当 Skill 未声明 allowedTools 时，子 Agent 继承此完整工具集。
     * 当 Skill 声明了 allowedTools 时，从此工具集中过滤出子集。</p>
     */
    private final ToolRegistry parentToolRegistry;

    /** 权限管理器（共享会话级别的权限记忆） */
    private final PermissionManager permissionManager;

    /** 终端渲染器（共享终端输出格式） */
    private final TerminalRenderer renderer;

    /** 文本输出回调（共享终端输出通道） */
    private final Consumer<String> outputCallback;

    // ==================== 子 Agent 专用的系统提示词 ====================

    /**
     * Fork 模式子 Agent 的系统提示词模板
     *
     * <p>设计要点：</p>
     * <ol>
     *   <li>告诉子 Agent 它是一个独立执行特定任务的 Agent</li>
     *   <li>要求子 Agent 专注于当前任务，不要偏离</li>
     *   <li>提供基础的工具使用指导</li>
     *   <li><b>不包含 Skill 列表</b>——因为子 Agent 没有 CommandRegistry，
     *       无法调用 Skill，也不应该尝试调用</li>
     * </ol>
     *
     * <p>%s 占位符会被替换为 Skill 的名称，让子 Agent 知道自己在执行哪个 Skill。</p>
     */
    private static final String FORK_SYSTEM_PROMPT_TEMPLATE =
            "You are a focused sub-agent executing a specific task as part of a larger conversation.\n\n"
            + "## Context\n"
            + "You have been forked from the main agent to independently execute the skill: \"%s\".\n"
            + "You have your own conversation history and token budget.\n"
            + "Your final text response will be returned to the main conversation as the skill's result.\n\n"
            + "## Guidelines\n"
            + "- Focus exclusively on the task described in the user message below.\n"
            + "- Use the available tools to accomplish the task.\n"
            + "- Be thorough but concise — your output will be injected back into the main conversation.\n"
            + "- Do NOT attempt to invoke skills or slash commands — they are not available to you.\n"
            + "- When the task is complete, provide a clear, well-structured summary of your findings or results.\n";

    // ==================== 构造函数 ====================

    /**
     * 构造 ForkExecutor
     *
     * <p>通常在应用启动时创建一次，然后被注入到 {@link com.claudecode.tool.impl.SkillTool} 中。
     * 每次 fork 执行时，ForkExecutor 会利用这些共享依赖创建新的子 AgentLoop。</p>
     *
     * @param apiClient          Claude API 客户端（与主 Agent 共享）
     * @param parentToolRegistry 父 Agent 的完整工具注册中心
     * @param permissionManager  权限管理器（与主 Agent 共享）
     * @param renderer           终端渲染器
     * @param outputCallback     文本输出回调
     */
    public ForkExecutor(ClaudeApiClient apiClient,
                        ToolRegistry parentToolRegistry,
                        PermissionManager permissionManager,
                        TerminalRenderer renderer,
                        Consumer<String> outputCallback) {
        this.apiClient = apiClient;
        this.parentToolRegistry = parentToolRegistry;
        this.permissionManager = permissionManager;
        this.renderer = renderer;
        this.outputCallback = outputCallback;
    }

    // ==================== 核心执行方法 ====================

    /**
     * 执行 Fork 模式的 Skill：创建子 Agent，独立执行，返回结果
     *
     * <p>这是 ForkExecutor 的核心方法。完整的执行流程：</p>
     *
     * <ol>
     *   <li><b>构建系统提示词</b>：使用 FORK_SYSTEM_PROMPT_TEMPLATE，注入 Skill 名称</li>
     *   <li><b>构建工具集</b>：
     *       <ul>
     *         <li>如果 Skill 声明了 allowedTools → 从父工具集中过滤出子集</li>
     *         <li>如果 allowedTools 为空 → 继承父 Agent 的全部工具（但不包含 SkillTool）</li>
     *       </ul>
     *   </li>
     *   <li><b>创建子 AgentLoop</b>：独立的对话历史、上下文管理、maxTurns=30</li>
     *   <li><b>显示 Fork 开始标记</b>：在终端输出视觉标记，让用户知道进入了子 Agent</li>
     *   <li><b>运行子 Agent</b>：将渲染后的 Skill 内容作为 user message，驱动子 Agent 循环</li>
     *   <li><b>显示 Fork 结束标记</b>：在终端输出结束标记</li>
     *   <li><b>返回结果</b>：子 Agent 的最终文本回复包装为 ToolResult</li>
     * </ol>
     *
     * <h3>错误处理</h3>
     * <p>如果子 Agent 执行过程中抛出异常（如 API 调用失败、网络超时），
     * 异常会被捕获并转换为 ToolResult.error()，不会导致主 Agent 崩溃。</p>
     *
     * @param command        要执行的 Fork Skill（包含元数据：名称、allowedTools 等）
     * @param renderedPrompt 经过 CommandRenderer 渲染后的 Skill 内容
     *                       （已完成 shell 预处理和变量替换）
     * @return 子 Agent 的执行结果：
     *         成功时包含子 Agent 的最终文本回复，
     *         失败时包含错误信息
     */
    public ToolResult execute(PromptCommand command, String renderedPrompt) {
        String skillName = command.name();

        try {
            // ---- 步骤 1：构建子 Agent 的系统提示词 ----
            String childSystemPrompt = buildChildSystemPrompt(skillName);

            // ---- 步骤 2：构建子 Agent 的工具集 ----
            ToolRegistry childToolRegistry = buildChildToolRegistry(command.getAllowedTools());

            // ---- 步骤 3：创建子 AgentLoop ----
            // 关键设计决策：
            // - commandRegistry 传 null：子 Agent 不能调用 Skill，避免递归 fork
            // - maxTurns = FORK_MAX_TURNS (30)：限制子 Agent 的执行范围
            // - 其他依赖（apiClient, permissionManager, renderer, outputCallback）与父 Agent 共享
            AgentLoop childLoop = new AgentLoop(
                    apiClient,
                    childToolRegistry,
                    permissionManager,
                    childSystemPrompt,
                    null,           // commandRegistry = null，防止递归 fork
                    renderer,
                    outputCallback,
                    FORK_MAX_TURNS
            );

            // ---- 步骤 4：显示 Fork 开始标记 ----
            renderForkBanner(skillName, true);

            // ---- 步骤 5：运行子 Agent ----
            // 将渲染后的 Skill 内容作为第一条 user message
            // 子 Agent 将根据这段内容独立执行任务
            String result = childLoop.run(renderedPrompt);

            // ---- 步骤 6：显示 Fork 结束标记 ----
            renderForkBanner(skillName, false);

            // ---- 步骤 7：返回结果 ----
            if (result == null || result.trim().isEmpty()) {
                return ToolResult.success("[Fork skill '" + skillName + "' completed with no output]");
            }
            return ToolResult.success(result);

        } catch (Exception e) {
            // 子 Agent 执行失败不应该导致主 Agent 崩溃
            renderForkBanner(skillName, false);
            return ToolResult.error("Fork skill '" + skillName + "' failed: " + e.getMessage());
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 构建子 Agent 的系统提示词
     *
     * <p>使用 FORK_SYSTEM_PROMPT_TEMPLATE 模板，注入 Skill 名称。
     * 子 Agent 的系统提示词比主 Agent 简化很多：</p>
     * <ul>
     *   <li>不包含 Skill 列表（子 Agent 无法调用 Skill）</li>
     *   <li>明确告知子 Agent 它的角色和限制</li>
     *   <li>指导子 Agent 专注于当前任务</li>
     * </ul>
     *
     * @param skillName Skill 名称，用于注入到系统提示词中
     * @return 子 Agent 专用的系统提示词
     */
    private String buildChildSystemPrompt(String skillName) {
        return String.format(FORK_SYSTEM_PROMPT_TEMPLATE, skillName);
    }

    /**
     * 构建子 Agent 的工具注册中心
     *
     * <p>根据 Skill 声明的 allowedTools 决定工具集范围：</p>
     * <ul>
     *   <li>如果 allowedTools 非空：从父工具集中过滤出指定工具的子集</li>
     *   <li>如果 allowedTools 为空：继承父 Agent 的全部工具</li>
     * </ul>
     *
     * <p><b>注意</b>：无论哪种情况，子 Agent 的工具集都不会包含 SkillTool
     * （因为 SkillTool 在 ToolRegistry 中注册时依赖 CommandRegistry，
     * 而 createFilteredCopy 只复制在父工具集中已注册的工具）。
     * 如果父工具集中包含 SkillTool，子 Agent 也会继承它，
     * 但由于子 Agent 没有 CommandRegistry，SkillTool 会在查找 Skill 时返回错误，
     * 不会造成递归问题。</p>
     *
     * @param allowedTools Skill 声明的允许工具列表，可能为空
     * @return 子 Agent 专用的工具注册中心
     */
    private ToolRegistry buildChildToolRegistry(List<String> allowedTools) {
        return parentToolRegistry.createFilteredCopy(allowedTools);
    }

    /**
     * 在终端渲染 Fork 开始/结束的视觉标记
     *
     * <p>在子 Agent 执行前后显示明确的视觉边界，让用户清楚地知道：</p>
     * <ul>
     *   <li>什么时候进入了子 Agent 的执行</li>
     *   <li>什么时候子 Agent 的执行结束了</li>
     *   <li>正在执行的是哪个 Skill</li>
     * </ul>
     *
     * <p>输出格式：</p>
     * <pre>
     * ┌─── Fork: simplify ───────────────────────┐
     * │  （子 Agent 的输出在这里...）
     * └─── Fork: simplify ── complete ────────────┘
     * </pre>
     *
     * @param skillName Skill 名称
     * @param isStart   true=显示开始标记，false=显示结束标记
     */
    private void renderForkBanner(String skillName, boolean isStart) {
        String label = isStart
                ? "Fork: " + skillName
                : "Fork: " + skillName + " \u2500\u2500 complete";
        // 构建分隔线，总宽度 50 字符
        int dashCount = Math.max(3, 48 - label.length());
        StringBuilder dashes = new StringBuilder();
        for (int i = 0; i < dashCount; i++) {
            dashes.append('\u2500');
        }

        String banner = isStart
                ? "\n\u250C\u2500\u2500\u2500 " + label + " " + dashes + "\u2510"
                : "\u2514\u2500\u2500\u2500 " + label + " " + dashes + "\u2518\n";

        renderer.renderSystemMessage(banner);
    }
}

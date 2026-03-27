package com.claudecode.core;

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
 *   4. 从响应中提取所有 tool_use 块，逐个执行：
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

    // TODO: 定义成员变量（apiClient, toolRegistry, permissionManager, history 等）
    // TODO: 构造函数
    // TODO: 实现上述方法
}

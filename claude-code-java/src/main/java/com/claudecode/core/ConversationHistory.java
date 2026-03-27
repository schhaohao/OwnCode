package com.claudecode.core;

/**
 * ConversationHistory — 对话历史管理器
 *
 * 核心职责：
 *   维护一个 List<Message> 作为与 Claude API 交互的消息历史。
 *   消息必须严格遵循 user/assistant 交替的顺序。
 *
 * API消息格式回顾：
 *   - user 消息：可以包含 text 和 tool_result 类型的 ContentBlock
 *   - assistant 消息：可以包含 text 和 tool_use 类型的 ContentBlock
 *   - tool_result 必须放在 user 消息中，通过 tool_use_id 与对应的 tool_use 匹配
 *   - 在同一个 user 消息的 content 数组中，tool_result 块要排在 text 块之前
 *
 * 需要实现的方法：
 *
 * 1. public void addUserMessage(String text)
 *    - 添加一条纯文本的 user 消息
 *    - 创建 Message(role="user", content=[TextBlock(text)])
 *
 * 2. public void addAssistantMessage(Message message)
 *    - 直接添加从 API 响应解析出的 assistant 消息
 *    - 该消息可能包含 text 块和 tool_use 块的混合
 *
 * 3. public void addToolResults(List<ToolResultBlock> results)
 *    - 将工具执行结果包装成一条 user 消息添加到历史
 *    - 创建 Message(role="user", content=[ToolResultBlock, ToolResultBlock, ...])
 *    - 每个 ToolResultBlock 需要包含对应的 tool_use_id
 *
 * 4. public List<Message> getMessages()
 *    - 返回当前完整的消息列表（供API请求使用）
 *
 * 5. public void clear()
 *    - 清空历史（开始新会话时使用）
 *
 * 6. public int estimateTokenCount()
 *    - 粗略估算当前历史的 token 数量
 *    - 简单方案：按每4个字符≈1个token来估算
 *    - 用于上下文窗口管理，判断是否需要压缩
 *
 * 存储结构：
 *   private List<Message> messages = new ArrayList<>();
 *
 * 注意事项：
 * - 不要允许连续添加两条相同 role 的消息
 * - addToolResults 添加的是 role="user" 的消息，因为 tool_result 在协议中属于 user 侧
 */
public class ConversationHistory {

    // TODO: 实现
}

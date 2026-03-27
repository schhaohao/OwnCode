package com.claudecode.api.model;

/**
 * ApiResponse — Claude API 响应体模型
 *
 * 封装从 Claude API 返回的完整响应。
 * 在流式模式下，由 StreamAssembler 逐步组装而成。
 *
 * JSON 结构：
 *   {
 *     "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
 *     "type": "message",
 *     "role": "assistant",
 *     "model": "claude-sonnet-4-6",
 *     "content": [
 *       {"type": "text", "text": "我来帮你检查文件..."},
 *       {"type": "tool_use", "id": "toolu_01...", "name": "Read", "input": {"file_path": "pom.xml"}}
 *     ],
 *     "stop_reason": "tool_use",
 *     "usage": {
 *       "input_tokens": 2500,
 *       "output_tokens": 150
 *     }
 *   }
 *
 * 字段说明：
 * - id (String): 消息唯一ID
 * - role (String): 始终为 "assistant"
 * - model (String): 实际使用的模型
 * - content (List<ContentBlock>): 内容块列表，可混合 text 和 tool_use
 * - stopReason (String): 停止原因，这是 AgentLoop 最关心的字段！
 *   - "end_turn": LLM 认为任务完成 → AgentLoop 退出循环
 *   - "tool_use": LLM 需要调用工具 → AgentLoop 执行工具并继续
 *   - "max_tokens": 输出达到上限 → 可提示用户或自动续写
 *   - "stop_sequence": 命中停止序列
 * - usage: token 使用统计
 *   - inputTokens (int): 输入 token 数
 *   - outputTokens (int): 输出 token 数
 *
 * 需要实现：
 * - 所有字段 + getter
 * - Builder 模式（StreamAssembler 逐步构建时使用）
 * - 便捷方法：
 *   - Message toMessage(): 将响应转为 Message 对象（role="assistant", content=this.content）
 *     用于追加到 ConversationHistory
 *   - List<ContentBlock> getToolUseBlocks(): 过滤出所有 tool_use 类型的 block
 *   - String getTextContent(): 拼接所有 text block 的文本
 *   - boolean hasToolUse(): stopReason 是否为 "tool_use"
 *
 * Jackson 反序列化注意：
 * - stop_reason → stopReason: @JsonProperty("stop_reason")
 * - input_tokens → inputTokens: 嵌套对象也需要映射
 */
public class ApiResponse {

    // TODO: 实现（建议包含一个 static Builder 内部类）
}

package com.claudecode.api.model;

/**
 * ApiRequest — Claude API 请求体模型
 *
 * 封装发送给 POST https://api.anthropic.com/v1/messages 的完整请求体。
 *
 * JSON 结构：
 *   {
 *     "model": "claude-sonnet-4-6",
 *     "max_tokens": 8192,
 *     "system": "你是一个编程助手...",
 *     "stream": true,
 *     "tools": [
 *       { "name": "Read", "description": "...", "input_schema": {...} },
 *       { "name": "Bash", "description": "...", "input_schema": {...} }
 *     ],
 *     "messages": [
 *       {"role": "user", "content": "帮我读取 pom.xml"},
 *       {"role": "assistant", "content": [...]},
 *       {"role": "user", "content": [...]}
 *     ]
 *   }
 *
 * 字段说明：
 * - model (String): 使用的模型名称，如 "claude-sonnet-4-6"
 * - maxTokens (int): 最大输出 token 数，建议 8192
 *   序列化为 "max_tokens"
 * - system (String): 系统提示，告诉 LLM 它的角色和行为规范
 *   注意：这是请求体的顶层字段，不是 messages 中的一条消息
 * - stream (boolean): 是否使用流式返回，建议默认 true
 * - tools (List<ToolDefinition>): 可用工具列表
 * - messages (List<Message>): 对话历史
 *
 * 需要实现：
 * - 所有字段 + getter/setter
 * - Builder 模式（推荐，构建请求时更清晰）
 * - Jackson 序列化支持
 *   注意字段名映射：maxTokens → "max_tokens"，使用 @JsonProperty("max_tokens")
 *
 * 使用示例：
 *   ApiRequest request = ApiRequest.builder()
 *       .model("claude-sonnet-4-6")
 *       .maxTokens(8192)
 *       .system(systemPrompt)
 *       .stream(true)
 *       .tools(toolDefinitions)
 *       .messages(conversationHistory.getMessages())
 *       .build();
 */
public class ApiRequest {

    // TODO: 实现（建议使用 Builder 模式）
}

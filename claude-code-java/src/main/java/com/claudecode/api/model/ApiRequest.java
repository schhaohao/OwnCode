package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

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
 *
 * @author sunchenhao
 * @date 2026/3/28
 */
public class ApiRequest {

    @JsonProperty("model")
    private final String model;

    /** maxTokens → "max_tokens"：Java 驼峰 → API 下划线 */
    @JsonProperty("max_tokens")
    private final int maxTokens;

    @JsonProperty("system")
    private final String system;

    @JsonProperty("stream")
    private final boolean stream;

    @JsonProperty("tools")
    private final List<ToolDefinition> tools;

    @JsonProperty("messages")
    private final List<Message> messages;

    /**
     * 私有构造函数，只能通过 Builder 创建
     */
    private ApiRequest(Builder builder) {
        this.model = builder.model;
        this.maxTokens = builder.maxTokens;
        this.system = builder.system;
        this.stream = builder.stream;
        this.tools = builder.tools;
        this.messages = builder.messages;
    }

    // ==================== Getter ====================

    public String getModel() { return model; }
    public int getMaxTokens() { return maxTokens; }
    public String getSystem() { return system; }
    public boolean isStream() { return stream; }
    public List<ToolDefinition> getTools() { return tools; }
    public List<Message> getMessages() { return messages; }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 模式 — 链式调用构建 ApiRequest
     *
     * 为什么用 Builder？
     * ApiRequest 有 6 个字段，其中部分有默认值（maxTokens=8192, stream=true），
     * 如果用构造函数，参数太多且容易搞混顺序。Builder 让每个字段都有名字，清晰不易出错。
     *
     * 使用方式：
     *   ApiRequest.builder()
     *       .model("claude-sonnet-4-6")
     *       .system("You are a helpful assistant")
     *       .tools(toolDefs)
     *       .messages(history)
     *       .build();
     */
    public static class Builder {
        private String model;
        private int maxTokens = 8192;       // 默认值
        private String system;
        private boolean stream = true;       // 默认流式
        private List<ToolDefinition> tools;
        private List<Message> messages;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public ApiRequest build() {
            return new ApiRequest(this);
        }
    }
}

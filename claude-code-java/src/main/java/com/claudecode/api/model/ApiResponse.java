package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
 *
 * @author sunchenhao
 * @date 2026/3/28
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // API 可能返回额外字段（如 type），忽略即可
public class ApiResponse {

    @JsonProperty("id")
    private final String id;

    @JsonProperty("role")
    private final String role;

    @JsonProperty("model")
    private final String model;

    @JsonProperty("content")
    private final List<ContentBlock> content;

    /** stopReason → "stop_reason"：AgentLoop 循环的核心判断依据 */
    @JsonProperty("stop_reason")
    private final String stopReason;

    @JsonProperty("usage")
    private final Usage usage;

    // ==================== 构造函数 ====================

    /**
     * Jackson 反序列化构造函数（非流式 API 场景）
     */
    @JsonCreator
    public ApiResponse(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("model") String model,
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("usage") Usage usage) {
        this.id = id;
        this.role = role;
        this.model = model;
        this.content = content != null ? content : new ArrayList<>();
        this.stopReason = stopReason;
        this.usage = usage;
    }

    /**
     * Builder 构造函数（StreamAssembler 流式组装场景）
     */
    private ApiResponse(Builder builder) {
        this.id = builder.id;
        this.role = builder.role;
        this.model = builder.model;
        this.content = builder.content;
        this.stopReason = builder.stopReason;
        this.usage = builder.usage;
    }

    // ==================== 便捷方法 ====================

    /**
     * 转为 Message 对象，用于追加到 ConversationHistory
     *
     * 使用场景：AgentLoop 收到响应后
     *   history.addAssistantMessage(response.toMessage());
     */
    public Message toMessage() {
        return Message.assistantFromBlocks(content);
    }

    /**
     * 过滤出所有 tool_use 类型的 block
     *
     * 使用场景：AgentLoop 提取工具调用请求
     *   for (ContentBlock block : response.getToolUseBlocks()) {
     *       ToolUseBlock toolUse = (ToolUseBlock) block;
     *       toolRegistry.execute(toolUse.getName(), toolUse.getInput());
     *   }
     */
    public List<ContentBlock> getToolUseBlocks() {
        return content.stream()
                .filter(block -> block instanceof ToolUseBlock)
                .collect(Collectors.toList());
    }

    /**
     * 拼接所有 text block 的文本内容
     */
    public String getTextContent() {
        return content.stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    /**
     * AgentLoop 最关键的判断：是否需要执行工具
     *
     * true  → 继续循环：执行工具，把结果喂回 LLM
     * false → 退出循环：输出文本，等待用户下一轮输入
     */
    public boolean hasToolUse() {
        return "tool_use".equals(stopReason);
    }

    // ==================== Getter ====================

    public String getId() { return id; }
    public String getRole() { return role; }
    public String getModel() { return model; }
    public List<ContentBlock> getContent() { return content; }
    public String getStopReason() { return stopReason; }
    public Usage getUsage() { return usage; }

    // ==================== Usage 嵌套类 ====================

    /**
     * Token 使用统计
     *
     * JSON 格式：
     *   { "input_tokens": 2500, "output_tokens": 150 }
     *
     * 嵌套对象的字段名同样需要 @JsonProperty 映射
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        @JsonProperty("input_tokens")
        private final int inputTokens;

        @JsonProperty("output_tokens")
        private final int outputTokens;

        @JsonCreator
        public Usage(
                @JsonProperty("input_tokens") int inputTokens,
                @JsonProperty("output_tokens") int outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 模式 — StreamAssembler 在 SSE 事件流中逐步构建 ApiResponse
     *
     * 流程：
     *   Builder builder = ApiResponse.builder();
     *   // message_start 事件 → 设置 id, model
     *   builder.id("msg_xxx").model("claude-sonnet-4-6");
     *   // content_block_stop 事件 → 添加已完成的 block
     *   builder.addContentBlock(new TextBlock("hello"));
     *   builder.addContentBlock(new ToolUseBlock(...));
     *   // message_delta 事件 → 设置 stopReason 和 usage
     *   builder.stopReason("tool_use").usage(new Usage(2500, 150));
     *   // message_stop 事件 → 构建完成
     *   ApiResponse response = builder.build();
     */
    public static class Builder {
        private String id;
        private String role = "assistant";
        private String model;
        private List<ContentBlock> content = new ArrayList<>();
        private String stopReason;
        private Usage usage;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 添加一个已完成的 content block（由 StreamAssembler 在 content_block_stop 时调用）
         */
        public Builder addContentBlock(ContentBlock block) {
            this.content.add(block);
            return this;
        }

        public Builder stopReason(String stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public ApiResponse build() {
            return new ApiResponse(this);
        }
    }
}

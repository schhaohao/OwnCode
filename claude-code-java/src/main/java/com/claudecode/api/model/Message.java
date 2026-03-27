package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Message — 对话消息模型
 *
 * 对应 Claude API 中 messages 数组里的每一条消息。
 *
 * JSON 结构：
 *   {
 *     "role": "user" | "assistant",
 *     "content": "纯文本" 或 [ContentBlock, ContentBlock, ...]
 *   }
 *
 * 字段说明：
 * - role: 消息角色
 *   - "user": 用户消息（也用于传递 tool_result）
 *   - "assistant": LLM 的回复
 *
 * - content: 消息内容，有两种形态：
 *   a. 纯字符串: "Hello, how can I help?"
 *   b. ContentBlock 数组: [TextBlock, ToolUseBlock, ...]
 *   建议统一使用 List<ContentBlock> 形式，纯文本包装为单元素列表
 *
 * 需要实现：
 *
 * 1. 字段：
 *    - String role
 *    - List<ContentBlock> content
 *
 * 2. 静态工厂方法（推荐，比构造函数更清晰）：
 *    - static Message userText(String text)
 *      → 创建纯文本的 user 消息
 *
 *    - static Message assistantFromBlocks(List<ContentBlock> blocks)
 *      → 从 API 响应的 content blocks 创建 assistant 消息
 *
 *    - static Message userWithToolResults(List<ContentBlock> toolResults)
 *      → 创建包含 tool_result 块的 user 消息
 *
 * 3. 便捷查询方法：
 *    - List<ContentBlock> getToolUseBlocks()
 *      → 过滤出所有 type="tool_use" 的 block
 *
 *    - String getTextContent()
 *      → 拼接所有 type="text" 的 block 的文本内容
 *
 * 4. Jackson 序列化支持：
 *    - 确保能正确序列化为 API 要求的 JSON 格式
 *    - 使用 @JsonProperty 等注解，或者手动构建 JsonNode
 *
 * 注意：
 * - 序列化时，如果 content 只有一个 text block，可以简化为纯字符串形式
 * - 反序列化时，需要处理 content 既可能是 string 也可能是 array 的情况
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class Message {

    @JsonProperty("role")
    private final String role;

    /**
     * 消息内容列表。
     *
     * 序列化时：统一输出为 ContentBlock 数组（API 接受数组和纯字符串两种格式）
     * 反序列化时：通过 ContentListDeserializer 自动处理两种输入格式：
     *   - 纯字符串 "hello" → 包装为 [TextBlock("hello")]
     *   - 数组 [{...}, {...}] → 正常解析为 List<ContentBlock>
     */
    @JsonProperty("content")
    @JsonDeserialize(using = ContentListDeserializer.class)
    private final List<ContentBlock> content;

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数，强制通过静态工厂方法创建
     */
    private Message(String role, List<ContentBlock> content) {
        this.role = role;
        this.content = Collections.unmodifiableList(content);  // 不可变，防止外部篡改
    }

    /**
     * Jackson 反序列化用的构造函数
     * @JsonCreator 告诉 Jackson 用这个方法来反序列化
     */
    @JsonCreator
    private static Message fromJson(
            @JsonProperty("role") String role,
            @JsonProperty("content") @JsonDeserialize(using = ContentListDeserializer.class) List<ContentBlock> content) {
        return new Message(role, content != null ? content : Collections.emptyList());
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建纯文本的 user 消息
     *
     * 使用场景：用户在终端输入了一段文字
     * 示例：Message.userText("帮我读取 pom.xml")
     * 序列化后：{"role":"user","content":[{"type":"text","text":"帮我读取 pom.xml"}]}
     */
    public static Message userText(String text) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(text));
        return new Message("user", blocks);
    }

    /**
     * 从 API 响应的 content blocks 创建 assistant 消息
     *
     * 使用场景：StreamAssembler 组装完响应后，创建 assistant 消息追加到历史
     * blocks 可能混合 TextBlock 和 ToolUseBlock
     */
    public static Message assistantFromBlocks(List<ContentBlock> blocks) {
        return new Message("assistant", new ArrayList<>(blocks));
    }

    /**
     * 创建包含 tool_result 块的 user 消息
     *
     * 使用场景：AgentLoop 执行完工具后，将结果打包为 user 消息发回给 LLM
     * toolResults 中的每个元素都应该是 ToolResultBlock
     *
     * 注意：在 API 协议中，tool_result 属于 user 侧消息，不是 assistant 侧
     */
    public static Message userWithToolResults(List<ContentBlock> toolResults) {
        return new Message("user", new ArrayList<>(toolResults));
    }

    // ==================== 便捷查询方法 ====================

    /**
     * 过滤出所有 tool_use 类型的 block
     *
     * 使用场景：AgentLoop 收到 assistant 响应后，提取所有工具调用请求
     * 示例：
     *   List<ContentBlock> toolUses = message.getToolUseBlocks();
     *   for (ContentBlock block : toolUses) {
     *       ToolUseBlock toolUse = (ToolUseBlock) block;
     *       // 执行工具...
     *   }
     */
    public List<ContentBlock> getToolUseBlocks() {
        return content.stream()
                .filter(block -> block instanceof ToolUseBlock)
                .collect(Collectors.toList());
    }

    /**
     * 拼接所有 text block 的文本内容
     *
     * 使用场景：获取 LLM 回复中的纯文本部分（忽略工具调用）
     * 如果有多个 TextBlock，用空字符串连接
     */
    public String getTextContent() {
        return content.stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    // ==================== Getter ====================

    public String getRole() {
        return role;
    }

    public List<ContentBlock> getContent() {
        return content;
    }

    // ==================== 自定义反序列化器 ====================

    /**
     * 处理 content 字段的两种 JSON 输入格式：
     *
     * 格式1 — 纯字符串（API 有时返回简化格式）：
     *   {"role":"user","content":"hello"}
     *   → 解析为 [TextBlock("hello")]
     *
     * 格式2 — ContentBlock 数组（标准格式）：
     *   {"role":"assistant","content":[{"type":"text","text":"..."},{"type":"tool_use",...}]}
     *   → 正常解析为 List<ContentBlock>
     *
     * 为什么需要这个？
     * Claude API 的响应中 content 始终是数组，但在某些场景（如历史消息恢复）
     * content 可能是纯字符串。这个反序列化器确保两种格式都能正确处理。
     */
    static class ContentListDeserializer extends JsonDeserializer<List<ContentBlock>> {
        @Override
        public List<ContentBlock> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            // 判断当前 JSON token 是字符串还是数组起始符
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                // 格式1：纯字符串 → 包装为单个 TextBlock
                String text = p.getValueAsString();
                List<ContentBlock> list = new ArrayList<>();
                list.add(new TextBlock(text));
                return list;
            } else if (p.currentToken() == JsonToken.START_ARRAY) {
                // 格式2：数组 → 逐个解析 ContentBlock（多态由 @JsonTypeInfo 自动处理）
                List<ContentBlock> list = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    ContentBlock block = ctxt.readValue(p, ContentBlock.class);
                    list.add(block);
                }
                return list;
            }
            // 其他情况（如 null）
            return Collections.emptyList();
        }
    }
}

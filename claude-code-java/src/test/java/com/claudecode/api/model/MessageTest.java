package com.claudecode.api.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Message 单元测试
 *
 * 验证点：
 * 1. 三个静态工厂方法创建的消息 role 和 content 正确
 * 2. 便捷查询方法 getToolUseBlocks / getTextContent
 * 3. Jackson 序列化：Java → JSON 格式正确
 * 4. Jackson 反序列化：content 为纯字符串和数组两种格式都能处理
 */
class MessageTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // ==================== 工厂方法测试 ====================

    @Test
    void userText_shouldCreateUserMessageWithTextBlock() {
        Message msg = Message.userText("hello");

        assertEquals("user", msg.getRole());
        assertEquals(1, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertEquals("hello", ((TextBlock) msg.getContent().get(0)).getText());
    }

    @Test
    void assistantFromBlocks_shouldCreateAssistantMessage() {
        List<ContentBlock> blocks = Arrays.asList(
                new TextBlock("let me check"),
                new ToolUseBlock("toolu_1", "Read", Map.of("file_path", "/tmp/a.txt"))
        );

        Message msg = Message.assistantFromBlocks(blocks);

        assertEquals("assistant", msg.getRole());
        assertEquals(2, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertInstanceOf(ToolUseBlock.class, msg.getContent().get(1));
    }

    @Test
    void userWithToolResults_shouldCreateUserMessage() {
        List<ContentBlock> results = Arrays.asList(
                new ToolResultBlock("toolu_1", "file content", false),
                new ToolResultBlock("toolu_2", "error", true)
        );

        Message msg = Message.userWithToolResults(results);

        assertEquals("user", msg.getRole());
        assertEquals(2, msg.getContent().size());
        assertInstanceOf(ToolResultBlock.class, msg.getContent().get(0));
    }

    // ==================== 便捷查询方法测试 ====================

    @Test
    void getToolUseBlocks_shouldFilterToolUseOnly() {
        List<ContentBlock> blocks = Arrays.asList(
                new TextBlock("thinking..."),
                new ToolUseBlock("toolu_1", "Read", Map.of()),
                new TextBlock("and also..."),
                new ToolUseBlock("toolu_2", "Bash", Map.of())
        );
        Message msg = Message.assistantFromBlocks(blocks);

        List<ContentBlock> toolUses = msg.getToolUseBlocks();

        assertEquals(2, toolUses.size());
        assertEquals("toolu_1", ((ToolUseBlock) toolUses.get(0)).getId());
        assertEquals("toolu_2", ((ToolUseBlock) toolUses.get(1)).getId());
    }

    @Test
    void getToolUseBlocks_noToolUse_shouldReturnEmpty() {
        Message msg = Message.userText("just text");

        assertTrue(msg.getToolUseBlocks().isEmpty());
    }

    @Test
    void getTextContent_shouldConcatenateAllTextBlocks() {
        List<ContentBlock> blocks = Arrays.asList(
                new TextBlock("hello "),
                new ToolUseBlock("toolu_1", "Read", Map.of()),
                new TextBlock("world")
        );
        Message msg = Message.assistantFromBlocks(blocks);

        assertEquals("hello world", msg.getTextContent());
    }

    @Test
    void getTextContent_noTextBlock_shouldReturnEmpty() {
        List<ContentBlock> blocks = Arrays.asList(
                new ToolResultBlock("toolu_1", "result", false)
        );
        Message msg = Message.userWithToolResults(blocks);

        assertEquals("", msg.getTextContent());
    }

    // ==================== Jackson 序列化测试 ====================

    @Test
    void serialize_userTextMessage() throws JsonProcessingException {
        Message msg = Message.userText("hello");
        String json = mapper.writeValueAsString(msg);

        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"text\":\"hello\""));
    }

    @Test
    void serialize_assistantWithToolUse() throws JsonProcessingException {
        List<ContentBlock> blocks = Arrays.asList(
                new TextBlock("checking"),
                new ToolUseBlock("toolu_abc", "Bash", Map.of("command", "ls"))
        );
        Message msg = Message.assistantFromBlocks(blocks);
        String json = mapper.writeValueAsString(msg);

        assertTrue(json.contains("\"role\":\"assistant\""));
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"type\":\"tool_use\""));
        assertTrue(json.contains("\"id\":\"toolu_abc\""));
    }

    @Test
    void serialize_userWithToolResult() throws JsonProcessingException {
        List<ContentBlock> blocks = Arrays.asList(
                new ToolResultBlock("toolu_abc", "output", false)
        );
        Message msg = Message.userWithToolResults(blocks);
        String json = mapper.writeValueAsString(msg);

        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"type\":\"tool_result\""));
        assertTrue(json.contains("\"tool_use_id\":\"toolu_abc\""));
    }

    // ==================== Jackson 反序列化测试 ====================

    @Test
    void deserialize_contentAsString() throws JsonProcessingException {
        // API 有时返回 content 为纯字符串的格式
        String json = "{\"role\":\"user\",\"content\":\"hello world\"}";

        Message msg = mapper.readValue(json, Message.class);

        assertEquals("user", msg.getRole());
        assertEquals(1, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertEquals("hello world", ((TextBlock) msg.getContent().get(0)).getText());
    }

    @Test
    void deserialize_contentAsArray() throws JsonProcessingException {
        String json = "{\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"hi\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{\"file_path\":\"/a\"}}]}";

        Message msg = mapper.readValue(json, Message.class);

        assertEquals("assistant", msg.getRole());
        assertEquals(2, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
        assertInstanceOf(ToolUseBlock.class, msg.getContent().get(1));
        assertEquals("Read", ((ToolUseBlock) msg.getContent().get(1)).getName());
    }

    // ==================== 不可变性测试 ====================

    @Test
    void content_shouldBeUnmodifiable() {
        Message msg = Message.userText("test");

        // 尝试修改 content 列表应该抛异常
        assertThrows(UnsupportedOperationException.class,
                () -> msg.getContent().add(new TextBlock("hack")));
    }
}

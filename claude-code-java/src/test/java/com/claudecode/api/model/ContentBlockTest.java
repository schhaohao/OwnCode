package com.claudecode.api.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContentBlock 继承体系的单元测试
 *
 * 验证点：
 * 1. 每个子类的 type 字段正确
 * 2. Jackson 序列化：Java 对象 → JSON（字段名映射是否正确）
 * 3. Jackson 反序列化：JSON → 正确的子类（多态路由是否工作）
 * 4. ToolResultBlock.from() 便捷工厂方法
 */
class ContentBlockTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // ==================== TextBlock 测试 ====================

    @Test
    void textBlock_typeShouldBeText() {
        TextBlock block = new TextBlock("hello");

        assertEquals("text", block.getType());
        assertEquals("hello", block.getText());
    }

    @Test
    void textBlock_serialize() throws JsonProcessingException {
        TextBlock block = new TextBlock("hello");
        String json = mapper.writeValueAsString(block);

        // 应该包含 "type":"text" 和 "text":"hello"
        assertTrue(json.contains("\"type\":\"text\""));
        assertTrue(json.contains("\"text\":\"hello\""));
    }

    @Test
    void textBlock_deserialize() throws JsonProcessingException {
        String json = "{\"type\":\"text\",\"text\":\"hello world\"}";

        // 反序列化为 ContentBlock，应该自动路由到 TextBlock
        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(TextBlock.class, block);
        assertEquals("hello world", ((TextBlock) block).getText());
    }

    // ==================== ToolUseBlock 测试 ====================

    @Test
    void toolUseBlock_typeShouldBeToolUse() {
        ToolUseBlock block = new ToolUseBlock("toolu_123", "Read", Map.of("file_path", "/tmp/a.txt"));

        assertEquals("tool_use", block.getType());
        assertEquals("toolu_123", block.getId());
        assertEquals("Read", block.getName());
        assertEquals("/tmp/a.txt", block.getInput().get("file_path"));
    }

    @Test
    void toolUseBlock_serialize() throws JsonProcessingException {
        ToolUseBlock block = new ToolUseBlock("toolu_abc", "Bash", Map.of("command", "ls"));
        String json = mapper.writeValueAsString(block);

        assertTrue(json.contains("\"type\":\"tool_use\""));
        assertTrue(json.contains("\"id\":\"toolu_abc\""));
        assertTrue(json.contains("\"name\":\"Bash\""));
        assertTrue(json.contains("\"command\":\"ls\""));
    }

    @Test
    void toolUseBlock_deserialize() throws JsonProcessingException {
        String json = "{\"type\":\"tool_use\",\"id\":\"toolu_xyz\",\"name\":\"Edit\","
                + "\"input\":{\"file_path\":\"/tmp/b.txt\",\"old_string\":\"foo\",\"new_string\":\"bar\"}}";

        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(ToolUseBlock.class, block);
        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("toolu_xyz", toolUse.getId());
        assertEquals("Edit", toolUse.getName());
        assertEquals("foo", toolUse.getInput().get("old_string"));
        assertEquals("bar", toolUse.getInput().get("new_string"));
    }

    // ==================== ToolResultBlock 测试 ====================

    @Test
    void toolResultBlock_typeShouldBeToolResult() {
        ToolResultBlock block = new ToolResultBlock("toolu_123", "file content here", false);

        assertEquals("tool_result", block.getType());
        assertEquals("toolu_123", block.getToolUseId());
        assertEquals("file content here", block.getContent());
        assertFalse(block.isError());
    }

    @Test
    void toolResultBlock_serialize_fieldNameMapping() throws JsonProcessingException {
        ToolResultBlock block = new ToolResultBlock("toolu_456", "error msg", true);
        String json = mapper.writeValueAsString(block);

        // 验证 Java 驼峰字段名正确映射为 JSON 下划线字段名
        assertTrue(json.contains("\"tool_use_id\":\"toolu_456\""));
        assertTrue(json.contains("\"is_error\":true"));
        assertTrue(json.contains("\"type\":\"tool_result\""));
    }

    @Test
    void toolResultBlock_deserialize() throws JsonProcessingException {
        String json = "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_789\","
                + "\"content\":\"command output\",\"is_error\":false}";

        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(ToolResultBlock.class, block);
        ToolResultBlock result = (ToolResultBlock) block;
        assertEquals("toolu_789", result.getToolUseId());
        assertEquals("command output", result.getContent());
        assertFalse(result.isError());
    }

    // ==================== ToolResultBlock.from() 测试 ====================

    @Test
    void toolResultBlock_from_successResult() {
        com.claudecode.tool.ToolResult toolResult = com.claudecode.tool.ToolResult.success("output");
        ToolResultBlock block = ToolResultBlock.from("toolu_abc", toolResult);

        assertEquals("toolu_abc", block.getToolUseId());
        assertEquals("output", block.getContent());
        assertFalse(block.isError());
    }

    @Test
    void toolResultBlock_from_errorResult() {
        com.claudecode.tool.ToolResult toolResult = com.claudecode.tool.ToolResult.error("fail");
        ToolResultBlock block = ToolResultBlock.from("toolu_def", toolResult);

        assertEquals("toolu_def", block.getToolUseId());
        assertEquals("fail", block.getContent());
        assertTrue(block.isError());
    }

    // ==================== 多态反序列化综合测试 ====================

    @Test
    void polymorphicDeserialization_unknownType_shouldThrow() {
        String json = "{\"type\":\"unknown\",\"data\":123}";

        // 未知 type 应该抛异常，而不是静默返回 null
        assertThrows(Exception.class, () -> mapper.readValue(json, ContentBlock.class));
    }
}

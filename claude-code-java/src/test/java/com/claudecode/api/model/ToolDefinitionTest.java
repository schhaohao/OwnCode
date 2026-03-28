package com.claudecode.api.model;

import com.claudecode.tool.impl.BashTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolDefinition 单元测试
 *
 * 验证点：
 * 1. 构造函数 & getter
 * 2. fromTool() 静态工厂方法
 * 3. Jackson 序列化：inputSchema → "input_schema"
 */
class ToolDefinitionTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void constructor_shouldSetAllFields() {
        Map<String, Object> schema = Map.of("type", "object");
        ToolDefinition def = new ToolDefinition("Read", "Reads a file", schema);

        assertEquals("Read", def.getName());
        assertEquals("Reads a file", def.getDescription());
        assertEquals("object", def.getInputSchema().get("type"));
    }

    @Test
    void fromTool_shouldExtractFieldsFromToolInterface() {
        BashTool bash = new BashTool(System.getProperty("java.io.tmpdir"));
        ToolDefinition def = ToolDefinition.fromTool(bash);

        assertEquals("Bash", def.getName());
        assertFalse(def.getDescription().isEmpty());
        assertEquals("object", def.getInputSchema().get("type"));

        // schema 的 properties 中应该有 command
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) def.getInputSchema().get("properties");
        assertTrue(props.containsKey("command"));
    }

    @Test
    void serialize_inputSchemaShouldMapToInputSchema() throws JsonProcessingException {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("file_path", Map.of("type", "string")));
        schema.put("required", List.of("file_path"));

        ToolDefinition def = new ToolDefinition("Read", "Reads a file", schema);
        String json = mapper.writeValueAsString(def);

        // 关键验证：Java 的 inputSchema 序列化为 JSON 的 "input_schema"
        assertTrue(json.contains("\"input_schema\""));
        // 不应出现驼峰形式
        assertFalse(json.contains("\"inputSchema\""));
        assertTrue(json.contains("\"name\":\"Read\""));
        assertTrue(json.contains("\"description\":\"Reads a file\""));
    }
}

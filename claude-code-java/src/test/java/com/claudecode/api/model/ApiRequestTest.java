package com.claudecode.api.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiRequest 单元测试
 *
 * 验证点：
 * 1. Builder 默认值（maxTokens=8192, stream=true）
 * 2. Builder 链式调用
 * 3. Jackson 序列化字段名映射（maxTokens → "max_tokens"）
 */
class ApiRequestTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void builder_defaultValues() {
        ApiRequest request = ApiRequest.builder()
                .model("claude-sonnet-4-6")
                .messages(Collections.emptyList())
                .build();

        assertEquals("claude-sonnet-4-6", request.getModel());
        assertEquals(8192, request.getMaxTokens());
        assertTrue(request.isStream());
    }

    @Test
    void builder_allFields() {
        List<Message> messages = Arrays.asList(Message.userText("hello"));
        List<ToolDefinition> tools = Arrays.asList(
                new ToolDefinition("Bash", "run commands", Map.of("type", "object"))
        );

        ApiRequest request = ApiRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(4096)
                .system("You are a helper")
                .stream(false)
                .tools(tools)
                .messages(messages)
                .build();

        assertEquals(4096, request.getMaxTokens());
        assertFalse(request.isStream());
        assertEquals("You are a helper", request.getSystem());
        assertEquals(1, request.getTools().size());
        assertEquals(1, request.getMessages().size());
    }

    @Test
    void serialize_fieldNameMapping() throws JsonProcessingException {
        ApiRequest request = ApiRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(8192)
                .system("test")
                .stream(true)
                .tools(Collections.emptyList())
                .messages(Arrays.asList(Message.userText("hi")))
                .build();

        String json = mapper.writeValueAsString(request);

        // 关键验证：驼峰 → 下划线
        assertTrue(json.contains("\"max_tokens\":8192"));
        assertFalse(json.contains("\"maxTokens\""));

        assertTrue(json.contains("\"model\":\"claude-sonnet-4-6\""));
        assertTrue(json.contains("\"stream\":true"));
        assertTrue(json.contains("\"system\":\"test\""));
    }
}

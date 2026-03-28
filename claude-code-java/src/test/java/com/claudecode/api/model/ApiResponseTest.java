package com.claudecode.api.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiResponse 单元测试
 *
 * 验证点：
 * 1. Jackson 反序列化（非流式场景）
 * 2. Builder 模式（流式场景，模拟 StreamAssembler）
 * 3. 便捷方法：hasToolUse / getToolUseBlocks / getTextContent / toMessage
 * 4. Usage 嵌套对象的字段名映射
 */
class ApiResponseTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    // ==================== Jackson 反序列化测试 ====================

    @Test
    void deserialize_endTurnResponse() throws JsonProcessingException {
        String json = "{\"id\":\"msg_01\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50}}";

        ApiResponse resp = mapper.readValue(json, ApiResponse.class);

        assertEquals("msg_01", resp.getId());
        assertEquals("assistant", resp.getRole());
        assertEquals("end_turn", resp.getStopReason());
        assertFalse(resp.hasToolUse());
        assertEquals(100, resp.getUsage().getInputTokens());
        assertEquals(50, resp.getUsage().getOutputTokens());
        assertEquals("hello", resp.getTextContent());
    }

    @Test
    void deserialize_toolUseResponse() throws JsonProcessingException {
        String json = "{\"id\":\"msg_02\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\","
                + "\"content\":["
                + "{\"type\":\"text\",\"text\":\"let me check\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\",\"input\":{\"file_path\":\"/a.txt\"}}"
                + "],\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":80}}";

        ApiResponse resp = mapper.readValue(json, ApiResponse.class);

        assertTrue(resp.hasToolUse());
        assertEquals(2, resp.getContent().size());
        assertEquals(1, resp.getToolUseBlocks().size());
        assertEquals("let me check", resp.getTextContent());

        ToolUseBlock toolUse = (ToolUseBlock) resp.getToolUseBlocks().get(0);
        assertEquals("Read", toolUse.getName());
        assertEquals("/a.txt", toolUse.getInput().get("file_path"));
    }

    @Test
    void deserialize_ignoresUnknownFields() throws JsonProcessingException {
        // API 返回的 "type":"message" 字段我们不需要，应该被 @JsonIgnoreProperties 忽略
        String json = "{\"id\":\"msg_03\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-sonnet-4-6\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";

        ApiResponse resp = mapper.readValue(json, ApiResponse.class);
        assertEquals("msg_03", resp.getId());  // 不抛异常即通过
    }

    // ==================== Builder 测试（模拟 StreamAssembler） ====================

    @Test
    void builder_simulateStreamAssembly() {
        // 模拟 StreamAssembler 逐步构建响应的过程
        ApiResponse resp = ApiResponse.builder()
                .id("msg_stream")
                .model("claude-sonnet-4-6")
                .addContentBlock(new TextBlock("thinking..."))
                .addContentBlock(new ToolUseBlock("toolu_1", "Bash", Map.of("command", "ls")))
                .stopReason("tool_use")
                .usage(new ApiResponse.Usage(500, 100))
                .build();

        assertEquals("msg_stream", resp.getId());
        assertEquals("assistant", resp.getRole());  // Builder 默认值
        assertTrue(resp.hasToolUse());
        assertEquals(2, resp.getContent().size());
        assertEquals("thinking...", resp.getTextContent());
        assertEquals(500, resp.getUsage().getInputTokens());
    }

    // ==================== 便捷方法测试 ====================

    @Test
    void toMessage_shouldCreateAssistantMessage() {
        ApiResponse resp = ApiResponse.builder()
                .addContentBlock(new TextBlock("hi"))
                .addContentBlock(new ToolUseBlock("t1", "Read", Map.of()))
                .stopReason("tool_use")
                .build();

        Message msg = resp.toMessage();

        assertEquals("assistant", msg.getRole());
        assertEquals(2, msg.getContent().size());
    }

    @Test
    void hasToolUse_variousStopReasons() {
        assertEquals(true, buildWithStopReason("tool_use").hasToolUse());
        assertEquals(false, buildWithStopReason("end_turn").hasToolUse());
        assertEquals(false, buildWithStopReason("max_tokens").hasToolUse());
        assertEquals(false, buildWithStopReason(null).hasToolUse());
    }

    @Test
    void getToolUseBlocks_noTools_shouldReturnEmpty() {
        ApiResponse resp = ApiResponse.builder()
                .addContentBlock(new TextBlock("just text"))
                .stopReason("end_turn")
                .build();

        assertTrue(resp.getToolUseBlocks().isEmpty());
    }

    private ApiResponse buildWithStopReason(String stopReason) {
        return ApiResponse.builder().stopReason(stopReason).build();
    }
}

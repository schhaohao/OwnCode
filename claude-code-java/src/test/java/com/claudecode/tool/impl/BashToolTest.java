package com.claudecode.tool.impl;

import com.claudecode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashTool 单元测试
 *
 * 验证点：
 * 1. 工具元信息（name, description, schema, permission）
 * 2. 正常命令执行 & 输出捕获
 * 3. 命令失败（非零退出码）处理
 * 4. 缺少参数时的错误处理
 * 5. 超时处理
 */
class BashToolTest {

    private BashTool bashTool;

    @BeforeEach
    void setUp() {
        // 以系统临时目录作为工作目录，保证测试环境隔离
        bashTool = new BashTool(System.getProperty("java.io.tmpdir"));
    }

    // ==================== 元信息测试 ====================

    @Test
    void name_shouldReturnBash() {
        assertEquals("Bash", bashTool.name());
    }

    @Test
    void description_shouldNotBeEmpty() {
        assertNotNull(bashTool.description());
        assertFalse(bashTool.description().isBlank());
    }

    @Test
    void inputSchema_shouldContainCommandProperty() {
        Map<String, Object> schema = bashTool.inputSchema();

        assertEquals("object", schema.get("type"));

        // properties 中应该包含 "command"
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("command"));

        // required 应该包含 "command"
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("command"));
    }

    @Test
    void requiresPermission_shouldReturnTrue() {
        // Bash 能执行任意命令，必须需要权限
        assertTrue(bashTool.requiresPermission());
    }

    // ==================== execute 测试 ====================

    @Test
    void execute_echoCommand_shouldReturnOutput() {
        ToolResult result = bashTool.execute(Map.of("command", "echo hello"));

        assertFalse(result.isError());
        assertEquals("hello", result.getContent().trim());
    }

    @Test
    void execute_multiLineOutput() {
        ToolResult result = bashTool.execute(Map.of("command", "echo 'line1'; echo 'line2'"));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("line1"));
        assertTrue(result.getContent().contains("line2"));
    }

    @Test
    void execute_nonZeroExitCode_shouldStillReturnSuccess() {
        // 非零退出码不标记为 error，而是附上退出码让 LLM 判断
        ToolResult result = bashTool.execute(Map.of("command", "exit 42"));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("Exit code: 42"));
    }

    @Test
    void execute_missingCommand_shouldReturnError() {
        ToolResult result = bashTool.execute(Map.of());

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("command"));
    }

    @Test
    void execute_blankCommand_shouldReturnError() {
        ToolResult result = bashTool.execute(Map.of("command", "   "));

        assertTrue(result.isError());
    }

    @Test
    void execute_withTimeout_shouldRespectIt() {
        // 给一个极短的超时（100ms），执行 sleep 命令应该超时
        ToolResult result = bashTool.execute(Map.of(
                "command", "sleep 10",
                "timeout", 100
        ));

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("timed out"));
    }

    @Test
    void execute_workingDirectory_shouldBeEffective() throws IOException {
        // 在临时目录创建一个标记文件，验证命令在正确的工作目录执行
        Path tempDir = Files.createTempDirectory("bash-tool-test");
        Path marker = Files.createFile(tempDir.resolve("marker.txt"));

        BashTool tool = new BashTool(tempDir.toString());
        ToolResult result = tool.execute(Map.of("command", "ls marker.txt"));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("marker.txt"));

        // 清理
        Files.deleteIfExists(marker);
        Files.deleteIfExists(tempDir);
    }
}

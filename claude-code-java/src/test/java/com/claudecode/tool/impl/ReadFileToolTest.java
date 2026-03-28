package com.claudecode.tool.impl;

import com.claudecode.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadFileTool 单元测试
 *
 * 验证点：
 * 1. 工具元信息（name, description, schema, permission）
 * 2. 正常文件读取 & cat -n 格式输出
 * 3. offset / limit 参数
 * 4. 边界情况：文件不存在、目录、空文件、offset 超出范围
 */
class ReadFileToolTest {

    private ReadFileTool readTool;

    /** JUnit 5 的 @TempDir：每个测试方法自动创建临时目录，测试后自动清理 */
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        readTool = new ReadFileTool();
    }

    // ==================== 元信息测试 ====================

    @Test
    void name_shouldReturnRead() {
        assertEquals("Read", readTool.name());
    }

    @Test
    void requiresPermission_shouldReturnFalse() {
        // 只读工具，不需要权限
        assertFalse(readTool.requiresPermission());
    }

    @Test
    void inputSchema_shouldContainFilePathRequired() {
        Map<String, Object> schema = readTool.inputSchema();

        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("file_path"));
        assertTrue(props.containsKey("offset"));
        assertTrue(props.containsKey("limit"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("file_path"));
    }

    // ==================== 正常读取测试 ====================

    @Test
    void execute_normalFile_shouldReturnContentWithLineNumbers() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, Arrays.asList("aaa", "bbb", "ccc"));

        ToolResult result = readTool.execute(Map.of("file_path", file.toString()));

        assertFalse(result.isError());
        // 验证 cat -n 格式：行号 + tab + 内容
        assertTrue(result.getContent().contains("1\taaa"));
        assertTrue(result.getContent().contains("2\tbbb"));
        assertTrue(result.getContent().contains("3\tccc"));
    }

    @Test
    void execute_lineNumberFormat_shouldBeRightAligned() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, Arrays.asList("line1"));

        ToolResult result = readTool.execute(Map.of("file_path", file.toString()));

        // 行号应该右对齐，6位宽度："     1\tline1"
        assertTrue(result.getContent().startsWith("     1\t"));
    }

    // ==================== offset / limit 测试 ====================

    @Test
    void execute_withOffset_shouldStartFromSpecifiedLine() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, Arrays.asList("line1", "line2", "line3", "line4", "line5"));

        ToolResult result = readTool.execute(Map.of(
                "file_path", file.toString(),
                "offset", 3
        ));

        assertFalse(result.isError());
        // 应该从第3行开始
        assertFalse(result.getContent().contains("line1"));
        assertFalse(result.getContent().contains("line2"));
        assertTrue(result.getContent().contains("3\tline3"));
        assertTrue(result.getContent().contains("4\tline4"));
        assertTrue(result.getContent().contains("5\tline5"));
    }

    @Test
    void execute_withLimit_shouldLimitLineCount() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, Arrays.asList("line1", "line2", "line3", "line4", "line5"));

        ToolResult result = readTool.execute(Map.of(
                "file_path", file.toString(),
                "limit", 2
        ));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("1\tline1"));
        assertTrue(result.getContent().contains("2\tline2"));
        assertFalse(result.getContent().contains("line3"));
    }

    @Test
    void execute_withOffsetAndLimit() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, Arrays.asList("a", "b", "c", "d", "e"));

        ToolResult result = readTool.execute(Map.of(
                "file_path", file.toString(),
                "offset", 2,
                "limit", 2
        ));

        assertFalse(result.isError());
        assertFalse(result.getContent().contains("a"));
        assertTrue(result.getContent().contains("2\tb"));
        assertTrue(result.getContent().contains("3\tc"));
        assertFalse(result.getContent().contains("d"));
    }

    // ==================== 边界情况测试 ====================

    @Test
    void execute_fileNotFound_shouldReturnError() {
        ToolResult result = readTool.execute(Map.of("file_path", "/nonexistent/file.txt"));

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("File not found"));
    }

    @Test
    void execute_directoryPath_shouldReturnError() {
        ToolResult result = readTool.execute(Map.of("file_path", tempDir.toString()));

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("directory"));
    }

    @Test
    void execute_emptyFile_shouldReturnEmptyMessage() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.write(file, Arrays.asList());

        ToolResult result = readTool.execute(Map.of("file_path", file.toString()));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("empty"));
    }

    @Test
    void execute_offsetBeyondFileLength_shouldReturnMessage() throws IOException {
        Path file = tempDir.resolve("short.txt");
        Files.write(file, Arrays.asList("only one line"));

        ToolResult result = readTool.execute(Map.of(
                "file_path", file.toString(),
                "offset", 100
        ));

        assertFalse(result.isError());
        assertTrue(result.getContent().contains("beyond"));
    }

    @Test
    void execute_missingFilePath_shouldReturnError() {
        ToolResult result = readTool.execute(Map.of());

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("file_path"));
    }
}

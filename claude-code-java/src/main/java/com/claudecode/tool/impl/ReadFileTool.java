package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReadFileTool — 文件读取工具
 *
 * 功能：读取指定路径文件的内容，支持行号显示、偏移和行数限制。
 * 这是最常用的工具之一，只读操作，不需要权限审批。
 *
 * 工具定义：
 * - name: "Read"
 * - requiresPermission: false
 * - inputSchema:
 *   {
 *     "type": "object",
 *     "properties": {
 *       "file_path": {
 *         "type": "string",
 *         "description": "要读取的文件的绝对路径"
 *       },
 *       "offset": {
 *         "type": "integer",
 *         "description": "起始行号（从1开始），默认为1"
 *       },
 *       "limit": {
 *         "type": "integer",
 *         "description": "最多读取的行数，默认2000"
 *       }
 *     },
 *     "required": ["file_path"]
 *   }
 *
 * execute() 实现思路：
 *
 * 1. 从 input 中获取参数：
 *    - file_path（必填）
 *    - offset（可选，默认 1）
 *    - limit（可选，默认 2000）
 *
 * 2. 校验文件路径：
 *    - 检查文件是否存在：Files.exists(path)
 *    - 检查是否是文件（不是目录）：Files.isRegularFile(path)
 *    - 不存在则返回 ToolResult.error("File not found: ...")
 *    - 是目录则返回 ToolResult.error("Path is a directory, not a file: ...")
 *
 * 3. 读取文件内容：
 *    - 使用 Files.readAllLines(path) 或 BufferedReader 逐行读取
 *    - 从 offset 行开始（注意：offset 是1-based，数组是0-based）
 *    - 最多读取 limit 行
 *
 * 4. 格式化输出（模拟 cat -n 格式）：
 *    - 每行前面加行号：String.format("%6d\t%s", lineNumber, lineContent)
 *    - 行号右对齐，6位宽度，tab分隔
 *    - 示例：
 *          1	package com.claudecode;
 *          2
 *          3	public class Main {
 *          4	    public static void main(String[] args) {
 *
 * 5. 返回 ToolResult.success(formattedContent)
 *
 * 边界情况处理：
 * - 空文件：返回提示 "File is empty"
 * - 二进制文件：检测到非文本内容时返回提示
 * - 编码问题：默认 UTF-8，异常时尝试其他编码或返回错误
 * - 超大文件：即使 limit=2000，也要控制总输出大小
 *
 * @author sunchenhao
 * @date 2026/3/28
 */
public class ReadFileTool implements Tool {

    /** 默认起始行号（1-based） */
    private static final int DEFAULT_OFFSET = 1;

    /** 默认最多读取行数 */
    private static final int DEFAULT_LIMIT = 2000;

    /** 输出最大字符数：200KB，防止撑爆上下文窗口 */
    private static final int MAX_OUTPUT_LENGTH = 200 * 1024;

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return "Reads a file from the local filesystem and returns its content with line numbers. "
                + "The file_path must be an absolute path. "
                + "Use offset and limit to read specific portions of large files. "
                + "Results are returned in cat -n format with line numbers starting at 1.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> filePathProp = new LinkedHashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path to the file to read");

        Map<String, Object> offsetProp = new LinkedHashMap<>();
        offsetProp.put("type", "integer");
        offsetProp.put("description", "The line number to start reading from (1-based), default 1");

        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("description", "Maximum number of lines to read, default 2000");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePathProp);
        properties.put("offset", offsetProp);
        properties.put("limit", limitProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path"));

        return schema;
    }

    @Override
    public boolean requiresPermission() {
        // 只读操作，无需用户审批
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        // ——— 第1步：提取参数 ———
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'file_path' is required");
        }

        int offset = DEFAULT_OFFSET;
        Object offsetObj = input.get("offset");
        if (offsetObj instanceof Number) {
            offset = ((Number) offsetObj).intValue();
            if (offset < 1) offset = 1;  // 行号最小为1
        }

        int limit = DEFAULT_LIMIT;
        Object limitObj = input.get("limit");
        if (limitObj instanceof Number) {
            limit = ((Number) limitObj).intValue();
            if (limit < 1) limit = 1;
        }

        // ——— 第2步：校验文件路径 ———
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + filePath);
        }

        if (Files.isDirectory(path)) {
            return ToolResult.error("Path is a directory, not a file: " + filePath);
        }

        // ——— 第3步：使用 BufferedReader 逐行读取，避免大文件 OOM ———
        try (java.io.BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lineNum = 0;
            int linesRead = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum < offset) continue;   // skip 到 offset
                if (linesRead >= limit) break;     // 已读够 limit 行

                if (linesRead > 0) sb.append("\n");
                sb.append(String.format("%6d\t%s", lineNum, line));
                linesRead++;

                if (sb.length() > MAX_OUTPUT_LENGTH) {
                    sb.append("\n... (output truncated, exceeded ")
                            .append(MAX_OUTPUT_LENGTH / 1024).append("KB)");
                    break;
                }
            }

            if (lineNum == 0) {
                return ToolResult.success("File is empty: " + filePath);
            }
            if (linesRead == 0) {
                return ToolResult.success("Offset " + offset + " is beyond file length (" + lineNum + " lines)");
            }
            return ToolResult.success(sb.toString());

        } catch (IOException e) {
            return ToolResult.error("Failed to read file (possibly binary or encoding issue): " + e.getMessage());
        }
    }
}

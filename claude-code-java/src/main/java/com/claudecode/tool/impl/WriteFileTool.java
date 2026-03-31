package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WriteFileTool — 文件写入工具（全量覆写）
 *
 * 功能：将内容写入指定路径的文件。如果文件已存在则覆盖，不存在则创建。
 * 这是破坏性操作，必须 requiresPermission() 返回 true。
 *
 * 工具定义：
 * - name: "Write"
 * - requiresPermission: true
 * - inputSchema:
 *   {
 *     "type": "object",
 *     "properties": {
 *       "file_path": {
 *         "type": "string",
 *         "description": "要写入的文件的绝对路径"
 *       },
 *       "content": {
 *         "type": "string",
 *         "description": "要写入的完整文件内容"
 *       }
 *     },
 *     "required": ["file_path", "content"]
 *   }
 *
 * execute() 实现思路：
 *
 * 1. 从 input 中获取 "file_path" 和 "content"
 *
 * 2. 确保父目录存在：
 *    - Path parent = path.getParent()
 *    - if (parent != null) Files.createDirectories(parent)
 *
 * 3. 写入文件：
 *    - Files.writeString(path, content, StandardCharsets.UTF_8)
 *    - 或使用 Files.write(path, content.getBytes(StandardCharsets.UTF_8))
 *
 * 4. 返回结果：
 *    - 成功: ToolResult.success("File written successfully: " + filePath)
 *    - 失败: ToolResult.error("Failed to write file: " + 异常信息)
 *
 * 注意事项：
 * - 这个工具是全量覆写，不是追加。LLM 需要提供完整的文件内容。
 * - 对于修改已有文件的场景，通常应该使用 EditFileTool（局部替换）而不是这个工具。
 * - WriteFileTool 主要用于创建新文件。
 */
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public String description() {
        return "Writes content to a file at the specified path. "
                + "Creates the file if it doesn't exist, overwrites if it does. "
                + "Use this for creating new files. "
                + "For modifying existing files, prefer the Edit tool (safer, only changes a specific part).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> filePathProp = new LinkedHashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path of the file to write");

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "The complete content to write to the file");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePathProp);
        properties.put("content", contentProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "content"));
        return schema;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'file_path' is required");
        }
        String content = (String) input.get("content");
        if (content == null) {
            return ToolResult.error("Parameter 'content' is required");
        }

        try {
            Path path = Paths.get(filePath);

            // Ensure parent directories exist
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content, StandardCharsets.UTF_8);
            return ToolResult.success("File written successfully: " + filePath);

        } catch (Exception e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }
}

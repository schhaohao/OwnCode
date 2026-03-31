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
 * EditFileTool — 文件编辑工具（精确字符串替换）
 *
 * 功能：在文件中查找指定的旧字符串，替换为新字符串。
 * 这是修改现有文件的主要方式，比 Write 更安全（只改局部）。
 * 需要权限审批。
 *
 * 工具定义：
 * - name: "Edit"
 * - requiresPermission: true
 * - inputSchema:
 *   {
 *     "type": "object",
 *     "properties": {
 *       "file_path": {
 *         "type": "string",
 *         "description": "要编辑的文件的绝对路径"
 *       },
 *       "old_string": {
 *         "type": "string",
 *         "description": "要被替换的原始文本（必须在文件中唯一匹配）"
 *       },
 *       "new_string": {
 *         "type": "string",
 *         "description": "替换后的新文本"
 *       },
 *       "replace_all": {
 *         "type": "boolean",
 *         "description": "是否替换所有匹配项，默认 false（只替换第一个）"
 *       }
 *     },
 *     "required": ["file_path", "old_string", "new_string"]
 *   }
 *
 * execute() 实现思路：
 *
 * 1. 从 input 中获取参数
 *
 * 2. 读取文件完整内容：
 *    String content = Files.readString(path)
 *
 * 3. 查找 old_string：
 *    - 检查 old_string 是否存在于文件中
 *    - 如果不存在：返回 ToolResult.error("old_string not found in file")
 *    - 如果 replace_all=false，检查是否唯一：
 *      计算 old_string 出现次数，如果 > 1，返回错误提示
 *      "old_string matches N times. Provide more context to make it unique, or use replace_all=true"
 *
 * 4. 执行替换：
 *    - replace_all=false: content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString))
 *      注意：用 Pattern.quote 和 Matcher.quoteReplacement 防止正则特殊字符问题
 *    - replace_all=true:  content.replace(oldString, newString)
 *
 * 5. 写回文件：
 *    Files.writeString(path, newContent)
 *
 * 6. 返回结果：
 *    ToolResult.success("Successfully edited " + filePath)
 *
 * 关键注意事项：
 * - old_string 和 new_string 不能相同（没意义的操作，返回错误）
 * - 必须是精确字符串匹配，不是正则匹配
 * - 保持缩进：old_string 中的空格/tab 必须精确匹配文件中的格式
 * - 这是整个系统中 LLM 最常用的修改文件的方式
 */
public class EditFileTool implements Tool {

    @Override
    public String name() {
        return "Edit";
    }

    @Override
    public String description() {
        return "Performs exact string replacement in a file. "
                + "Finds old_string in the file and replaces it with new_string. "
                + "The old_string must uniquely match a single location in the file "
                + "(unless replace_all is true). "
                + "Use this tool for modifying existing files — it is safer than Write "
                + "because it only changes the specific part you target.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> filePathProp = new LinkedHashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path of the file to edit");

        Map<String, Object> oldStringProp = new LinkedHashMap<>();
        oldStringProp.put("type", "string");
        oldStringProp.put("description", "The text to find and replace (must be unique in the file)");

        Map<String, Object> newStringProp = new LinkedHashMap<>();
        newStringProp.put("type", "string");
        newStringProp.put("description", "The replacement text");

        Map<String, Object> replaceAllProp = new LinkedHashMap<>();
        replaceAllProp.put("type", "boolean");
        replaceAllProp.put("description", "Replace all occurrences, default false");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePathProp);
        properties.put("old_string", oldStringProp);
        properties.put("new_string", newStringProp);
        properties.put("replace_all", replaceAllProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path", "old_string", "new_string"));
        return schema;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("Parameter 'file_path' is required");
        }
        if (oldString == null) {
            return ToolResult.error("Parameter 'old_string' is required");
        }
        if (newString == null) {
            return ToolResult.error("Parameter 'new_string' is required");
        }
        if (oldString.equals(newString)) {
            return ToolResult.error("old_string and new_string are identical — nothing to change");
        }

        boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));

        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                return ToolResult.error("File not found: " + filePath);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            // Check if old_string exists
            int firstIdx = content.indexOf(oldString);
            if (firstIdx < 0) {
                return ToolResult.error("old_string not found in file: " + filePath);
            }

            // If not replace_all, check uniqueness
            if (!replaceAll) {
                int secondIdx = content.indexOf(oldString, firstIdx + 1);
                if (secondIdx >= 0) {
                    // Count total occurrences
                    int count = 0;
                    int idx = 0;
                    while ((idx = content.indexOf(oldString, idx)) >= 0) {
                        count++;
                        idx += oldString.length();
                    }
                    return ToolResult.error(
                            "old_string matches " + count + " times in the file. "
                            + "Provide more surrounding context to make it unique, "
                            + "or set replace_all=true to replace all occurrences.");
                }
            }

            // Perform replacement
            String newContent;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
            } else {
                // Replace only the first occurrence — use literal replacement (not regex)
                newContent = content.substring(0, firstIdx)
                        + newString
                        + content.substring(firstIdx + oldString.length());
            }

            Files.writeString(path, newContent, StandardCharsets.UTF_8);
            return ToolResult.success("Successfully edited " + filePath);

        } catch (Exception e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }
}

package com.claudecode.tool.impl;

import com.claudecode.tool.ToolResult;

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
public class EditFileTool implements com.claudecode.tool.Tool {
    @Override
    public String name() {
        return "";
    }

    @Override
    public String description() {
        return "";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of();
    }

    @Override
    public boolean requiresPermission() {
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        return null;
    }

    // TODO: 实现 Tool 接口的所有方法
}

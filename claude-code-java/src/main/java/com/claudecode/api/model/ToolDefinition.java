package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * ToolDefinition — 工具定义模型（传给 API 的工具描述）
 *
 * 每个工具在 API 请求中需要提供一个定义，告诉 LLM 这个工具能做什么、接收什么参数。
 * 这些定义放在请求体的 "tools" 数组中。
 *
 * JSON 结构：
 *   {
 *     "name": "Read",
 *     "description": "读取指定路径的文件内容...(详细描述工具的用途和参数含义)",
 *     "input_schema": {
 *       "type": "object",
 *       "properties": {
 *         "file_path": {
 *           "type": "string",
 *           "description": "要读取的文件的绝对路径"
 *         },
 *         "offset": {
 *           "type": "integer",
 *           "description": "从第几行开始读取（0-based）"
 *         },
 *         "limit": {
 *           "type": "integer",
 *           "description": "最多读取多少行"
 *         }
 *       },
 *       "required": ["file_path"]
 *     }
 *   }
 *
 * 字段说明：
 * - name: 工具名称，LLM 会在 tool_use 中引用这个名字
 *         规则：^[a-zA-Z0-9_-]{1,64}$
 *
 * - description: 工具描述，这是 LLM 决定是否使用这个工具的主要依据
 *               写得越清晰、越具体，LLM 使用工具就越准确
 *               建议至少 3-4 句话
 *
 * - inputSchema: JSON Schema 格式的输入参数定义
 *               type 必须是 "object"
 *               properties 定义每个参数
 *               required 列出必填参数
 *
 * 需要实现：
 * - 字段: String name, String description, Map<String, Object> inputSchema
 * - 构造函数或 Builder
 * - Jackson 序列化支持（注意 inputSchema 序列化为 "input_schema"，用 @JsonProperty）
 *
 * 提示：
 * - 每个 Tool 实现类可以提供一个 toDefinition() 方法返回 ToolDefinition
 * - 或者 ToolRegistry 负责从 Tool 接口的 name/description/inputSchema 方法构建
 *
 * @author sunchenhao
 * @date 2026/3/28
 */
public class ToolDefinition {

    @JsonProperty("name")
    private final String name;

    @JsonProperty("description")
    private final String description;

    /**
     * JSON Schema 格式的输入参数定义
     *
     * Java 字段名 inputSchema → JSON 字段名 "input_schema"（API 要求下划线风格）
     * 结构示例：
     *   {
     *     "type": "object",
     *     "properties": { "command": {"type":"string","description":"..."} },
     *     "required": ["command"]
     *   }
     */
    @JsonProperty("input_schema")
    private final Map<String, Object> inputSchema;

    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * 从 Tool 接口实例构建 ToolDefinition
     *
     * 使用场景：ToolRegistry.getAllDefinitions() 中批量转换
     *   tools.values().stream()
     *       .map(ToolDefinition::fromTool)
     *       .collect(Collectors.toList());
     */
    public static ToolDefinition fromTool(com.claudecode.tool.Tool tool) {
        return new ToolDefinition(tool.name(), tool.description(), tool.inputSchema());
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }
}

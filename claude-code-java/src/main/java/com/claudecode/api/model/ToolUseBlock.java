package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * ToolUseBlock — 工具调用请求块 (type = "tool_use")
 *
 * 出现在 assistant 消息中，表示 LLM 请求调用某个工具。
 * AgentLoop 收到后，根据 name 查找工具，用 input 作为参数执行，
 * 然后把结果包装成 ToolResultBlock 反馈给 LLM。
 *
 * JSON 格式：
 *   {
 *     "type": "tool_use",
 *     "id": "toolu_01A09q90qw90lq917835lq9",
 *     "name": "Read",
 *     "input": {"file_path": "/path/to/file", "limit": 100}
 *   }
 *
 * 字段说明：
 * - id:    本次工具调用的唯一标识，由 Claude API 生成
 *          在返回 tool_result 时必须用 tool_use_id 匹配回来
 * - name:  工具名称，对应 Tool.name()，如 "Read", "Bash", "Edit"
 * - input: 工具的输入参数，由 LLM 根据 inputSchema 生成
 *          已从 JSON 解析为 Map<String, Object>
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class ToolUseBlock extends ContentBlock {

    /** 工具调用唯一标识（API生成），用于匹配 tool_result */
    @JsonProperty("id")
    private final String id;

    /** 工具名称，对应 Tool.name() */
    @JsonProperty("name")
    private final String name;

    /** 工具输入参数，LLM 根据 inputSchema 生成 */
    @JsonProperty("input")
    private final Map<String, Object> input;

    @JsonCreator
    public ToolUseBlock(@JsonProperty("id") String id, @JsonProperty("name") String name, @JsonProperty("input") Map<String, Object> input) {
        super("tool_use");  // 固定 type = "tool_use"
        this.id = id;
        this.name = name;
        this.input = input;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getInput() {
        return input;
    }
}

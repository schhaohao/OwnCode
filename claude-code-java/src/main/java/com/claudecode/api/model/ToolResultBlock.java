package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ToolResultBlock — 工具执行结果块 (type = "tool_result")
 *
 * 出现在 user 消息中（注意：不是 assistant 消息！），
 * 你的代码执行完工具后，把结果包装成这个 block 发回给 LLM。
 *
 * JSON 格式：
 *   {
 *     "type": "tool_result",
 *     "tool_use_id": "toolu_01A09q90qw90lq917835lq9",
 *     "content": "文件内容...",
 *     "is_error": false
 *   }
 *
 * 字段说明：
 * - toolUseId: 必须与对应的 ToolUseBlock.id 完全一致！
 *              这是 API 用来配对"哪个调用对应哪个结果"的唯一依据
 * - content:   工具执行的输出文本
 * - isError:   标记执行是否出错
 *              true 时 LLM 通常会调整策略重试（如换个命令、换个路径）
 *
 * 注意 Jackson 字段名映射：
 * - Java 字段 toolUseId  ↔ JSON 字段 "tool_use_id"  （下划线风格）
 * - Java 字段 isError    ↔ JSON 字段 "is_error"
 * 通过 @JsonProperty 注解完成映射
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class ToolResultBlock extends ContentBlock {

    /** 对应的 ToolUseBlock 的 id（必须精确匹配） */
    @JsonProperty("tool_use_id")
    private final String toolUseId;

    /** 工具执行结果文本 */
    @JsonProperty("content")
    private final String content;

    /** 是否执行出错 */
    @JsonProperty("is_error")
    private final boolean isError;

    @JsonCreator
    public ToolResultBlock(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") boolean isError) {
        super("tool_result");  // 固定 type = "tool_result"
        this.toolUseId = toolUseId;
        this.content = content;
        this.isError = isError;
    }

    /**
     * 便捷工厂方法：从 ToolResult + toolUseId 创建
     * 用于 AgentLoop 中将工具执行结果包装为 API 需要的格式
     */
    public static ToolResultBlock from(String toolUseId, com.claudecode.tool.ToolResult result) {
        return new ToolResultBlock(toolUseId, result.getContent(), result.isError());
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }
}

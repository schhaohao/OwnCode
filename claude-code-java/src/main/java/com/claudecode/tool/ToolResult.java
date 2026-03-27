package com.claudecode.tool;

/**
 * ToolResult — 工具执行结果
 *
 * 封装工具执行后的返回值，最终会被包装成 tool_result ContentBlock 发送给 API。
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class ToolResult {

    /** 工具执行的输出内容（成功时为结果，失败时为错误信息） */
    private final String content;

    /** 是否执行出错（true 时 LLM 会尝试调整策略） */
    private final boolean isError;

    /**
     * 私有构造函数，强制通过静态工厂方法创建实例
     */
    private ToolResult(String content, boolean isError) {
        this.content = content;
        this.isError = isError;
    }

    /**
     * 创建成功结果
     *
     * @param content 执行输出内容（如文件内容、命令输出等）
     */
    public static ToolResult success(String content) {
        return new ToolResult(content, false);
    }

    /**
     * 创建失败结果
     *
     * @param errorMessage 错误描述信息
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(errorMessage, true);
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }
}

package com.claudecode.cli;

import com.claudecode.tool.ToolResult;

import java.util.Map;

/**
 * TerminalRenderer — 终端输出渲染器
 *
 * 提供美观的终端输出，使用 ANSI 颜色和 Unicode 符号区分不同类型的消息。
 *
 * 视觉风格：
 *   >  用户输入提示符（绿色）
 *   ◆  AI 思考/响应（蓝紫色）
 *   ⚙  工具调用（黄色）
 *   ←  工具返回结果（绿色）
 *   ✓  成功信息（绿色）
 *   ✗  错误信息（红色）
 *   −  列表项
 */
public class TerminalRenderer {

    // ==================== ANSI 颜色代码 ====================
    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String RED     = "\033[31m";
    private static final String GREEN   = "\033[32m";
    private static final String YELLOW  = "\033[33m";
    private static final String MAGENTA = "\033[35m";
    private static final String CYAN    = "\033[36m";
    private static final String GRAY    = "\033[90m";

    // ==================== Unicode 符号 ====================
    private static final String ICON_THINKING = "◆";
    private static final String ICON_TOOL     = "⚙";
    private static final String ICON_RESULT   = "←";
    private static final String ICON_SUCCESS  = "✓";
    private static final String ICON_ERROR    = "✗";

    /** 是否启用颜色输出（重定向到文件时应禁用） */
    private final boolean colorEnabled;

    public TerminalRenderer() {
        this.colorEnabled = System.console() != null;
    }

    public TerminalRenderer(boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    // ==================== 流式文本渲染 ====================

    /**
     * 流式文本开始前的前缀：输出 "◆ " 符号（蓝紫色）
     * 在 AgentLoop 发起流式 API 调用时，第一个 text_delta 到来前调用
     */
    public void renderTextPrefix() {
        System.out.print(color(MAGENTA, "  " + ICON_THINKING + " "));
    }

    /**
     * 流式文本结束后的后缀：输出换行
     */
    public void renderTextSuffix() {
        System.out.println();
    }

    /**
     * 渲染 LLM 输出的文本（非流式场景）
     */
    public void renderText(String text) {
        System.out.print(text);
    }

    // ==================== 工具调用渲染 ====================

    /**
     * 渲染工具调用信息
     *
     * 格式：  ⚙ ToolName → detail
     * ⚙ 黄色，工具名白色加粗，→ 和参数绿色
     */
    public void renderToolCall(String toolName, Map<String, Object> input) {
        String detail = extractDetail(toolName, input);
        String line = color(YELLOW, "  " + ICON_TOOL + " ") + color(BOLD, toolName);
        if (detail != null) {
            line += color(GREEN, " → " + detail);
        }
        System.out.println(line);
    }

    /**
     * 渲染工具执行结果
     *
     * 成功：  ← ✓ 摘要（绿色）
     * 失败：  ← ✗ 错误信息（红色）
     */
    public void renderToolResult(String toolName, ToolResult result) {
        if (result.isError()) {
            System.out.println(color(RED, "  " + ICON_RESULT + " " + ICON_ERROR + " "
                    + truncateOneLine(result.getContent(), 200)));
        } else {
            System.out.println(color(GREEN, "  " + ICON_RESULT + " " + ICON_SUCCESS + " "
                    + truncateOneLine(result.getContent(), 100)));
        }
    }

    // ==================== 状态信息渲染 ====================

    /**
     * 渲染错误信息（红色，带 ✗ 前缀）
     */
    public void renderError(String message) {
        System.out.println(color(RED, "  " + ICON_ERROR + " " + message));
    }

    /**
     * 渲染系统信息（灰色）
     */
    public void renderSystemMessage(String message) {
        System.out.println(color(GRAY, message));
    }

    /**
     * 渲染权限询问框
     */
    public void renderPermissionPrompt(String toolName, String detail) {
        System.out.println();
        System.out.println(color(YELLOW, "  " + ICON_TOOL + " Permission required: ") + color(BOLD, toolName));
        if (detail != null) {
            System.out.println(color(GRAY, "    " + detail));
        }
    }

    // ==================== 欢迎 & 帮助 ====================

    /**
     * 渲染欢迎信息（含连接配置）
     */
    public void renderWelcome(String model, String baseUrl, String apiKey) {
        System.out.println();
        String title = "Claude Code Java";
        String border = "─".repeat(48);

        System.out.println(color(CYAN, "  ┌" + border + "┐"));
        System.out.println(color(CYAN, "  │") + centerPad(color(BOLD + CYAN, title), 48)
                + color(CYAN, "│"));
        System.out.println(color(CYAN, "  │")
                + centerPad(color(GRAY, "Type /help for available commands"), 48)
                + color(CYAN, "│"));
        System.out.println(color(CYAN, "  ├" + border + "┤"));

        printConfigLine("Model", model);
        printConfigLine("API", baseUrl != null ? baseUrl : "https://api.anthropic.com");
        printConfigLine("Key", maskApiKey(apiKey));

        System.out.println(color(CYAN, "  └" + border + "┘"));
        System.out.println();
    }

    /**
     * 渲染帮助信息
     */
    public void renderHelp() {
        System.out.println();
        System.out.println(color(BOLD, "  Available commands:"));
        System.out.println("    " + color(CYAN, "/help ") + color(GRAY, " — ") + "Show this help message");
        System.out.println("    " + color(CYAN, "/clear") + color(GRAY, " — ") + "Clear conversation history");
        System.out.println("    " + color(CYAN, "/exit ") + color(GRAY, " — ") + "Exit the program");
        System.out.println("    " + color(CYAN, "/quit ") + color(GRAY, " — ") + "Exit the program");
        System.out.println();
    }

    /**
     * 渲染 Skill 列表项（供 Repl 调用）
     */
    public void renderSkillItem(String name, String description) {
        System.out.println("    " + color(CYAN, "/" + name)
                + (description.isEmpty() ? "" : color(GRAY, " — ") + description));
    }

    /**
     * 渲染带标题的节头（供 Repl 调用）
     */
    public void renderSectionHeader(String title) {
        System.out.println("  " + color(BOLD, title));
    }

    // ==================== 提示符 ====================

    /**
     * 获取带颜色的命令行提示符
     */
    public String getPrompt() {
        if (!colorEnabled) return "> ";
        return GREEN + BOLD + "> " + RESET;
    }

    // ==================== 内部辅助 ====================

    /**
     * 包装 ANSI 颜色：如果颜色被禁用则返回原文
     */
    String color(String ansiCode, String text) {
        if (!colorEnabled) return text;
        return ansiCode + text + RESET;
    }

    /**
     * 根据工具类型提取关键参数用于展示
     */
    private String extractDetail(String toolName, Map<String, Object> input) {
        if (input == null) return null;
        switch (toolName) {
            case "Bash":   return getStr(input, "command");
            case "Read":
            case "Edit":
            case "Write":  return getStr(input, "file_path");
            case "Glob":
            case "Grep":   return getStr(input, "pattern");
            case "Skill":  return getStr(input, "skill");
            default:       return null;
        }
    }

    private String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : null;
    }

    private String truncateOneLine(String text, int maxLen) {
        if (text == null) return "";
        int newline = text.indexOf('\n');
        String line = newline >= 0 ? text.substring(0, newline) : text;
        return line.length() > maxLen ? line.substring(0, maxLen) + "..." : line;
    }

    /**
     * API Key 脱敏显示
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null) return "not set";
        if (apiKey.length() <= 8) return "****";
        int lastDash = apiKey.lastIndexOf('-');
        String prefix = lastDash > 0 && lastDash < apiKey.length() - 5
                ? apiKey.substring(0, lastDash + 1)
                : apiKey.substring(0, 4);
        String suffix = apiKey.substring(apiKey.length() - 4);
        return prefix + "****" + suffix;
    }

    /**
     * 居中填充文本到指定宽度（用于欢迎框）
     * 注意：text 可能包含 ANSI 转义码，需要计算可见宽度
     */
    private String centerPad(String text, int width) {
        int visibleLen = stripAnsi(text).length();
        int totalPad = width - visibleLen;
        if (totalPad <= 0) return text;
        int left = totalPad / 2;
        int right = totalPad - left;
        return " ".repeat(left) + text + " ".repeat(right);
    }

    /**
     * 打印配置行（欢迎框内）
     */
    private void printConfigLine(String label, String value) {
        String content = color(GRAY, "  " + label + ":  ") + color(BOLD, value);
        int visibleLen = ("  " + label + ":  " + value).length();
        int pad = 48 - visibleLen;
        if (pad < 0) pad = 0;
        System.out.println(color(CYAN, "  │") + content + " ".repeat(pad) + color(CYAN, "│"));
    }

    /**
     * 剥离 ANSI 转义码，返回可见文本
     */
    private static String stripAnsi(String text) {
        return text.replaceAll("\033\\[[0-9;]*m", "");
    }
}

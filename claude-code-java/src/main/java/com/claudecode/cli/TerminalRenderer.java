package com.claudecode.cli;

import com.claudecode.tool.ToolResult;

import java.util.Map;

/**
 * TerminalRenderer — 终端输出渲染器
 *
 * 核心职责：
 *   美化终端输出，包括 ANSI 颜色、简易 Markdown 渲染、工具调用展示等。
 *   让 CLI 的输出更易读、更美观。
 *
 * 需要实现的方法：
 *
 * 1. public void renderText(String text)
 *    - 渲染 LLM 输出的文本
 *    - 简易 Markdown 渲染（可以逐步完善）：
 *      - **bold** → ANSI 加粗
 *      - `code`   → ANSI 反色或特殊颜色
 *      - ```代码块``` → 带边框和语法高亮（进阶）
 *      - # 标题   → ANSI 加粗 + 颜色
 *    - P0 阶段可以直接原样输出，不做渲染
 *
 * 2. public void renderToolCall(String toolName, Map<String, Object> input)
 *    - 渲染工具调用的展示信息
 *    - 格式示例（带颜色）：
 *      ⚡ Read {"file_path": "/path/to/file"}
 *      ⚡ Bash {"command": "npm test"}
 *    - 工具名用一种颜色（如黄色），参数用另一种
 *
 * 3. public void renderToolResult(String toolName, ToolResult result)
 *    - 渲染工具执行结果
 *    - 成功：用正常颜色或灰色显示（因为结果可能很长，不应太抢眼）
 *    - 失败：用红色显示错误信息
 *
 * 4. public void renderError(String message)
 *    - 渲染错误信息（红色）
 *
 * 5. public void renderSystemMessage(String message)
 *    - 渲染系统信息（灰色/暗色，如 token 用量提示）
 *
 * ANSI 颜色代码速查：
 *   \033[0m    重置
 *   \033[1m    加粗
 *   \033[31m   红色
 *   \033[32m   绿色
 *   \033[33m   黄色
 *   \033[34m   蓝色
 *   \033[36m   青色
 *   \033[90m   灰色（暗色）
 *
 * 使用示例：
 *   System.out.print("\033[33m⚡ Bash\033[0m ");  // 黄色工具名
 *   System.out.println("\033[90m{\"command\": \"ls\"}\033[0m");  // 灰色参数
 *
 * 设计建议：
 * - P0 阶段只需要实现基本的颜色区分（工具调用黄色、错误红色、正常文本无色）
 * - P1 阶段添加简易 Markdown 渲染
 * - P2 阶段考虑使用 Jansi 库支持 Windows 终端
 * - 检测终端是否支持颜色：如果不支持（如重定向到文件），禁用 ANSI 代码
 */
public class TerminalRenderer {

    // ==================== ANSI 颜色代码 ====================
    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String RED     = "\033[31m";
    private static final String YELLOW  = "\033[33m";
    private static final String CYAN    = "\033[36m";
    private static final String GRAY    = "\033[90m";

    /** 是否启用颜色输出（重定向到文件时应禁用） */
    private final boolean colorEnabled;

    public TerminalRenderer() {
        // 检测 stdout 是否连接到真实终端（非重定向/管道）
        this.colorEnabled = System.console() != null;
    }

    public TerminalRenderer(boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    /**
     * 渲染 LLM 输出的文本
     *
     * P0 阶段：直接原样输出，不做 Markdown 渲染
     */
    public void renderText(String text) {
        System.out.print(text);
    }

    /**
     * 渲染工具调用的展示信息
     *
     * 格式：> ToolName: detail
     * 工具名黄色加粗，参数灰色
     */
    public void renderToolCall(String toolName, Map<String, Object> input) {
        String detail = extractDetail(toolName, input);
        System.out.println();
        if (detail != null) {
            System.out.println(color(YELLOW + BOLD, "> " + toolName) + color(GRAY, ": " + detail));
        } else {
            System.out.println(color(YELLOW + BOLD, "> " + toolName));
        }
    }

    /**
     * 渲染工具执行结果
     *
     * 成功：灰色（结果可能很长，不应太抢眼）
     * 失败：红色错误信息
     */
    public void renderToolResult(String toolName, ToolResult result) {
        if (result.isError()) {
            System.out.println(color(RED, "  [Error] " + truncateOneLine(result.getContent(), 200)));
        } else {
            System.out.println(color(GRAY, "  [Done] " + truncateOneLine(result.getContent(), 100)));
        }
    }

    /**
     * 渲染错误信息（红色）
     */
    public void renderError(String message) {
        System.out.println(color(RED, "[Error] " + message));
    }

    /**
     * 渲染系统信息（灰色，如 token 用量提示、上下文压缩通知）
     */
    public void renderSystemMessage(String message) {
        System.out.println(color(GRAY, message));
    }

    /**
     * 渲染权限询问框标题
     */
    public void renderPermissionPrompt(String toolName, String detail) {
        System.out.println();
        System.out.println(color(YELLOW, "  Permission required: ") + color(BOLD, toolName));
        if (detail != null) {
            System.out.println(color(GRAY, "  " + detail));
        }
    }

    /**
     * 渲染欢迎信息（含连接配置）
     */
    public void renderWelcome(String model, String baseUrl, String apiKey) {
        String border = color(CYAN, "================================================");
        System.out.println(border);
        System.out.println(color(BOLD, "        Claude Code Java v1.0"));
        System.out.println(color(GRAY, "    Type /help for available commands"));
        System.out.println();
        System.out.println(color(GRAY, "    Model:    ") + color(BOLD, model));
        System.out.println(color(GRAY, "    API:      ") + color(BOLD, baseUrl != null ? baseUrl : "https://api.anthropic.com"));
        System.out.println(color(GRAY, "    Key:      ") + color(BOLD, maskApiKey(apiKey)));
        System.out.println(border);
        System.out.println();
    }

    /**
     * 渲染帮助信息
     */
    public void renderHelp() {
        System.out.println(color(BOLD, "Available commands:"));
        System.out.println("  " + color(CYAN, "/help ") + " - Show this help message");
        System.out.println("  " + color(CYAN, "/clear") + " - Clear conversation history");
        System.out.println("  " + color(CYAN, "/exit ") + " - Exit the program");
        System.out.println("  " + color(CYAN, "/quit ") + " - Exit the program");
        System.out.println();
        System.out.println(color(GRAY, "Enter any text to chat with Claude."));
    }

    // ==================== 内部辅助 ====================

    /**
     * 包装 ANSI 颜色：如果颜色被禁用则返回原文
     */
    private String color(String ansiCode, String text) {
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
     * API Key 脱敏：保留前缀和最后4位，中间用 **** 替代
     * 例如：sk-kimi-JuqI...dOW → sk-kimi-****dOW
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null) return "not set";
        if (apiKey.length() <= 8) return "****";
        // 找最后一个 '-' 之后的前缀部分
        int lastDash = apiKey.lastIndexOf('-');
        String prefix = lastDash > 0 && lastDash < apiKey.length() - 5
                ? apiKey.substring(0, lastDash + 1)
                : apiKey.substring(0, 4);
        String suffix = apiKey.substring(apiKey.length() - 4);
        return prefix + "****" + suffix;
    }
}

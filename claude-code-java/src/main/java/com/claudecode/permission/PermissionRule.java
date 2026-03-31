package com.claudecode.permission;

import java.util.Map;

/**
 * PermissionRule — 权限规则
 *
 * 表示一条权限规则，用于匹配特定的工具调用。
 * 规则可以是 allow（允许）或 deny（拒绝）类型。
 *
 * 规则格式：
 *   - "Read"               → 匹配所有 Read 工具调用
 *   - "Bash(npm run *)"    → 匹配 Bash 工具中以 "npm run" 开头的命令
 *   - "Edit(/src/**)"      → 匹配 Edit 工具中路径在 /src/ 下的操作
 *   - "Bash(git commit *)" → 匹配 git commit 相关命令
 *
 * 字段：
 * - toolName (String): 工具名称，如 "Bash", "Read", "Edit"
 * - specifier (String): 参数匹配模式（可选），支持 * 通配符
 *   - null 或空字符串表示匹配该工具的所有调用
 *   - 非空时根据工具类型匹配不同的参数：
 *     - Bash: 匹配 command 参数
 *     - Read/Edit/Write: 匹配 file_path 参数
 *     - Glob: 匹配 pattern 参数
 *
 * 需要实现的方法：
 *
 * 1. 构造函数 PermissionRule(String toolName, String specifier)
 *
 * 2. public boolean matches(String toolName, Map<String, Object> input)
 *    - 检查给定的工具调用是否匹配此规则
 *    - 先匹配 toolName
 *    - 如果 specifier 为空，只要 toolName 匹配就返回 true
 *    - 如果 specifier 非空，根据工具类型提取对应参数值，进行通配符匹配
 *
 * 3. private boolean wildcardMatch(String pattern, String text)
 *    - 实现简单的通配符匹配
 *    - * 匹配任意字符序列
 *    - 其他字符精确匹配
 *    - 示例：
 *      "npm run *" 匹配 "npm run test", "npm run build"
 *      "git *"     匹配 "git status", "git commit -m 'msg'"
 *      "/src/**"   匹配 "/src/main/App.java"
 *
 * 4. 静态解析方法 static PermissionRule parse(String ruleString)
 *    - 从字符串解析规则
 *    - "Bash(npm run *)" → PermissionRule("Bash", "npm run *")
 *    - "Read"            → PermissionRule("Read", null)
 */
public class PermissionRule {

    private final String toolName;
    private final String specifier;

    public PermissionRule(String toolName, String specifier) {
        this.toolName = toolName;
        this.specifier = specifier;
    }

    /**
     * 检查给定的工具调用是否匹配此规则
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return true 如果匹配
     */
    public boolean matches(String toolName, Map<String, Object> input) {
        // 先匹配工具名
        if (!this.toolName.equals(toolName)) {
            return false;
        }
        // specifier 为空，只要 toolName 匹配就算命中
        if (specifier == null || specifier.isEmpty()) {
            return true;
        }
        // 根据工具类型提取对应的参数值进行通配符匹配
        String value = extractMatchValue(toolName, input);
        if (value == null) {
            return false;
        }
        return wildcardMatch(specifier, value);
    }

    /**
     * 根据工具类型从 input 中提取用于匹配的参数值
     *
     * - Bash → command
     * - Read/Edit/Write → file_path
     * - Glob/Grep → pattern
     */
    private String extractMatchValue(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        switch (toolName) {
            case "Bash":
                return getStringValue(input, "command");
            case "Read":
            case "Edit":
            case "Write":
                return getStringValue(input, "file_path");
            case "Glob":
            case "Grep":
                return getStringValue(input, "pattern");
            default:
                // 未知工具类型，尝试取第一个字符串值
                for (Object val : input.values()) {
                    if (val instanceof String) {
                        return (String) val;
                    }
                }
                return null;
        }
    }

    private String getStringValue(Map<String, Object> input, String key) {
        Object val = input.get(key);
        return val instanceof String ? (String) val : null;
    }

    /**
     * 简单通配符匹配
     *
     * * 匹配任意长度的任意字符（包括空字符串）
     * 其他字符精确匹配
     *
     * 示例：
     *   "npm run *"  匹配 "npm run test"
     *   "git *"      匹配 "git status"
     *   "/src/**"    匹配 "/src/main/App.java"
     */
    private boolean wildcardMatch(String pattern, String text) {
        // 动态规划：dp[i][j] 表示 pattern[0..i-1] 是否匹配 text[0..j-1]
        int pLen = pattern.length();
        int tLen = text.length();
        boolean[][] dp = new boolean[pLen + 1][tLen + 1];
        dp[0][0] = true;

        // pattern 以连续 * 开头时可以匹配空字符串
        for (int i = 1; i <= pLen; i++) {
            if (pattern.charAt(i - 1) == '*') {
                dp[i][0] = dp[i - 1][0];
            } else {
                break;
            }
        }

        for (int i = 1; i <= pLen; i++) {
            char pc = pattern.charAt(i - 1);
            for (int j = 1; j <= tLen; j++) {
                if (pc == '*') {
                    // * 匹配零个字符(dp[i-1][j]) 或匹配一个以上字符(dp[i][j-1])
                    dp[i][j] = dp[i - 1][j] || dp[i][j - 1];
                } else {
                    // 精确匹配当前字符
                    dp[i][j] = dp[i - 1][j - 1] && pc == text.charAt(j - 1);
                }
            }
        }
        return dp[pLen][tLen];
    }

    /**
     * 从字符串解析规则
     *
     * "Bash(npm run *)" → PermissionRule("Bash", "npm run *")
     * "Read"            → PermissionRule("Read", null)
     */
    public static PermissionRule parse(String ruleString) {
        if (ruleString == null || ruleString.isBlank()) {
            throw new IllegalArgumentException("Rule string cannot be empty");
        }
        String trimmed = ruleString.trim();
        int parenStart = trimmed.indexOf('(');
        if (parenStart < 0) {
            // 无括号：纯工具名
            return new PermissionRule(trimmed, null);
        }
        // 有括号：提取工具名和 specifier
        if (!trimmed.endsWith(")")) {
            throw new IllegalArgumentException("Invalid rule format, missing closing ')': " + ruleString);
        }
        String toolName = trimmed.substring(0, parenStart);
        String specifier = trimmed.substring(parenStart + 1, trimmed.length() - 1);
        return new PermissionRule(toolName, specifier);
    }

    // ==================== Getter ====================

    public String getToolName() {
        return toolName;
    }

    public String getSpecifier() {
        return specifier;
    }

    @Override
    public String toString() {
        if (specifier == null || specifier.isEmpty()) {
            return toolName;
        }
        return toolName + "(" + specifier + ")";
    }
}

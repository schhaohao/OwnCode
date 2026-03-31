package com.claudecode.permission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Function;

/**
 * PermissionManager — 权限管理器
 *
 * 核心职责：
 *   在工具执行前评估是否允许该操作，保护用户的文件系统安全。
 *   这是 "人在回路中(Human-in-the-loop)" 安全模式的核心实现。
 *
 * 权限评估三种结果：
 *   - ALLOW: 直接允许，无需提示用户（如只读工具 Read/Glob/Grep）
 *   - DENY:  直接拒绝（如匹配了 deny 规则）
 *   - ASK:   需要终端提示用户确认（如 Bash 命令、文件修改等）
 *
 * 评估流程：
 *   1. 检查 deny 规则 → 匹配则直接 DENY
 *   2. 检查 allow 规则 → 匹配则直接 ALLOW
 *   3. 检查工具的 requiresPermission()：
 *      - false → ALLOW（只读工具）
 *      - true  → ASK（需要用户审批）
 *   4. 检查会话记忆（用户之前批准过同类操作）→ ALLOW 或 ASK
 *
 * 需要实现的方法：
 *
 * 1. public PermissionResult evaluate(String toolName, Map<String, Object> input)
 *    - 执行上述评估流程
 *    - 返回 PermissionResult 枚举: ALLOW, DENY, ASK
 *
 * 2. public void addAllowRule(PermissionRule rule)
 *    - 添加一条 allow 规则
 *    - 如: addAllowRule(new PermissionRule("Bash", "npm run *"))
 *
 * 3. public void addDenyRule(PermissionRule rule)
 *    - 添加一条 deny 规则
 *    - 如: addDenyRule(new PermissionRule("Bash", "rm -rf *"))
 *
 * 4. public void rememberApproval(String toolName, String pattern)
 *    - 记住用户的批准决定（会话内有效）
 *    - 当用户批准 "Bash(git commit *)" 后，后续同类操作不再询问
 *
 * 5. public boolean promptUser(String toolName, Map<String, Object> input)
 *    - 在终端提示用户审批（evaluate 返回 ASK 时调用）
 *    - 显示工具名和参数，等待用户输入 y/n
 *    - 格式示例：
 *      ┌──────────────────────────────────────┐
 *      │ Tool: Bash                           │
 *      │ Command: npm install express         │
 *      │                                      │
 *      │ Allow? (y)es / (n)o / (a)lways       │
 *      └──────────────────────────────────────┘
 *    - 用户输入 'a' 时调用 rememberApproval()
 *    - 返回 true=允许，false=拒绝
 *
 * 内部枚举：
 *   enum PermissionResult { ALLOW, DENY, ASK }
 *
 * 简化版（P0 阶段建议）：
 *   不加载规则文件，直接根据 tool.requiresPermission() 决定是否 ASK
 *   后续再加 deny/allow 规则和会话记忆
 */
public class PermissionManager {

    // ==================== 权限评估结果枚举 ====================

    public enum PermissionResult {
        /** 直接允许，无需提示用户（如只读工具、匹配 allow 规则） */
        ALLOW,
        /** 直接拒绝（匹配 deny 规则） */
        DENY,
        /** 需要终端提示用户确认 */
        ASK
    }

    /** deny 规则列表（优先级最高） */
    private final List<PermissionRule> denyRules = new ArrayList<>();

    /** allow 规则列表 */
    private final List<PermissionRule> allowRules = new ArrayList<>();

    /** 会话级别的批准记忆：用户选择 "always" 后记住的工具名 */
    private final Set<String> approvedTools = new HashSet<>();

    /** 会话级别的细粒度批准规则 */
    private final List<PermissionRule> approvalRules = new ArrayList<>();

    /** 终端输入回调（接收提示文本，返回用户输入行） */
    private Function<String, String> inputReader;

    public PermissionManager() {
        // 默认使用 Scanner，可通过 setInputReader 注入 JLine 的实现
        this.inputReader = prompt -> {
            System.out.print(prompt);
            return new Scanner(System.in).nextLine();
        };
    }

    /**
     * 注入自定义输入读取方式（如 JLine LineReader）
     * 解决 Scanner(System.in) 与 JLine raw mode 冲突问题
     */
    public void setInputReader(Function<String, String> inputReader) {
        this.inputReader = inputReader;
    }

    // ==================== 核心评估方法 ====================

    /**
     * 评估工具调用是否允许执行
     *
     * 评估流程（按优先级）：
     *   1. 检查 deny 规则 → 匹配则 DENY
     *   2. 检查 allow 规则 → 匹配则 ALLOW
     *   3. 检查会话记忆（用户之前选了 always）→ 匹配则 ALLOW
     *   4. 不需要权限的工具 → ALLOW
     *   5. 以上都不匹配 → ASK
     *
     * @param toolName           工具名称
     * @param input              工具输入参数
     * @param requiresPermission 该工具是否需要权限审批（来自 Tool.requiresPermission()）
     */
    public PermissionResult evaluate(String toolName, Map<String, Object> input, boolean requiresPermission) {
        // 1. deny 规则优先级最高
        for (PermissionRule rule : denyRules) {
            if (rule.matches(toolName, input)) {
                return PermissionResult.DENY;
            }
        }

        // 2. allow 规则
        for (PermissionRule rule : allowRules) {
            if (rule.matches(toolName, input)) {
                return PermissionResult.ALLOW;
            }
        }

        // 3. 会话记忆：工具名级别
        if (approvedTools.contains(toolName)) {
            return PermissionResult.ALLOW;
        }

        // 3.5 会话记忆：细粒度规则级别
        for (PermissionRule rule : approvalRules) {
            if (rule.matches(toolName, input)) {
                return PermissionResult.ALLOW;
            }
        }

        // 4. 只读工具不需要权限
        if (!requiresPermission) {
            return PermissionResult.ALLOW;
        }

        // 5. 需要用户审批
        return PermissionResult.ASK;
    }

    /**
     * 简化版 evaluate — 默认视为需要权限的工具
     */
    public PermissionResult evaluate(String toolName, Map<String, Object> input) {
        return evaluate(toolName, input, true);
    }

    // ==================== 规则管理 ====================

    public void addAllowRule(PermissionRule rule) {
        allowRules.add(rule);
    }

    public void addDenyRule(PermissionRule rule) {
        denyRules.add(rule);
    }

    /**
     * 记住用户的批准决定（会话内有效）
     *
     * @param toolName 工具名称
     * @param pattern  参数匹配模式（null 表示批准该工具的所有调用）
     */
    public void rememberApproval(String toolName, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            // 粗粒度：批准该工具的所有调用
            approvedTools.add(toolName);
        } else {
            // 细粒度：只批准匹配 pattern 的调用
            approvalRules.add(new PermissionRule(toolName, pattern));
        }
    }

    // ==================== 用户交互 ====================

    /**
     * 在终端提示用户审批工具调用
     *
     * 当 evaluate() 返回 ASK 时由 AgentLoop 调用。
     * 显示工具名和关键参数，等待用户输入 y/n/a。
     *
     * @return true=允许执行，false=拒绝
     */
    public boolean promptUser(String toolName, Map<String, Object> input) {
        String detail = extractDisplayDetail(toolName, input);

        // 显示审批提示框
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.printf( "│  Tool: %-38s│%n", toolName);
        if (detail != null) {
            // 如果 detail 太长则截断显示
            String displayDetail = detail.length() > 36 ? detail.substring(0, 33) + "..." : detail;
            System.out.printf("│  %s: %-*s│%n", getDetailLabel(toolName),
                    38 - getDetailLabel(toolName).length() - 2, displayDetail);
        }
        System.out.println("│                                              │");
        System.out.println("│  Allow? (y)es / (n)o / (a)lways             │");
        System.out.println("└──────────────────────────────────────────────┘");

        String answer = inputReader.apply("  > ").trim().toLowerCase();

        switch (answer) {
            case "y":
            case "yes":
                return true;

            case "a":
            case "always":
                // 记住批准：后续同工具调用不再询问
                rememberApproval(toolName, null);
                return true;

            case "n":
            case "no":
            default:
                return false;
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 根据工具类型提取用于显示的关键参数值
     */
    private String extractDisplayDetail(String toolName, Map<String, Object> input) {
        if (input == null) return null;
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
                return input.toString();
        }
    }

    /**
     * 根据工具类型返回显示标签
     */
    private String getDetailLabel(String toolName) {
        switch (toolName) {
            case "Bash":   return "Command";
            case "Read":
            case "Edit":
            case "Write":  return "Path";
            case "Glob":
            case "Grep":   return "Pattern";
            default:       return "Input";
        }
    }

    private String getStringValue(Map<String, Object> input, String key) {
        Object val = input.get(key);
        return val instanceof String ? (String) val : null;
    }
}

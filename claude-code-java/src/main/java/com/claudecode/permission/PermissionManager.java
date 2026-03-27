package com.claudecode.permission;

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

    // TODO: 实现
}

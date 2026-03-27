package com.claudecode.permission;

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

    // TODO: 实现
}

package com.claudecode.tool.impl;

/**
 * GlobTool — 文件名模式搜索工具
 *
 * 功能：根据 glob 模式搜索文件路径，返回匹配的文件列表。
 * 只读操作，不需要权限审批。
 *
 * 工具定义：
 * - name: "Glob"
 * - requiresPermission: false
 * - inputSchema:
 *   {
 *     "type": "object",
 *     "properties": {
 *       "pattern": {
 *         "type": "string",
 *         "description": "glob 模式，如 '**\/*.java', 'src/**\/*.ts'"
 *       },
 *       "path": {
 *         "type": "string",
 *         "description": "搜索的根目录，默认为当前工作目录"
 *       }
 *     },
 *     "required": ["pattern"]
 *   }
 *
 * execute() 实现思路：
 *
 * 1. 从 input 中获取 "pattern" 和 "path"（可选，默认当前目录）
 *
 * 2. 使用 Java NIO 的 glob 支持遍历文件树：
 *
 *    方案A — Files.walkFileTree + PathMatcher（推荐）：
 *    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
 *    Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
 *        @Override
 *        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
 *            if (matcher.matches(rootPath.relativize(file))) {
 *                results.add(file.toString());
 *            }
 *            return FileVisitResult.CONTINUE;
 *        }
 *    });
 *
 *    方案B — Files.newDirectoryStream（仅支持单层目录，不支持 **）
 *
 * 3. 结果排序：
 *    - 按最后修改时间降序排列（最近修改的文件在前面）
 *    - 这样 LLM 更容易找到最近改动的相关文件
 *
 * 4. 结果限制：
 *    - 最多返回 200 个匹配结果，超出则截断并提示
 *    - 每个结果一行，格式为文件的绝对路径
 *
 * 5. 返回：
 *    - 有匹配: ToolResult.success(匹配文件列表，每行一个路径)
 *    - 无匹配: ToolResult.success("No files matched pattern: " + pattern)
 *
 * glob 模式速查：
 * - *      匹配任意文件名（不跨目录）
 * - **     匹配任意层级的目录
 * - ?      匹配单个字符
 * - [abc]  匹配方括号中的任一字符
 * - {a,b}  匹配花括号中的任一模式
 * 示例：
 * - "**\/*.java"          → 所有 Java 文件
 * - "src/main/**\/*.xml"  → src/main 下所有 XML 文件
 * - "*.{java,xml}"       → 当前目录的 Java 和 XML 文件
 */
public class GlobTool implements com.claudecode.tool.Tool {
    @Override
    public String name() {
        return "";
    }

    @Override
    public String description() {
        return "";
    }

    @Override
    public java.util.Map<String, Object> inputSchema() {
        return java.util.Map.of();
    }

    @Override
    public boolean requiresPermission() {
        return false;
    }

    @Override
    public com.claudecode.tool.ToolResult execute(java.util.Map<String, Object> input) {
        return null;
    }

    // TODO: 实现 Tool 接口的所有方法
}

package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

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
public class GlobTool implements Tool {

    /** Maximum number of results to return */
    private static final int MAX_RESULTS = 200;

    /** Directories to skip during search (与 GrepTool 保持一致) */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", "__pycache__", ".gradle");

    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return "Fast file pattern matching tool that works with any codebase size. "
                + "Supports glob patterns like '**/*.java' or 'src/**/*.ts'. "
                + "Returns matching file paths sorted by modification time (most recent first). "
                + "Use this when you need to find files by name or extension.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> patternProp = new LinkedHashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "Glob pattern, e.g. '**/*.java', 'src/**/*.xml'");

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "Root directory to search in (default: current working directory)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", patternProp);
        properties.put("path", pathProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public boolean requiresPermission() {
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String pattern = (String) input.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("Parameter 'pattern' is required");
        }

        String pathStr = (String) input.get("path");
        Path rootPath;
        try {
            rootPath = pathStr != null ? Paths.get(pathStr) : Paths.get(System.getProperty("user.dir"));
            if (!Files.isDirectory(rootPath)) {
                return ToolResult.error("Path is not a directory: " + rootPath);
            }
        } catch (Exception e) {
            return ToolResult.error("Invalid path: " + e.getMessage());
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> matches = new ArrayList<>();

            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip common non-interesting directories
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (SKIP_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = rootPath.relativize(file);
                    if (matcher.matches(relative)) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // Skip unreadable files
                }
            });

            if (matches.isEmpty()) {
                return ToolResult.success("No files matched pattern: " + pattern);
            }

            // Sort by last modified time (most recent first)
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });

            // Build result string
            StringBuilder sb = new StringBuilder();
            int count = Math.min(matches.size(), MAX_RESULTS);
            for (int i = 0; i < count; i++) {
                sb.append(matches.get(i).toString()).append("\n");
            }
            if (matches.size() > MAX_RESULTS) {
                sb.append("... and ").append(matches.size() - MAX_RESULTS).append(" more files");
            }

            return ToolResult.success(sb.toString().trim());

        } catch (Exception e) {
            return ToolResult.error("Glob search failed: " + e.getMessage());
        }
    }
}

package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GrepTool — 文件内容搜索工具
 *
 * 功能：在文件内容中搜索匹配正则表达式的行，类似 grep/ripgrep。
 * 只读操作，不需要权限审批。
 *
 * 工具定义：
 * - name: "Grep"
 * - requiresPermission: false
 * - inputSchema:
 *   {
 *     "type": "object",
 *     "properties": {
 *       "pattern": {
 *         "type": "string",
 *         "description": "正则表达式搜索模式"
 *       },
 *       "path": {
 *         "type": "string",
 *         "description": "搜索的目录或文件路径，默认为当前工作目录"
 *       },
 *       "glob": {
 *         "type": "string",
 *         "description": "文件过滤模式，如 '*.java', '*.{ts,tsx}'"
 *       }
 *     },
 *     "required": ["pattern"]
 *   }
 *
 * execute() 实现思路：
 *
 * 1. 从 input 中获取参数
 *
 * 2. 确定搜索范围：
 *    - 如果 path 是文件：只搜索这个文件
 *    - 如果 path 是目录：递归搜索目录下所有文件
 *    - 如果提供了 glob：只搜索匹配 glob 模式的文件
 *
 * 3. 遍历文件并搜索：
 *    Pattern regex = Pattern.compile(pattern);  // 编译正则
 *    对每个文件：
 *      a. 跳过二进制文件（检测方法见下方）
 *      b. 逐行读取
 *      c. 如果行匹配 regex.matcher(line).find()，记录结果
 *      d. 结果格式："文件路径:行号:匹配的行内容"
 *
 * 4. 跳过不该搜索的内容：
 *    - .git 目录
 *    - node_modules 目录
 *    - target / build 目录
 *    - 二进制文件（简单判断：前 8000 字节中是否包含 \0）
 *    - 超大文件（如 > 1MB）
 *
 * 5. 结果限制：
 *    - 最多返回 500 个匹配行
 *    - 超出时截断并提示 "... and N more matches"
 *
 * 6. 返回格式示例：
 *    src/main/java/com/example/App.java:15:    public static void main(String[] args) {
 *    src/main/java/com/example/App.java:23:    public void processArgs(String[] args) {
 *    src/test/java/com/example/AppTest.java:8:    void testMain() {
 *
 * 性能优化建议：
 * - 使用 Files.walkFileTree 而不是 Files.walk（更可控）
 * - 遇到超大文件可以只读取前 N 行
 * - 简单实现：先用纯 Java 实现；进阶：可以直接调用系统的 grep 或 rg 命令
 *
 * 进阶方案（更简单的实现）：
 *   直接通过 ProcessBuilder 调用系统的 grep 或 rg 命令，
 *   解析它们的输出即可。这样性能更好且自动处理了二进制文件跳过等问题。
 *   但注意不同系统上的可用性差异。
 */
public class GrepTool implements Tool {

    /** Maximum number of matching lines to return */
    private static final int MAX_MATCHES = 500;

    /** Skip files larger than 1MB */
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    /** Directories to skip during search */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", "__pycache__", ".gradle");

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return "Searches file contents using regex patterns, similar to ripgrep. "
                + "Returns matching lines with file paths and line numbers. "
                + "Supports filtering by file glob pattern. "
                + "Use this when you need to search for code patterns, function definitions, "
                + "or specific text across the codebase.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> patternProp = new LinkedHashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "Regex pattern to search for");

        Map<String, Object> pathProp = new LinkedHashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "File or directory to search in (default: current working directory)");

        Map<String, Object> globProp = new LinkedHashMap<>();
        globProp.put("type", "string");
        globProp.put("description", "File filter pattern, e.g. '*.java', '*.{ts,tsx}'");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", patternProp);
        properties.put("path", pathProp);
        properties.put("glob", globProp);

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
        String patternStr = (String) input.get("pattern");
        if (patternStr == null || patternStr.isBlank()) {
            return ToolResult.error("Parameter 'pattern' is required");
        }

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (Exception e) {
            return ToolResult.error("Invalid regex pattern: " + e.getMessage());
        }

        String pathStr = (String) input.get("path");
        Path searchPath;
        try {
            searchPath = pathStr != null ? Paths.get(pathStr) : Paths.get(System.getProperty("user.dir"));
        } catch (Exception e) {
            return ToolResult.error("Invalid path: " + e.getMessage());
        }

        String globPattern = (String) input.get("glob");
        PathMatcher globMatcher = globPattern != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + globPattern)
                : null;

        List<String> results = new ArrayList<>();

        try {
            if (Files.isRegularFile(searchPath)) {
                // Search single file
                searchFile(searchPath, searchPath.getParent(), regex, results);
            } else if (Files.isDirectory(searchPath)) {
                // Recursive search
                Path root = searchPath;
                Files.walkFileTree(searchPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (SKIP_DIRS.contains(dirName)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (results.size() >= MAX_MATCHES) {
                            return FileVisitResult.TERMINATE;
                        }
                        // Skip large files
                        if (attrs.size() > MAX_FILE_SIZE) {
                            return FileVisitResult.CONTINUE;
                        }
                        // Apply glob filter
                        if (globMatcher != null && !globMatcher.matches(root.relativize(file))) {
                            return FileVisitResult.CONTINUE;
                        }
                        searchFile(file, root, regex, results);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                return ToolResult.error("Path not found: " + searchPath);
            }
        } catch (Exception e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success("No matches found for pattern: " + patternStr);
        }

        StringBuilder sb = new StringBuilder();
        int count = Math.min(results.size(), MAX_MATCHES);
        for (int i = 0; i < count; i++) {
            sb.append(results.get(i)).append("\n");
        }
        if (results.size() > MAX_MATCHES) {
            sb.append("... and more matches (truncated at ").append(MAX_MATCHES).append(" results)");
        }

        return ToolResult.success(sb.toString().trim());
    }

    /**
     * Search a single file for regex matches
     */
    private void searchFile(Path file, Path root, Pattern regex, List<String> results) {
        // Skip binary files by checking the file extension
        String fileName = file.getFileName().toString();
        if (isBinaryExtension(fileName)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String relativePath = root.relativize(file).toString();
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (results.size() >= MAX_MATCHES) break;

                Matcher m = regex.matcher(line);
                if (m.find()) {
                    results.add(relativePath + ":" + lineNum + ":" + line);
                }
            }
        } catch (Exception e) {
            // Skip files that can't be read as text (binary files, encoding issues)
        }
    }

    private boolean isBinaryExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".class") || lower.endsWith(".jar") || lower.endsWith(".zip")
                || lower.endsWith(".gz") || lower.endsWith(".tar") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".ico") || lower.endsWith(".pdf") || lower.endsWith(".exe")
                || lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf");
    }
}

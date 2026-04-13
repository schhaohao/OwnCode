package com.claudecode.memory.model;

import com.claudecode.memory.util.FrontmatterParser;
import com.claudecode.memory.util.PathSanitizer;

import java.util.Map;

/**
 * MemoryEntry — 单条持久化记忆的完整模型。
 *
 * <p>该类对应 memory 目录中的单个 Markdown 文件，结构遵循：</p>
 *
 * <pre>
 * ---
 * name: Testing Feedback
 * description: 用户要求集成测试使用真实数据库而非 mock
 * type: feedback
 * ---
 *
 * 集成测试必须使用真实数据库，不要用 mock。
 * </pre>
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>内存中可以方便操作字段</li>
 *   <li>磁盘上保持纯 Markdown，可读、可 diff、可人工编辑</li>
 *   <li>解析逻辑集中，避免业务代码到处拼 frontmatter</li>
 * </ul>
 */
public class MemoryEntry {

    private final String name;
    private final String description;
    private final MemoryType type;
    private final String content;
    private final String fileName;
    private final long lastModified;

    public MemoryEntry(String name,
                       String description,
                       MemoryType type,
                       String content,
                       String fileName,
                       long lastModified) {
        this.name = defaultString(name, "Untitled Memory");
        this.description = defaultString(description, "");
        this.type = type != null ? type : MemoryType.PROJECT;
        this.content = content != null ? content.trim() : "";
        this.fileName = normalizeFileName(fileName, this.name, this.type);
        this.lastModified = lastModified;
    }

    /**
     * 将当前记忆条目序列化为包含 YAML frontmatter 的 Markdown 文本。
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escapeYaml(name)).append("\n");
        sb.append("description: ").append(escapeYaml(description)).append("\n");
        sb.append("type: ").append(type.toFrontmatterValue()).append("\n");
        sb.append("---\n\n");
        if (!content.isEmpty()) {
            sb.append(content);
            if (!content.endsWith("\n")) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从磁盘上的 Markdown 内容恢复一个 MemoryEntry。
     *
     * @param fileName      文件名
     * @param rawMarkdown   原始 Markdown 文本
     * @param lastModified  文件最后修改时间
     * @return 解析后的记忆条目
     */
    public static MemoryEntry fromMarkdown(String fileName, String rawMarkdown, long lastModified) {
        FrontmatterParser.ParsedFrontmatter parsed = FrontmatterParser.parse(rawMarkdown);
        Map<String, Object> metadata = parsed.getAttributes();

        String name = asString(metadata.get("name"));
        String description = asString(metadata.get("description"));
        MemoryType type = MemoryType.fromFrontmatter(asString(metadata.get("type")));

        return new MemoryEntry(
                defaultString(name, fileName),
                defaultString(description, ""),
                type,
                parsed.getBody().trim(),
                fileName,
                lastModified
        );
    }

    /**
     * 便捷重载：当没有 lastModified 信息时，默认填 0。
     */
    public static MemoryEntry fromMarkdown(String fileName, String rawMarkdown) {
        return fromMarkdown(fileName, rawMarkdown, 0L);
    }

    private static String normalizeFileName(String fileName, String name, MemoryType type) {
        if (fileName != null && !fileName.trim().isEmpty()) {
            return ensureMarkdownSuffix(fileName.trim());
        }

        String slug = PathSanitizer.slugify(type.toFrontmatterValue() + "-" + name);
        if (slug.isEmpty()) {
            slug = "memory";
        }
        return ensureMarkdownSuffix(slug);
    }

    private static String ensureMarkdownSuffix(String fileName) {
        return fileName.endsWith(".md") ? fileName : fileName + ".md";
    }

    private static String escapeYaml(String value) {
        String normalized = value != null ? value : "";
        if (normalized.contains(":") || normalized.contains("#") || normalized.contains("\"")) {
            return "\"" + normalized.replace("\"", "\\\"") + "\"";
        }
        return normalized;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public MemoryType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getFileName() {
        return fileName;
    }

    public long getLastModified() {
        return lastModified;
    }
}

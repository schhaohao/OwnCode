package com.claudecode.memory.model;

/**
 * MemorySummary — 记忆索引级别的轻量摘要。
 *
 * <p>持久化记忆检索分两步：</p>
 * <ol>
 *   <li>先只读取索引或 frontmatter，得到轻量摘要列表</li>
 *   <li>再从候选中挑选少数相关条目读取全文</li>
 * </ol>
 *
 * <p>这个类承载的就是第一步的结果。它刻意不包含正文全文，
 * 这样扫描记忆目录时可以做到足够轻量。</p>
 */
public class MemorySummary {

    private final String fileName;
    private final String name;
    private final String description;
    private final MemoryType type;
    private final long lastModified;

    public MemorySummary(String fileName,
                         String name,
                         String description,
                         MemoryType type,
                         long lastModified) {
        this.fileName = fileName;
        this.name = name;
        this.description = description;
        this.type = type;
        this.lastModified = lastModified;
    }

    public String getFileName() {
        return fileName;
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

    public long getLastModified() {
        return lastModified;
    }
}

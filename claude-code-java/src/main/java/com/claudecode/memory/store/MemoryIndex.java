package com.claudecode.memory.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * MemoryIndex — 管理 memory 目录中的 MEMORY.md 索引文件。
 *
 * <p>索引文件的作用不是保存完整记忆，而是提供一份足够轻量的“目录”：
 * 既方便人类查看，也方便系统快速注入一份总览到 system prompt 中。</p>
 *
 * <p>每一行格式：</p>
 * <pre>
 * - [Testing Feedback](feedback_testing.md) — 集成测试必须使用真实数据库
 * </pre>
 */
public class MemoryIndex {

    private final Path indexPath;
    private final int maxLines;
    private final int maxBytes;

    public MemoryIndex(Path indexPath, int maxLines, int maxBytes) {
        this.indexPath = indexPath;
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
    }

    /**
     * 添加或更新一条索引项。
     *
     * <p>如果 fileName 已存在则就地替换，否则追加到末尾。
     * 为了控制提示词大小，会在写回前应用行数和字节数双重裁剪。</p>
     */
    public synchronized void addOrUpdate(String fileName, String title, String hook) throws IOException {
        List<String> lines = loadLines();
        String entry = "- [" + title + "](" + fileName + ") — " + hook;

        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("(" + fileName + ")")) {
                lines.set(i, entry);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            lines.add(entry);
        }

        trimToLimits(lines);
        writeLines(lines);
    }

    /**
     * 删除指定文件对应的索引项。
     */
    public synchronized void remove(String fileName) throws IOException {
        List<String> lines = loadLines();
        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().contains("(" + fileName + ")")) {
                iterator.remove();
            }
        }
        writeLines(lines);
    }

    /**
     * 读取索引文件并按限制截断。
     *
     * <p>该方法用于 system prompt 注入，因此即便索引文件意外变大，也会在读取层面
     * 再做一次保护，防止整个请求体失控。</p>
     */
    public synchronized String loadTruncated() throws IOException {
        List<String> lines = loadLines();
        trimToLimits(lines);
        return String.join("\n", lines);
    }

    private List<String> loadLines() throws IOException {
        if (!Files.exists(indexPath)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Files.readAllLines(indexPath, StandardCharsets.UTF_8));
    }

    private void writeLines(List<String> lines) throws IOException {
        Files.createDirectories(indexPath.getParent());
        String content = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        Files.write(indexPath, content.getBytes(StandardCharsets.UTF_8));
    }

    private void trimToLimits(List<String> lines) {
        while (lines.size() > maxLines) {
            lines.remove(lines.size() - 1);
        }

        while (utf8Length(lines) > maxBytes && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    private int utf8Length(List<String> lines) {
        String joined = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        return joined.getBytes(StandardCharsets.UTF_8).length;
    }
}

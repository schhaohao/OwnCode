package com.claudecode.memory.store;

import com.claudecode.memory.model.MemoryEntry;
import com.claudecode.memory.model.MemorySummary;
import com.claudecode.memory.model.MemoryType;
import com.claudecode.memory.util.FrontmatterParser;
import com.claudecode.memory.util.PathSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PersistentMemoryStore — 持久化记忆的文件存储层。
 *
 * <p>存储布局：</p>
 * <pre>
 * ~/.claude-code-java/projects/&lt;project-slug&gt;/memory/
 * ├── MEMORY.md
 * ├── feedback_testing.md
 * └── project_auth_context.md
 * </pre>
 *
 * <p>职责拆分：</p>
 * <ul>
 *   <li>本类负责“单条记忆文件”和“目录扫描”</li>
 *   <li>{@link MemoryIndex} 负责 MEMORY.md 的增删改查</li>
 * </ul>
 */
public class PersistentMemoryStore {

    private static final int MAX_INDEX_LINES = 200;
    private static final int MAX_INDEX_BYTES = 25_000;
    private static final int MAX_FILE_LINES = 200;
    private static final int MAX_FILE_BYTES = 4 * 1024;
    private static final int MAX_SCAN_FILES = 200;
    private static final int FRONTMATTER_SCAN_LINES = 30;

    private final Path memoryDir;
    private final MemoryIndex index;

    public PersistentMemoryStore(Path projectRoot) throws IOException {
        String projectSlug = PathSanitizer.sanitizeProjectRoot(projectRoot);
        this.memoryDir = Path.of(
                System.getProperty("user.home"),
                ".claude-code-java",
                "projects",
                projectSlug,
                "memory"
        );
        Files.createDirectories(memoryDir);
        this.index = new MemoryIndex(memoryDir.resolve("MEMORY.md"), MAX_INDEX_LINES, MAX_INDEX_BYTES);
    }

    /**
     * 保存或更新一条记忆。
     *
     * <p>写入顺序固定为：</p>
     * <ol>
     *   <li>先写记忆正文文件</li>
     *   <li>再刷新 MEMORY.md 索引</li>
     * </ol>
     *
     * <p>这样做的好处是：哪怕程序在索引更新之前中断，正文文件仍然已经落盘，
     * 后续也可以通过扫描修复索引。</p>
     */
    public synchronized void save(MemoryEntry entry) throws IOException {
        Path filePath = memoryDir.resolve(entry.getFileName());
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, entry.toMarkdown().getBytes(StandardCharsets.UTF_8));
        index.addOrUpdate(entry.getFileName(), entry.getName(), entry.getDescription());
    }

    /**
     * 删除一条记忆及其索引项。
     */
    public synchronized void delete(String fileName) throws IOException {
        Files.deleteIfExists(memoryDir.resolve(fileName));
        index.remove(fileName);
    }

    /**
     * 扫描目录中的所有记忆摘要。
     *
     * <p>这里只读取每个文件的前若干行用于解析 frontmatter，不读取全文，
     * 从而把“扫描候选集”的成本压低到可接受范围。</p>
     */
    public synchronized List<MemorySummary> scan() throws IOException {
        if (!Files.exists(memoryDir)) {
            return List.of();
        }

        List<Path> files = Files.list(memoryDir)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .filter(path -> !"MEMORY.md".equals(path.getFileName().toString()))
                .sorted(Comparator.comparing(this::safeLastModified).reversed())
                .limit(MAX_SCAN_FILES)
                .collect(Collectors.toList());

        List<MemorySummary> summaries = new ArrayList<>();
        for (Path path : files) {
            summaries.add(parseFrontmatterOnly(path));
        }
        return summaries;
    }

    /**
     * 读取单条记忆全文，并按行数和字节数双重限制截断。
     */
    public synchronized String readContent(String fileName) throws IOException {
        Path file = memoryDir.resolve(fileName);
        if (!Files.exists(file)) {
            return "";
        }
        return truncate(Files.readString(file), MAX_FILE_LINES, MAX_FILE_BYTES);
    }

    /**
     * 读取并反序列化完整记忆。
     */
    public synchronized MemoryEntry readEntry(String fileName) throws IOException {
        Path file = memoryDir.resolve(fileName);
        if (!Files.exists(file)) {
            return null;
        }
        return MemoryEntry.fromMarkdown(
                fileName,
                Files.readString(file),
                safeLastModified(file)
        );
    }

    /**
     * 读取 MEMORY.md 索引，自动应用保护性截断。
     */
    public synchronized String loadIndex() throws IOException {
        return index.loadTruncated();
    }

    /**
     * 暴露记忆目录路径，便于测试和调试。
     */
    public Path getMemoryDir() {
        return memoryDir;
    }

    private MemorySummary parseFrontmatterOnly(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int lineCount = Math.min(lines.size(), FRONTMATTER_SCAN_LINES);
        StringBuilder snippet = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) {
                snippet.append("\n");
            }
            snippet.append(lines.get(i));
        }

        FrontmatterParser.ParsedFrontmatter parsed = FrontmatterParser.parse(snippet.toString());
        Map<String, Object> metadata = parsed.getAttributes();

        String fileName = path.getFileName().toString();
        String name = stringOrDefault(metadata.get("name"), fileName);
        String description = stringOrDefault(metadata.get("description"), "");
        MemoryType type = MemoryType.fromFrontmatter(stringOrDefault(metadata.get("type"), "project"));

        return new MemorySummary(fileName, name, description, type, safeLastModified(path));
    }

    private String truncate(String raw, int maxLines, int maxBytes) {
        String safeRaw = raw != null ? raw : "";
        String[] lines = safeRaw.split("\\R", -1);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length && i < maxLines; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }

        String lineLimited = sb.toString();
        byte[] bytes = lineLimited.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return lineLimited;
        }

        String truncated = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
        return truncated + "\n...(truncated)";
    }

    private long safeLastModified(Path path) {
        try {
            FileTime fileTime = Files.getLastModifiedTime(path);
            return fileTime.toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String stringOrDefault(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}

package com.claudecode.memory.store;

import com.claudecode.memory.model.MemoryEntry;
import com.claudecode.memory.model.MemorySummary;
import com.claudecode.memory.model.MemoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PersistentMemoryStore 单元测试。
 */
class PersistentMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void save_shouldWriteMemoryFileAndUpdateIndex() throws IOException {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            PersistentMemoryStore store = new PersistentMemoryStore(tempDir.resolve("demo-project"));
            MemoryEntry entry = new MemoryEntry(
                    "Testing Feedback",
                    "集成测试必须使用真实数据库",
                    MemoryType.FEEDBACK,
                    "集成测试必须使用真实数据库，不要使用 mock。",
                    "feedback_testing.md",
                    0L
            );

            store.save(entry);

            Path writtenFile = store.getMemoryDir().resolve("feedback_testing.md");
            assertTrue(writtenFile.toFile().isFile());

            String index = store.loadIndex();
            assertTrue(index.contains("[Testing Feedback](feedback_testing.md)"));
            assertTrue(index.contains("集成测试必须使用真实数据库"));

            MemoryEntry loaded = store.readEntry("feedback_testing.md");
            assertNotNull(loaded);
            assertEquals("Testing Feedback", loaded.getName());
            assertEquals(MemoryType.FEEDBACK, loaded.getType());
            assertTrue(loaded.getContent().contains("真实数据库"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void scan_shouldReadFrontmatterSummaryWithoutLoadingWholeFile() throws IOException {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            PersistentMemoryStore store = new PersistentMemoryStore(tempDir.resolve("demo-project"));
            store.save(new MemoryEntry(
                    "User Role",
                    "用户是高级后端工程师",
                    MemoryType.USER,
                    "用户主要做 Java 后端，对前端不熟。",
                    "user_role.md",
                    0L
            ));

            List<MemorySummary> summaries = store.scan();

            assertEquals(1, summaries.size());
            MemorySummary summary = summaries.get(0);
            assertEquals("user_role.md", summary.getFileName());
            assertEquals("User Role", summary.getName());
            assertEquals(MemoryType.USER, summary.getType());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}

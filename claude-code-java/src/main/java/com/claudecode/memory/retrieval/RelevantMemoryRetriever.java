package com.claudecode.memory.retrieval;

import com.claudecode.memory.model.MemoryEntry;
import com.claudecode.memory.model.MemorySummary;
import com.claudecode.memory.store.PersistentMemoryStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RelevantMemoryRetriever — 从持久化记忆中挑选“当前最值得注入”的条目。
 *
 * <p>完整版 Claude Code 会借助轻量模型做 side query 选择。这里先采用
 * 关键词打分策略，优点是：</p>
 * <ul>
 *   <li>零额外 API 调用</li>
 *   <li>行为稳定，便于测试</li>
 *   <li>后续若要替换为 LLM 选择器，调用方接口不需要变化</li>
 * </ul>
 */
public class RelevantMemoryRetriever {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_TOTAL_BYTES = 60 * 1024;

    private final PersistentMemoryStore store;
    private final Set<String> surfacedPaths = new HashSet<>();

    public RelevantMemoryRetriever(PersistentMemoryStore store) {
        this.store = store;
    }

    /**
     * 开启新一轮用户请求前重置去重状态。
     *
     * <p>同一轮工具循环中我们不希望重复注入同一条记忆；但进入下一轮用户请求后，
     * 同一条记忆仍然可能再次 relevant，因此这里按“轮”而不是“整个进程”去重。</p>
     */
    public void resetTurnScope() {
        surfacedPaths.clear();
    }

    /**
     * 依据用户查询挑选相关记忆。
     */
    public List<MemoryEntry> retrieve(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        List<MemorySummary> candidates = store.scan();
        if (candidates.isEmpty()) {
            return List.of();
        }

        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String[] keywords = normalizedQuery.split("\\s+");

        List<ScoredMemory> scored = new ArrayList<>();
        for (MemorySummary candidate : candidates) {
            if (surfacedPaths.contains(candidate.getFileName())) {
                continue;
            }

            int score = score(candidate, keywords);
            if (score > 0) {
                scored.add(new ScoredMemory(candidate, score));
            }
        }

        scored.sort(Comparator.comparingInt(ScoredMemory::getScore).reversed()
                .thenComparing(sm -> sm.getSummary().getLastModified(), Comparator.reverseOrder()));

        List<MemoryEntry> results = new ArrayList<>();
        int totalBytes = 0;
        for (ScoredMemory item : scored) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }

            MemoryEntry entry = store.readEntry(item.getSummary().getFileName());
            if (entry == null) {
                continue;
            }

            int entryBytes = entry.toMarkdown().length();
            if (totalBytes + entryBytes > MAX_TOTAL_BYTES) {
                break;
            }

            totalBytes += entryBytes;
            surfacedPaths.add(entry.getFileName());
            results.add(entry);
        }
        return results;
    }

    private int score(MemorySummary summary, String[] keywords) {
        String haystack = (summary.getName() + " " + summary.getDescription() + " " + summary.getType().name())
                .toLowerCase(Locale.ROOT);

        int score = 0;
        for (String keyword : keywords) {
            String token = keyword.trim();
            if (token.length() < 2) {
                continue;
            }
            if (haystack.contains(token)) {
                score += 3;
            }
        }

        // 用户反馈类和项目类通常对 coding agent 更高价值，给一个轻微偏置。
        switch (summary.getType()) {
            case FEEDBACK:
                score += 2;
                break;
            case PROJECT:
                score += 1;
                break;
            default:
                break;
        }
        return score;
    }

    private static class ScoredMemory {
        private final MemorySummary summary;
        private final int score;

        private ScoredMemory(MemorySummary summary, int score) {
            this.summary = summary;
            this.score = score;
        }

        public MemorySummary getSummary() {
            return summary;
        }

        public int getScore() {
            return score;
        }
    }
}

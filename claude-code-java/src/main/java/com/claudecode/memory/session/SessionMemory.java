package com.claudecode.memory.session;

import com.claudecode.api.model.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionMemory — 单会话生命周期内的结构化摘要。
 *
 * <p>它不是跨会话持久化的“长期记忆”，而是为了在当前会话变长时，
 * 可以把早期历史替换成一份结构化摘要，减少上下文占用。</p>
 *
 * <p>触发逻辑遵循调研文档的简化版本：</p>
 * <ul>
 *   <li>首次提取：会话达到 10K tokens</li>
 *   <li>后续更新：自上次提取后新增 5K tokens 且累计至少 3 次 tool 调用</li>
 * </ul>
 */
public class SessionMemory {

    private static final int INIT_TOKEN_THRESHOLD = 10_000;
    private static final int UPDATE_TOKEN_DELTA = 5_000;
    private static final int UPDATE_TOOL_CALL_DELTA = 3;
    private static final int MAX_SECTION_CHARS = 2_000;

    private final Map<String, String> sections = new LinkedHashMap<>();

    private int lastExtractTokenCount;
    private int toolCallsSinceLastExtract;
    private boolean initialized;
    private boolean extractionInProgress;

    public SessionMemory() {
        sections.put("Session Title", "");
        sections.put("Current State", "");
        sections.put("Task Specification", "");
        sections.put("Files and Functions", "");
        sections.put("Workflow", "");
        sections.put("Errors & Corrections", "");
        sections.put("Key Results", "");
        sections.put("Worklog", "");
        sections.put("Latest User Intent", "");
    }

    /**
     * 标记发生过一次工具调用。
     */
    public synchronized void onToolCall() {
        toolCallsSinceLastExtract++;
    }

    /**
     * 判断是否应该触发提取，并在返回 true 的同时占用“进行中”标记，
     * 防止同一时刻发起多个重复提取任务。
     */
    public synchronized boolean shouldExtract(int currentTokenCount) {
        if (extractionInProgress) {
            return false;
        }

        if (!initialized) {
            if (currentTokenCount >= INIT_TOKEN_THRESHOLD) {
                initialized = true;
                extractionInProgress = true;
                return true;
            }
            return false;
        }

        int delta = currentTokenCount - lastExtractTokenCount;
        if (delta >= UPDATE_TOKEN_DELTA && toolCallsSinceLastExtract >= UPDATE_TOOL_CALL_DELTA) {
            extractionInProgress = true;
            return true;
        }
        return false;
    }

    /**
     * 使用提取器刷新结构化摘要。
     */
    public void refresh(List<Message> messages, SessionMemoryExtractor extractor, int currentTokenCount) {
        try {
            SessionMemorySnapshot snapshot = extractor.extract(messages);
            synchronized (this) {
                for (Map.Entry<String, String> entry : snapshot.getSections().entrySet()) {
                    sections.put(entry.getKey(), clip(entry.getValue(), MAX_SECTION_CHARS));
                }
                lastExtractTokenCount = currentTokenCount;
                toolCallsSinceLastExtract = 0;
            }
        } finally {
            synchronized (this) {
                extractionInProgress = false;
            }
        }
    }

    /**
     * 生成可直接注入压缩消息中的结构化摘要文本。
     */
    public synchronized String toSummaryText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("# ").append(entry.getKey()).append("\n");
            sb.append(entry.getValue());
        }
        return sb.toString();
    }

    public synchronized boolean hasSummary() {
        return !toSummaryText().isBlank();
    }

    private String clip(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }
}

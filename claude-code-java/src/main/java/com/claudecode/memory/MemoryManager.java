package com.claudecode.memory;

import com.claudecode.api.ClaudeApiClient;
import com.claudecode.api.model.Message;
import com.claudecode.core.ContextManager;
import com.claudecode.core.ConversationHistory;
import com.claudecode.memory.compact.ClaudeConversationSummaryGenerator;
import com.claudecode.memory.compact.ConversationSummaryGenerator;
import com.claudecode.memory.compact.RuleBasedConversationSummaryGenerator;
import com.claudecode.memory.instruction.InstructionLoader;
import com.claudecode.memory.model.MemoryEntry;
import com.claudecode.memory.retrieval.RelevantMemoryRetriever;
import com.claudecode.memory.session.SessionMemory;
import com.claudecode.memory.session.SessionMemoryExtractor;
import com.claudecode.memory.store.PersistentMemoryStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * MemoryManager — 记忆系统门面。
 *
 * <p>对外只暴露“主循环需要关心的动作”，把底层的存储、检索、会话摘要、
 * 指令加载和上下文压缩都收口到这一层：</p>
 *
 * <ul>
 *   <li>构建动态 system prompt</li>
 *   <li>在 API 调用前进行压缩</li>
 *   <li>记录工具调用并周期性刷新 session memory</li>
 *   <li>暴露持久化记忆的保存/删除能力</li>
 * </ul>
 */
public class MemoryManager {

    private final Path projectRoot;
    private final PersistentMemoryStore persistentStore;
    private final RelevantMemoryRetriever retriever;
    private final InstructionLoader instructionLoader;
    private final SessionMemory sessionMemory;
    private final SessionMemoryExtractor sessionMemoryExtractor;
    private final ContextManager contextManager;

    public MemoryManager(Path projectRoot, ClaudeApiClient apiClient) throws IOException {
        this(projectRoot, apiClient, 200_000, 8_192);
    }

    public MemoryManager(Path projectRoot,
                         ClaudeApiClient apiClient,
                         int maxContextTokens,
                         int maxOutputTokens) throws IOException {
        this.projectRoot = projectRoot != null ? projectRoot.toAbsolutePath().normalize() : null;
        this.persistentStore = new PersistentMemoryStore(this.projectRoot);
        this.retriever = new RelevantMemoryRetriever(persistentStore);
        this.instructionLoader = new InstructionLoader();
        this.sessionMemory = new SessionMemory();
        this.sessionMemoryExtractor = new SessionMemoryExtractor();

        ConversationSummaryGenerator fallbackSummaryGenerator = new RuleBasedConversationSummaryGenerator();
        ConversationSummaryGenerator summaryGenerator = apiClient != null
                ? new ClaudeConversationSummaryGenerator(apiClient, apiClient.getDefaultModel())
                : fallbackSummaryGenerator;

        this.contextManager = new ContextManager(
                maxContextTokens,
                0.8,
                10,
                maxOutputTokens,
                sessionMemory,
                summaryGenerator,
                fallbackSummaryGenerator
        );
    }

    /**
     * 开始新一轮用户输入前的准备动作。
     */
    public void beginTurn() {
        retriever.resetTurnScope();
    }

    /**
     * 基于基础系统提示、指令文件、记忆索引和相关记忆，拼出本轮动态系统提示。
     */
    public String buildSystemPrompt(String baseSystemPrompt,
                                    String skillListing,
                                    String latestUserInput) {
        StringBuilder sb = new StringBuilder(baseSystemPrompt != null ? baseSystemPrompt : "");

        appendSection(sb, "Instruction Files", loadInstructionsSafe());
        appendSection(sb, "Memory Index", loadIndexSafe());
        appendSection(sb, "Relevant Memories", renderRelevantMemories(latestUserInput));
        appendSection(sb, "Skills", skillListing);
        return sb.toString().trim();
    }

    /**
     * 在真正发起 API 请求前执行压缩。
     */
    public void beforeApiCall(ConversationHistory history) {
        if (history != null && contextManager.isNearLimit(history)) {
            contextManager.compact(history);
        }
    }

    /**
     * 记录一次工具调用，供 session memory 的更新阈值判断使用。
     */
    public void onToolCall() {
        sessionMemory.onToolCall();
    }

    /**
     * 每轮结束后异步刷新 session memory。
     */
    public void afterTurn(ConversationHistory history) {
        if (history == null) {
            return;
        }

        int currentTokens = history.estimateTokenCount();
        if (!sessionMemory.shouldExtract(currentTokens)) {
            return;
        }

        List<Message> snapshot = new ArrayList<>(history.getMessages());
        CompletableFuture.runAsync(() -> sessionMemory.refresh(snapshot, sessionMemoryExtractor, currentTokens));
    }

    public void saveMemory(MemoryEntry entry) throws IOException {
        persistentStore.save(entry);
    }

    public void deleteMemory(String fileName) throws IOException {
        persistentStore.delete(fileName);
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    private String loadInstructionsSafe() {
        try {
            return instructionLoader.loadInstructions(projectRoot);
        } catch (IOException e) {
            return "";
        }
    }

    private String loadIndexSafe() {
        try {
            return persistentStore.loadIndex();
        } catch (IOException e) {
            return "";
        }
    }

    private String renderRelevantMemories(String latestUserInput) {
        if (latestUserInput == null || latestUserInput.trim().isEmpty()) {
            return "";
        }

        try {
            List<MemoryEntry> memories = retriever.retrieve(latestUserInput);
            if (memories.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (MemoryEntry memory : memories) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("### ").append(memory.getName()).append("\n");
                sb.append("Type: ").append(memory.getType().name().toLowerCase()).append("\n");
                if (isStale(memory)) {
                    sb.append("Note: this memory is over 1 day old, verify if it may have changed.\n");
                }
                sb.append(memory.getContent());
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isStale(MemoryEntry memory) {
        if (memory.getLastModified() <= 0) {
            return false;
        }
        Instant lastModified = Instant.ofEpochMilli(memory.getLastModified());
        return Duration.between(lastModified, Instant.now()).toHours() >= 24;
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("<system-reminder>\n");
        sb.append("## ").append(title).append("\n");
        sb.append(content.trim()).append("\n");
        sb.append("</system-reminder>");
    }
}

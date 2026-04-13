package com.claudecode.core;

import com.claudecode.api.model.ContentBlock;
import com.claudecode.api.model.Message;
import com.claudecode.api.model.ToolResultBlock;
import com.claudecode.memory.compact.ConversationSummaryGenerator;
import com.claudecode.memory.session.SessionMemory;
import com.claudecode.memory.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * ContextManager — 上下文压缩调度器。
 *
 * <p>旧版本只有“保留首条 user + 最近 N 轮”的单一截断策略。
 * 现在升级为更接近 Claude Code 调研结果的三层压缩流程：</p>
 *
 * <ol>
 *   <li>L1 微压缩：优先清理旧 tool_result 的大体积内容</li>
 *   <li>L2 Session Memory：如果已经提取过结构化会话摘要，用摘要替换早期历史</li>
 *   <li>L3 Summary Compact：必要时再将更大一段旧历史压缩为一条摘要消息</li>
 * </ol>
 *
 * <p>设计重点不是“绝对复刻官方内部实现”，而是保留它最关键的思想：
 * 先做便宜、低损耗的压缩，再做更激进的摘要替换。</p>
 */
public class ContextManager {

    private static final String TOOL_RESULT_PLACEHOLDER = "[Old tool result content cleared]";
    private static final int KEEP_RECENT_TOOL_RESULTS = 5;
    private static final int AUTOCOMPACT_BUFFER = 13_000;
    private static final int BLOCKING_BUFFER = 3_000;
    private static final int SESSION_MIN_KEEP_TOKENS = 10_000;
    private static final int SESSION_MAX_KEEP_TOKENS = 40_000;
    private static final int SESSION_MIN_TEXT_MESSAGES = 5;

    /** Claude 上下文窗口大小。 */
    private final int maxContextTokens;

    /** 压缩阈值比例，作为 auto compact 阈值的兜底值。 */
    private final double compactThreshold;

    /** L3 压缩后额外保留的近期轮次数。 */
    private final int keepRecentTurns;

    /** 输出上限，用于估算 effective window。 */
    private final int maxOutputTokens;

    /** L2 压缩依赖的结构化会话摘要，可为空。 */
    private final SessionMemory sessionMemory;

    /** 首选摘要器，通常是 Claude API。 */
    private final ConversationSummaryGenerator summaryGenerator;

    /** 兜底摘要器，通常是本地规则版。 */
    private final ConversationSummaryGenerator fallbackSummaryGenerator;

    public ContextManager() {
        this(200_000, 0.8, 10, 8_192, null, null, null);
    }

    public ContextManager(int maxContextTokens,
                          double compactThreshold,
                          int keepRecentTurns,
                          int maxOutputTokens,
                          SessionMemory sessionMemory,
                          ConversationSummaryGenerator summaryGenerator,
                          ConversationSummaryGenerator fallbackSummaryGenerator) {
        this.maxContextTokens = maxContextTokens;
        this.compactThreshold = compactThreshold;
        this.keepRecentTurns = keepRecentTurns;
        this.maxOutputTokens = maxOutputTokens;
        this.sessionMemory = sessionMemory;
        this.summaryGenerator = summaryGenerator;
        this.fallbackSummaryGenerator = fallbackSummaryGenerator;
    }

    /**
     * 判断当前历史是否已经接近上下文上限。
     */
    public boolean isNearLimit(ConversationHistory history) {
        int estimated = history.estimateTokenCount();
        return estimated >= autoCompactThreshold();
    }

    /**
     * 执行分层压缩，并直接回写到对话历史。
     *
     * <p>压缩流程是“逐层尝试、每层都重新评估大小”：</p>
     * <ol>
     *   <li>如果 L1 已经足够，就不继续做更激进的处理</li>
     *   <li>L2 只有在 session memory 已经形成后才启用</li>
     *   <li>L3 最后兜底，必要时调用模型生成摘要；失败则降级到规则摘要</li>
     * </ol>
     */
    public void compact(ConversationHistory history) {
        List<Message> original = new ArrayList<>(history.getMessages());
        if (original.size() < 3) {
            return;
        }

        List<Message> candidate = microCompact(original);
        if (TokenEstimator.estimate(candidate) <= autoCompactThreshold()) {
            history.replaceAll(candidate);
            return;
        }

        if (sessionMemory != null && sessionMemory.hasSummary()) {
            candidate = sessionMemoryCompact(candidate);
            if (TokenEstimator.estimate(candidate) <= autoCompactThreshold()) {
                history.replaceAll(candidate);
                return;
            }
        }

        candidate = summaryCompact(candidate);
        history.replaceAll(candidate);
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    /**
     * 对外暴露阻塞阈值，便于后续做“调用前直接拒绝/强制压缩”等策略。
     */
    public int getBlockingLimit() {
        return effectiveWindow() - BLOCKING_BUFFER;
    }

    private int effectiveWindow() {
        return maxContextTokens - Math.min(maxOutputTokens, 20_000);
    }

    private int autoCompactThreshold() {
        int computed = effectiveWindow() - AUTOCOMPACT_BUFFER;
        int ratioThreshold = (int) (maxContextTokens * compactThreshold);
        return Math.min(computed, ratioThreshold);
    }

    /**
     * L1：清空旧 tool_result 的正文，仅保留最近 5 个工具结果不动。
     *
     * <p>这一步通常是收益最高、语义损失最低的压缩动作。因为真正决定下一步推理的，
     * 更多是工具“被调用过”以及最近的结果，而不是几十轮之前那份完整输出。</p>
     */
    private List<Message> microCompact(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages);
        int seenToolResults = 0;

        for (int i = result.size() - 1; i >= 0; i--) {
            Message message = result.get(i);
            List<ContentBlock> blocks = message.getContent();
            boolean changed = false;
            List<ContentBlock> updatedBlocks = new ArrayList<>(blocks.size());

            for (ContentBlock block : blocks) {
                if (block instanceof ToolResultBlock) {
                    ToolResultBlock toolResult = (ToolResultBlock) block;
                    seenToolResults++;
                    if (seenToolResults > KEEP_RECENT_TOOL_RESULTS) {
                        updatedBlocks.add(new ToolResultBlock(
                                toolResult.getToolUseId(),
                                TOOL_RESULT_PLACEHOLDER,
                                toolResult.isError()
                        ));
                        changed = true;
                    } else {
                        updatedBlocks.add(block);
                    }
                } else {
                    updatedBlocks.add(block);
                }
            }

            if (changed) {
                result.set(i, message.withContent(updatedBlocks));
            }
        }

        return result;
    }

    /**
     * L2：使用 Session Memory 替换较早的历史消息。
     *
     * <p>策略与调研文档一致：保留足够新的消息，确保尾部上下文仍然连贯；
     * 更早的部分则折叠为一条结构化摘要 user 消息。</p>
     */
    private List<Message> sessionMemoryCompact(List<Message> messages) {
        String summary = sessionMemory.toSummaryText();
        if (summary.isBlank()) {
            return messages;
        }

        int keepIndex = findKeepIndex(messages, SESSION_MIN_KEEP_TOKENS, SESSION_MAX_KEEP_TOKENS, SESSION_MIN_TEXT_MESSAGES);
        keepIndex = ensureAssistantBoundary(messages, keepIndex);
        return buildSummaryPrefixedMessages(messages, keepIndex, "Previous conversation summary:\n\n" + summary);
    }

    /**
     * L3：对更长的历史做摘要替换。
     *
     * <p>优先尝试首选摘要器；如果失败，则回退到本地规则摘要器。
     * 即便两者都失败，也至少保留“首条 user + 最近 N 轮”的安全降级结果。</p>
     */
    private List<Message> summaryCompact(List<Message> messages) {
        int keepIndex = Math.max(1, messages.size() - keepRecentTurns * 2);
        keepIndex = ensureAssistantBoundary(messages, keepIndex);

        List<Message> oldMessages = messages.subList(0, keepIndex);
        String summary = summarize(oldMessages);
        if (summary.isBlank()) {
            return fallbackTrim(messages, keepIndex);
        }

        return buildSummaryPrefixedMessages(
                messages,
                keepIndex,
                "This session continues from a compacted conversation.\n\nSummary:\n" + summary
        );
    }

    private List<Message> buildSummaryPrefixedMessages(List<Message> messages, int keepIndex, String summaryText) {
        List<Message> result = new ArrayList<>();
        result.add(Message.userText(summaryText));
        if (keepIndex < messages.size()) {
            result.addAll(messages.subList(keepIndex, messages.size()));
        }

        if (!isAlternating(result)) {
            return fallbackTrim(messages, keepIndex);
        }
        return result;
    }

    private List<Message> fallbackTrim(List<Message> messages, int keepIndex) {
        List<Message> trimmed = new ArrayList<>();
        trimmed.add(messages.get(0));
        if (keepIndex < messages.size()) {
            trimmed.addAll(messages.subList(keepIndex, messages.size()));
        }
        if (isAlternating(trimmed)) {
            return trimmed;
        }
        return new ArrayList<>(messages.subList(Math.max(0, messages.size() - keepRecentTurns * 2), messages.size()));
    }

    private int findKeepIndex(List<Message> messages,
                              int minKeepTokens,
                              int maxKeepTokens,
                              int minTextMessages) {
        int keepIndex = messages.size();
        int keptTokens = 0;
        int keptTextMessages = 0;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            int messageTokens = TokenEstimator.estimate(message);
            if (keptTokens + messageTokens > maxKeepTokens) {
                break;
            }

            keptTokens += messageTokens;
            if (!message.getTextContent().isBlank()) {
                keptTextMessages++;
            }
            keepIndex = i;

            if (keptTokens >= minKeepTokens && keptTextMessages >= minTextMessages) {
                break;
            }
        }
        return keepIndex;
    }

    private int ensureAssistantBoundary(List<Message> messages, int keepIndex) {
        int safeIndex = Math.max(1, Math.min(keepIndex, messages.size()));
        if (safeIndex < messages.size() && "user".equals(messages.get(safeIndex).getRole())) {
            safeIndex++;
        }
        return Math.min(safeIndex, messages.size());
    }

    private boolean isAlternating(List<Message> messages) {
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i - 1).getRole().equals(messages.get(i).getRole())) {
                return false;
            }
        }
        return true;
    }

    private String summarize(List<Message> messages) {
        try {
            if (summaryGenerator != null) {
                return summaryGenerator.summarize(messages);
            }
        } catch (Exception ignored) {
            // 主摘要器失败时，交给兜底摘要器处理。
        }

        try {
            if (fallbackSummaryGenerator != null) {
                return fallbackSummaryGenerator.summarize(messages);
            }
        } catch (Exception ignored) {
            // 所有摘要器都失败时返回空串，由上层做安全降级。
        }
        return "";
    }
}

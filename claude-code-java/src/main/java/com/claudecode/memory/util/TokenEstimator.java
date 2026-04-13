package com.claudecode.memory.util;

import com.claudecode.api.model.ContentBlock;
import com.claudecode.api.model.Message;
import com.claudecode.api.model.TextBlock;
import com.claudecode.api.model.ToolResultBlock;
import com.claudecode.api.model.ToolUseBlock;
import com.claudecode.core.ConversationHistory;

import java.util.List;

/**
 * TokenEstimator — 对消息历史做“够用而稳定”的 token 估算。
 *
 * <p>目标不是精确复刻服务端 tokenizer，而是为以下策略提供一个稳定依据：</p>
 * <ul>
 *   <li>何时接近上下文窗口</li>
 *   <li>何时触发会话记忆提取</li>
 *   <li>压缩后是否已经回到安全区间</li>
 * </ul>
 *
 * <p>实现遵循调研文档里的混合策略：</p>
 * <ol>
 *   <li>优先使用最近一次 assistant 响应中的 usage 精确值作为锚点</li>
 *   <li>usage 之后新增的消息再做粗估</li>
 * </ol>
 *
 * <p>粗估部分采用经验值：</p>
 * <ul>
 *   <li>CJK 字符约 2 字符 ≈ 1 token</li>
 *   <li>其他字符约 4 字符 ≈ 1 token</li>
 * </ul>
 */
public final class TokenEstimator {

    private static final int NON_CJK_CHARS_PER_TOKEN = 4;
    private static final int CJK_CHARS_PER_TOKEN = 2;

    private TokenEstimator() {
    }

    /**
     * 估算单条消息的 token 数。
     */
    public static int estimate(Message message) {
        if (message == null) {
            return 0;
        }

        int total = 0;
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock) {
                total += estimateText(((TextBlock) block).getText());
            } else if (block instanceof ToolResultBlock) {
                ToolResultBlock toolResult = (ToolResultBlock) block;
                total += estimateText(toolResult.getContent());
                total += estimateText(toolResult.getToolUseId());
                total += 6;
            } else if (block instanceof ToolUseBlock) {
                ToolUseBlock toolUse = (ToolUseBlock) block;
                total += estimateText(toolUse.getName());
                total += estimateText(toolUse.getId());
                total += estimateText(toolUse.getInput() != null ? toolUse.getInput().toString() : "");
                total += 12;
            }
        }

        // role、数组标记等协议开销。数值不追求绝对准确，但要稳定。
        return total + 8;
    }

    /**
     * 估算消息列表总 token 数。
     */
    public static int estimate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    /**
     * 对整个对话历史做混合估算：usage 精确值 + 新消息粗估。
     */
    public static int estimate(ConversationHistory history) {
        if (history == null) {
            return 0;
        }

        int usageTokens = history.getLastUsageTotalTokens();
        int usageIndex = history.getLastUsageMessageIndex();
        List<Message> messages = history.getMessages();

        if (usageTokens <= 0 || usageIndex < 0 || usageIndex >= messages.size()) {
            return estimate(messages);
        }

        return usageTokens + estimate(messages.subList(usageIndex + 1, messages.size()));
    }

    /**
     * 对纯文本进行粗估。
     */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }

        int cjkTokens = (int) Math.ceil(cjkCount / (double) CJK_CHARS_PER_TOKEN);
        int otherTokens = (int) Math.ceil(otherCount / (double) NON_CJK_CHARS_PER_TOKEN);
        return cjkTokens + otherTokens;
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}

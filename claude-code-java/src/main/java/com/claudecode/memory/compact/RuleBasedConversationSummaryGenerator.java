package com.claudecode.memory.compact;

import com.claudecode.api.model.Message;
import com.claudecode.memory.session.SessionMemoryExtractor;
import com.claudecode.memory.session.SessionMemorySnapshot;

import java.util.List;
import java.util.Map;

/**
 * RuleBasedConversationSummaryGenerator — 本地规则版摘要器。
 *
 * <p>这是所有压缩策略的兜底实现：不需要网络，不依赖模型，
 * 因此在任何环境下都能工作。</p>
 */
public class RuleBasedConversationSummaryGenerator implements ConversationSummaryGenerator {

    private final SessionMemoryExtractor extractor = new SessionMemoryExtractor();

    @Override
    public String summarize(List<Message> messages) {
        SessionMemorySnapshot snapshot = extractor.extract(messages);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : snapshot.getSections().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(entry.getKey()).append(":\n");
            sb.append(entry.getValue());
        }
        return sb.toString();
    }
}

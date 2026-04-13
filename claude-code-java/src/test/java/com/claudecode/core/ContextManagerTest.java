package com.claudecode.core;

import com.claudecode.api.model.Message;
import com.claudecode.api.model.ContentBlock;
import com.claudecode.api.model.ToolResultBlock;
import com.claudecode.api.model.ToolUseBlock;
import com.claudecode.api.model.TextBlock;
import com.claudecode.memory.compact.RuleBasedConversationSummaryGenerator;
import com.claudecode.memory.session.SessionMemory;
import com.claudecode.memory.session.SessionMemoryExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextManager 单元测试。
 */
class ContextManagerTest {

    @Test
    void compact_shouldClearOldToolResultsBeforeMoreAggressiveCompression() {
        ConversationHistory history = new ConversationHistory();

        for (int i = 0; i < 8; i++) {
            history.addUserMessage("用户请求第 " + i + " 轮 " + repeat("A", 600));
            history.addAssistantMessage(Message.assistantFromBlocks(List.of(
                    new ToolUseBlock("tool-" + i, "Read", Map.of("file_path", "src/Main" + i + ".java"))
            )));
            history.addToolResults(List.of(
                    new ToolResultBlock("tool-" + i, repeat("R", 3000), false)
            ));
            history.addAssistantMessage(Message.assistantFromBlocks(List.of(
                    new TextBlock("我已经读取完第 " + i + " 轮结果。")
            )));
        }

        ContextManager manager = new ContextManager(
                30_000,
                0.2,
                10,
                8_192,
                null,
                null,
                new RuleBasedConversationSummaryGenerator()
        );

        manager.compact(history);

        StringBuilder combined = new StringBuilder();
        for (Message message : history.getMessages()) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolResultBlock) {
                    combined.append(((ToolResultBlock) block).getContent()).append("\n");
                }
            }
        }

        assertTrue(combined.toString().contains("[Old tool result content cleared]"));
    }

    @Test
    void compact_shouldUseSessionMemorySummaryWhenAvailable() {
        ConversationHistory history = new ConversationHistory();
        for (int i = 0; i < 10; i++) {
            history.addUserMessage("请继续修改 src/main/java/App" + i + ".java " + repeat("B", 500));
            history.addAssistantMessage(Message.assistantFromBlocks(List.of()));
            history.addUserMessage("工具结果 " + repeat("C", 500));
            history.addAssistantMessage(Message.assistantFromBlocks(List.of()));
        }

        SessionMemory sessionMemory = new SessionMemory();
        sessionMemory.refresh(history.getMessages(), new SessionMemoryExtractor(), history.estimateTokenCount());

        ContextManager manager = new ContextManager(
                30_000,
                0.05,
                4,
                8_192,
                sessionMemory,
                null,
                new RuleBasedConversationSummaryGenerator()
        );

        manager.compact(history);

        assertTrue(history.getMessages().stream()
                .map(Message::getTextContent)
                .anyMatch(text -> text.contains("Previous conversation summary")));
    }

    private static String repeat(String text, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(text);
        }
        return sb.toString();
    }
}

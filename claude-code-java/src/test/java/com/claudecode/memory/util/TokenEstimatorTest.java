package com.claudecode.memory.util;

import com.claudecode.api.model.ApiResponse;
import com.claudecode.api.model.TextBlock;
import com.claudecode.core.ConversationHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenEstimator 单元测试。
 */
class TokenEstimatorTest {

    @Test
    void estimateText_shouldTreatCjkMoreDenselyThanAscii() {
        int english = TokenEstimator.estimateText("abcdefgh");
        int chinese = TokenEstimator.estimateText("你好世界");

        assertEquals(2, english);
        assertEquals(2, chinese);
    }

    @Test
    void estimateConversation_shouldUseUsageAnchorThenEstimateTrailingMessages() {
        ConversationHistory history = new ConversationHistory();
        history.addUserMessage("请读取 pom.xml");

        ApiResponse response = new ApiResponse(
                "msg_1",
                "assistant",
                "claude-sonnet-4-6",
                List.of(new TextBlock("我先检查一下。")),
                "tool_use",
                new ApiResponse.Usage(120, 30)
        );
        history.addAssistantResponse(response);
        history.addUserMessage("继续");

        int estimated = history.estimateTokenCount();

        assertTrue(estimated > 150);
        assertEquals(150 + TokenEstimator.estimateText("继续") + 8, estimated);
    }
}

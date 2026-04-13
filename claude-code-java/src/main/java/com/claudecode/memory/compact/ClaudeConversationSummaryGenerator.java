package com.claudecode.memory.compact;

import com.claudecode.api.ClaudeApiClient;
import com.claudecode.api.model.ApiRequest;
import com.claudecode.api.model.ApiResponse;
import com.claudecode.api.model.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ClaudeConversationSummaryGenerator — 使用 Claude API 生成 L3 摘要。
 *
 * <p>当上下文已经非常大时，纯规则摘要能工作，但信息密度不如模型总结。
 * 这个实现把旧历史发给 Claude 做一次非流式总结，然后用摘要替换原消息。</p>
 *
 * <p>如果调用失败，调用方应该退回到规则版摘要器，而不是让主流程失败。</p>
 */
public class ClaudeConversationSummaryGenerator implements ConversationSummaryGenerator {

    private final ClaudeApiClient apiClient;
    private final String model;

    public ClaudeConversationSummaryGenerator(ClaudeApiClient apiClient, String model) {
        this.apiClient = apiClient;
        this.model = model;
    }

    @Override
    public String summarize(List<Message> messages) throws Exception {
        String conversationText = messages.stream()
                .map(this::renderMessage)
                .collect(Collectors.joining("\n---\n"));

        String prompt = ""
                + "Summarize the following coding-agent conversation into concise sections.\n"
                + "Use these headings exactly:\n"
                + "1. Primary Request and Intent\n"
                + "2. Key Technical Concepts\n"
                + "3. Files and Code Sections\n"
                + "4. Errors and fixes\n"
                + "5. Current Work and Pending Tasks\n\n"
                + "Do not include chain-of-thought. Keep it compact but specific.\n\n"
                + conversationText;

        ApiRequest request = ApiRequest.builder()
                .model(model != null ? model : apiClient.getDefaultModel())
                .maxTokens(1200)
                .system("You compress long coding conversations into accurate continuation summaries.")
                .stream(false)
                .messages(List.of(Message.userText(prompt)))
                .build();

        ApiResponse response = apiClient.sendMessage(request);
        return response.getTextContent();
    }

    private String renderMessage(Message message) {
        return message.getRole() + ":\n" + message.getTextContent();
    }
}

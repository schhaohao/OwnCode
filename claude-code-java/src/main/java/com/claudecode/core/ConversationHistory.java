package com.claudecode.core;

import com.claudecode.api.model.Message;
import com.claudecode.api.model.ToolResultBlock;
import com.claudecode.api.model.ApiResponse;
import com.claudecode.memory.util.TokenEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ConversationHistory — 对话历史管理器
 *
 * 核心职责：
 *   维护一个 List<Message> 作为与 Claude API 交互的消息历史。
 *   消息必须严格遵循 user/assistant 交替的顺序。
 *
 * API消息格式回顾：
 *   - user 消息：可以包含 text 和 tool_result 类型的 ContentBlock
 *   - assistant 消息：可以包含 text 和 tool_use 类型的 ContentBlock
 *   - tool_result 必须放在 user 消息中，通过 tool_use_id 与对应的 tool_use 匹配
 *   - 在同一个 user 消息的 content 数组中，tool_result 块要排在 text 块之前
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class ConversationHistory {

    private final List<Message> messages = new ArrayList<>();
    private int lastUsageMessageIndex = -1;
    private int lastUsageTotalTokens = -1;

    /**
     * 添加一条纯文本的 user 消息
     *
     * @throws IllegalStateException 如果上一条消息也是 user（违反交替规则）
     */
    public void addUserMessage(String text) {
        checkRoleAlternation("user");
        messages.add(Message.userText(text));
    }

    /**
     * 添加从 API 响应转换出的 assistant 消息
     * 该消息可能包含 TextBlock 和 ToolUseBlock 的混合
     *
     * @throws IllegalStateException 如果上一条消息也是 assistant
     */
    public void addAssistantMessage(Message message) {
        checkRoleAlternation("assistant");
        messages.add(message);
    }

    /**
     * 添加 assistant 响应，并记录 usage 作为后续 token 估算的精确锚点。
     *
     * <p>这是记忆系统新增的关键辅助方法。Claude Code 原始实现并不会完全依赖
     * 粗略字符估算，而是尽量复用最近一次 API 返回的 usage。这样越到会话后期，
     * 估算越稳定。</p>
     */
    public void addAssistantResponse(ApiResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }

        addAssistantMessage(response.toMessage());
        if (response.getUsage() != null) {
            lastUsageMessageIndex = messages.size() - 1;
            lastUsageTotalTokens = response.getUsage().getTotalTokens();
        }
    }

    /**
     * 将工具执行结果包装成一条 user 消息添加到历史
     *
     * 在 API 协议中 tool_result 属于 user 侧消息。
     * 每个 ToolResultBlock 的 tool_use_id 必须与前一条 assistant 消息中
     * 对应的 ToolUseBlock.id 精确匹配。
     *
     * @throws IllegalStateException 如果上一条消息也是 user
     */
    public void addToolResults(List<ToolResultBlock> results) {
        checkRoleAlternation("user");
        messages.add(Message.userWithToolResults(new ArrayList<>(results)));
    }

    /**
     * 返回当前完整的消息列表（供 ApiRequest 构建使用）
     * 返回不可变视图，防止外部直接修改
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 清空历史（开始新会话时使用）
     */
    public void clear() {
        messages.clear();
        lastUsageMessageIndex = -1;
        lastUsageTotalTokens = -1;
    }

    /**
     * 粗略估算当前历史的 token 数量
     *
     * 简单方案：英文约每4字符≈1 token，中文约每2字符≈1 token
     * 这里统一按每4字符≈1 token 估算（偏保守）。
     * 用于上下文窗口管理，判断是否需要压缩。
     */
    public int estimateTokenCount() {
        return TokenEstimator.estimate(this);
    }

    /**
     * 当前历史中的消息数量
     */
    public int size() {
        return messages.size();
    }

    // ==================== ContextManager 专用 ====================

    /**
     * 用新的消息列表替换当前历史（用于上下文压缩）
     *
     * Package-private：仅供同包的 ContextManager 调用。
     * 调用方必须保证 newMessages 中的 role 交替是正确的。
     */
    void replaceAll(List<Message> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        lastUsageMessageIndex = -1;
        lastUsageTotalTokens = -1;
    }

    public int getLastUsageMessageIndex() {
        return lastUsageMessageIndex;
    }

    public int getLastUsageTotalTokens() {
        return lastUsageTotalTokens;
    }

    // ==================== 内部方法 ====================

    /**
     * 校验 role 交替规则：不允许连续两条相同 role 的消息
     */
    private void checkRoleAlternation(String newRole) {
        if (!messages.isEmpty()) {
            String lastRole = messages.get(messages.size() - 1).getRole();
            if (lastRole.equals(newRole)) {
                throw new IllegalStateException(
                        "Cannot add consecutive '" + newRole + "' messages. "
                        + "Messages must alternate between 'user' and 'assistant'.");
            }
        }
    }
}

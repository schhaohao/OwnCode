package com.claudecode.core;

import com.claudecode.api.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * ContextManager — 上下文窗口管理器
 *
 * 核心职责：
 *   监控对话历史的 token 用量，在接近上下文窗口限制时执行压缩，
 *   防止因超出窗口限制导致 API 调用失败。
 *
 * 上下文窗口组成（每次API请求都会占用）：
 *   - 系统提示 (system prompt)：固定开销，约几百到几千 token
 *   - 工具定义 (tools)：每个工具的 schema 和 description，固定开销
 *   - 对话历史 (messages)：随每轮对话增长，是主要的增长来源
 *     - 用户输入的文本
 *     - LLM的回复文本
 *     - 工具调用的输入参数（可能很大，如长文件路径）
 *     - 工具执行结果（可能非常大，如整个文件内容）
 *
 * 需要实现的方法：
 *
 * 1. public boolean isNearLimit(ConversationHistory history)
 *    - 判断当前历史是否接近上下文窗口限制
 *    - 建议阈值：当已用 token 超过窗口容量的 80% 时返回 true
 *    - Claude的上下文窗口大小：200K tokens（可配置）
 *
 * 2. public void compact(ConversationHistory history)
 *    - 执行上下文压缩，缩减对话历史的大小
 *    - 压缩策略（由简到复杂，建议先实现简单版本）：
 *
 *      【简单版 - 建议先实现这个】
 *      a. 截断策略：保留最近 N 轮对话，丢弃更早的历史
 *         - 保留第一条 user 消息（包含初始需求上下文）
 *         - 保留最近 10 轮对话
 *         - 中间的历史直接丢弃
 *
 *      【进阶版 - 后续优化】
 *      b. 摘要策略：调用 LLM 对旧历史生成摘要
 *         - 把要丢弃的历史发给 Claude，让它生成一段摘要
 *         - 用摘要替换旧历史（作为一条 system 消息或 user 消息的前缀）
 *         - 这样能保留更多上下文信息
 *
 *      c. 工具结果截断：优先截断大体积的工具执行结果
 *         - 找到历史中最大的 tool_result 块
 *         - 将其截断为前 N 行 + "... (truncated)"
 *         - 这通常是最有效的空间释放方式
 *
 * 3. public int getMaxContextTokens()
 *    - 返回配置的最大上下文 token 数
 *    - 默认 200000（Claude 的 200K 窗口）
 *
 * 配置项：
 * - maxContextTokens: 最大上下文token数（默认200000）
 * - compactThreshold: 触发压缩的阈值比例（默认0.8）
 * - keepRecentTurns: 压缩时保留最近多少轮对话（默认10）
 */
public class ContextManager {

    /** Claude 上下文窗口大小（200K tokens） */
    private final int maxContextTokens;

    /** 触发压缩的阈值比例（已用 token / 窗口容量） */
    private final double compactThreshold;

    /** 压缩时保留最近多少轮对话（1轮 = 1对 user+assistant 消息） */
    private final int keepRecentTurns;

    public ContextManager() {
        this(200_000, 0.8, 10);
    }

    public ContextManager(int maxContextTokens, double compactThreshold, int keepRecentTurns) {
        this.maxContextTokens = maxContextTokens;
        this.compactThreshold = compactThreshold;
        this.keepRecentTurns = keepRecentTurns;
    }

    /**
     * 判断当前历史是否接近上下文窗口限制
     *
     * 当已用 token 超过窗口容量的 compactThreshold（默认 80%）时返回 true
     */
    public boolean isNearLimit(ConversationHistory history) {
        int estimated = history.estimateTokenCount();
        return estimated > (int) (maxContextTokens * compactThreshold);
    }

    /**
     * 执行上下文压缩 — 截断策略（简单版）
     *
     * 策略：
     *   1. 保留第一条 user 消息（包含用户的初始需求，是最重要的上下文）
     *   2. 保留最近 keepRecentTurns * 2 条消息（最近的对话最有价值）
     *   3. 中间的历史直接丢弃
     *
     * 确保截取后的消息序列仍然满足 user/assistant 交替规则。
     */
    public void compact(ConversationHistory history) {
        List<Message> messages = history.getMessages();
        int total = messages.size();
        int keepRecent = keepRecentTurns * 2;  // 每轮 = user + assistant

        // 消息太少，不需要压缩
        if (total <= keepRecent + 1) {
            return;
        }

        List<Message> compacted = new ArrayList<>();

        // 1. 保留第一条 user 消息（初始上下文）
        compacted.add(messages.get(0));

        // 2. 计算最近消息的起始索引
        int recentStart = total - keepRecent;

        // 确保 recentStart 指向 assistant 消息（接在 messages[0](user) 后面）
        // 如果指向 user（可能是 tool_result），往后移一位到 assistant
        // 避免 user, user 连续，也避免 orphan tool_result
        if (recentStart > 0 && "user".equals(messages.get(recentStart).getRole())) {
            recentStart++;
        }

        // 避免和第一条消息重叠或越界
        if (recentStart <= 1 || recentStart >= total) {
            return;  // 无法有效压缩
        }

        // 3. 保留最近的消息
        for (int i = recentStart; i < total; i++) {
            compacted.add(messages.get(i));
        }

        // 4. 验证 role 交替：如果压缩后不满足交替规则则放弃压缩
        for (int i = 1; i < compacted.size(); i++) {
            if (compacted.get(i).getRole().equals(compacted.get(i - 1).getRole())) {
                return;
            }
        }

        // 用压缩后的列表替换原历史
        history.replaceAll(compacted);
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }
}

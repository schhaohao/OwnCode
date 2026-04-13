package com.claudecode.memory.compact;

import com.claudecode.api.model.Message;

import java.util.List;

/**
 * ConversationSummaryGenerator — 为 L3 压缩生成对话摘要的抽象接口。
 *
 * <p>做这层抽象的原因是：压缩策略希望优先用更高质量的摘要器，
 * 但在网络失败、限流或者测试环境里，又需要一个稳定的本地回退实现。</p>
 */
public interface ConversationSummaryGenerator {

    /**
     * 将一段较长的消息历史总结成紧凑文本。
     *
     * @param messages 需要总结的消息列表
     * @return 摘要文本
     * @throws Exception 允许实现抛出异常，以便调用方回退到其他摘要器
     */
    String summarize(List<Message> messages) throws Exception;
}

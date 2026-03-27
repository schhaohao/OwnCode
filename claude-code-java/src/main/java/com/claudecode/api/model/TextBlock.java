package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TextBlock — 纯文本内容块 (type = "text")
 *
 * 出现在 user 消息和 assistant 消息中，承载纯文本内容。
 *
 * JSON 格式：
 *   {"type": "text", "text": "Hello world"}
 *
 * 序列化示例：
 *   new TextBlock("你好") → {"type":"text","text":"你好"}
 *
 * 反序列化示例：
 *   {"type":"text","text":"你好"} → TextBlock 实例，getText() 返回 "你好"
 *   （由 ContentBlock 上的 @JsonSubTypes 注解自动路由到此类）
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class TextBlock extends ContentBlock {

    /** 文本内容 */
    @JsonProperty("text")
    private final String text;

    /**
     * @JsonCreator 告诉 Jackson：反序列化时调用这个构造函数
     * @JsonProperty("text") 告诉 Jackson：把 JSON 中的 "text" 字段值传给这个参数
     */
    @JsonCreator
    public TextBlock(@JsonProperty("text") String text) {
        super("text");  // 固定 type = "text"
        this.text = text;
    }

    public String getText() {
        return text;
    }
}

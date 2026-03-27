package com.claudecode.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * ContentBlock — 消息内容块模型（抽象基类）
 *
 * Message 的 content 字段是一个 ContentBlock 列表。
 * ContentBlock 是多态的，通过 "type" 字段区分不同类型。
 *
 * 你需要支持的 content block 类型：
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 1. TextBlock (type = "text")
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *    出现在：user 消息、assistant 消息
 *    JSON: {"type": "text", "text": "Hello world"}
 *    字段：
 *      - String text: 文本内容
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 2. ToolUseBlock (type = "tool_use")
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *    出现在：assistant 消息（LLM 请求调用工具）
 *    JSON: {
 *      "type": "tool_use",
 *      "id": "toolu_01A09q90qw90lq917835lq9",
 *      "name": "Read",
 *      "input": {"file_path": "/path/to/file"}
 *    }
 *    字段：
 *      - String id:    工具调用的唯一标识（用于匹配 tool_result）
 *      - String name:  工具名称（对应 Tool.name()）
 *      - Map<String, Object> input: 工具的输入参数
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 3. ToolResultBlock (type = "tool_result")
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *    出现在：user 消息（你的代码返回工具执行结果给LLM）
 *    JSON: {
 *      "type": "tool_result",
 *      "tool_use_id": "toolu_01A09q90qw90lq917835lq9",
 *      "content": "文件内容...",
 *      "is_error": false
 *    }
 *    字段：
 *      - String toolUseId: 对应的 tool_use 的 id（必须匹配！）
 *      - String content:   工具执行结果（文本）
 *      - boolean isError:  是否执行出错（true时LLM会尝试调整策略）
 *
 * 实现方式选择（二选一）：
 *
 * 方案A — 继承体系（推荐，类型安全）：  ← 已选择此方案
 *   abstract class ContentBlock { String type; }
 *   class TextBlock extends ContentBlock { String text; }
 *   class ToolUseBlock extends ContentBlock { String id; String name; Map input; }
 *   class ToolResultBlock extends ContentBlock { String toolUseId; String content; boolean isError; }
 *
 * 方案B — 单类 + 可选字段（简单，但不够优雅）：
 *   class ContentBlock {
 *       String type;
 *       String text;           // type="text" 时有值
 *       String id;             // type="tool_use" 时有值
 *       String name;           // type="tool_use" 时有值
 *       Map input;             // type="tool_use" 时有值
 *       String toolUseId;      // type="tool_result" 时有值
 *       String content;        // type="tool_result" 时有值
 *       boolean isError;       // type="tool_result" 时有值
 *   }
 *
 * Jackson 多态序列化提示：
 * - 如果用方案A，可以使用 @JsonTypeInfo + @JsonSubTypes 注解实现自动多态解析
 * - 或者手动写自定义反序列化器（更灵活）
 *
 * @author sunchenhao
 * @date 2026/3/27
 */

// ==================== Jackson 多态配置 ====================
// @JsonTypeInfo: 告诉 Jackson 根据 JSON 中的 "type" 字段来决定反序列化为哪个子类
//   - use = Id.NAME: 用名字（字符串）来标识类型
//   - include = As.EXISTING_PROPERTY: "type" 是 JSON 中已有的字段（不是 Jackson 额外加的）
//   - property = "type": 指定用哪个 JSON 字段做类型标识
//
// @JsonSubTypes: 建立 "type" 字段值 → 子类 的映射关系
//   - "text"        → TextBlock
//   - "tool_use"    → ToolUseBlock
//   - "tool_result" → ToolResultBlock
//
// 这样 Jackson 看到 {"type":"text","text":"hello"} 就自动创建 TextBlock 实例
// 看到 {"type":"tool_use","id":"...","name":"Read","input":{...}} 就自动创建 ToolUseBlock 实例

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
})
public abstract class ContentBlock {

    /**
     * 类型标识字段，每个子类在构造时设置固定值
     * 序列化时输出到 JSON 的 "type" 字段
     */
    @JsonProperty("type")
    protected final String type;

    protected ContentBlock(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}

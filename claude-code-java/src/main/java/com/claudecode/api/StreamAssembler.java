package com.claudecode.api;

/**
 * StreamAssembler — SSE 流式响应组装器
 *
 * 核心职责：
 *   实现 OkHttp 的 EventSourceListener，逐个处理 SSE 事件，
 *   最终组装出一个完整的 ApiResponse 对象。
 *   同时将文本增量实时通过回调函数输出到终端。
 *
 * SSE 事件序列（一个完整响应的事件流）：
 *
 *   event: message_start          → 响应开始，包含 Message 的元信息（id, model, role）
 *   event: content_block_start    → 一个 content block 开始（type="text" 或 "tool_use"）
 *   event: content_block_delta    → 增量数据到达（text_delta 或 input_json_delta）
 *   event: content_block_delta    → ...更多增量...
 *   event: content_block_stop     → 当前 content block 结束
 *   event: content_block_start    → 下一个 block 开始（如果有多个）
 *   event: content_block_delta    → ...
 *   event: content_block_stop     → ...
 *   event: message_delta          → 包含 stop_reason 和 usage 统计
 *   event: message_stop           → 整个消息结束
 *
 * 需要处理的事件类型和数据格式：
 *
 * 1. "message_start"
 *    data: {"type":"message_start","message":{"id":"msg_xxx","role":"assistant",...}}
 *    → 初始化 ApiResponse.Builder，记录 id 和 role
 *
 * 2. "content_block_start"
 *    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
 *    或:   {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_xxx","name":"Read","input":{}}}
 *    → 如果是 text 类型：准备接收文本增量
 *    → 如果是 tool_use 类型：记录 tool_use 的 id 和 name，准备拼接 input JSON
 *
 * 3. "content_block_delta"
 *    文本增量: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}
 *    → 调用 onTextDelta 回调实时输出，同时追加到内部文本缓冲区
 *
 *    工具输入增量: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"file"}}
 *    → 追加到 toolInputBuffer（StringBuilder），不要尝试解析不完整的 JSON
 *
 * 4. "content_block_stop"
 *    → 如果当前 block 是 tool_use：将 toolInputBuffer 的完整 JSON 解析为 Map
 *    → 将完成的 ContentBlock 添加到响应的 content 列表
 *    → 清空缓冲区，准备下一个 block
 *
 * 5. "message_delta"
 *    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}
 *    → 记录 stop_reason（最关键的字段！决定 AgentLoop 是否继续）
 *    → 记录 usage 统计
 *
 * 6. "message_stop"
 *    → 标记流结束
 *
 * 需要维护的内部状态：
 * - ApiResponse.Builder: 逐步构建最终响应
 * - StringBuilder textBuffer: 当前 text block 的文本累积
 * - StringBuilder toolInputBuffer: 当前 tool_use block 的 input JSON 累积
 * - String currentBlockType: 当前正在处理的 block 类型 ("text" 或 "tool_use")
 * - String currentToolId: 当前 tool_use 的 id
 * - String currentToolName: 当前 tool_use 的 name
 * - CountDownLatch completionLatch: 用于同步等待流完成
 *
 * 需要实现的方法：
 *
 * 1. 继承 okhttp3.sse.EventSourceListener，覆写：
 *    - onEvent(EventSource, String id, String type, String data)
 *    - onFailure(EventSource, Throwable, Response)
 *    - onClosed(EventSource)
 *
 * 2. public ApiResponse getResponse()
 *    - 阻塞等待流完成（通过 CountDownLatch）
 *    - 返回组装好的完整 ApiResponse
 *
 * 关键注意事项：
 * - tool_use 的 input 是以 JSON 片段增量到达的，必须全部拼接完成后才能解析
 * - 一个 assistant 消息可能包含多个 content block（先文本后工具调用）
 * - 使用 Jackson 的 ObjectMapper 来解析每个 SSE 事件的 data 字段
 */
public class StreamAssembler {

    // TODO: 实现
}

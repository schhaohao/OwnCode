package com.claudecode.api;

import com.claudecode.api.model.ApiResponse;
import com.claudecode.api.model.ContentBlock;
import com.claudecode.api.model.TextBlock;
import com.claudecode.api.model.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 *
 * @author sunchenhao
 * @date 2026/3/29
 */
public class StreamAssembler extends EventSourceListener {

    private final ObjectMapper mapper;

    /** 文本增量的实时回调（每收到一个 text_delta 就调用一次，用于实时输出到终端） */
    private final Consumer<String> onTextDelta;

    /** 逐步构建的响应对象 */
    private final ApiResponse.Builder responseBuilder = ApiResponse.builder();

    // ——— 当前 content block 的临时状态 ———
    /** 当前正在处理的 block 类型："text" 或 "tool_use" */
    private String currentBlockType;
    /** 当前 text block 的文本累积 */
    private final StringBuilder textBuffer = new StringBuilder();
    /** 当前 tool_use block 的 input JSON 片段累积 */
    private final StringBuilder toolInputBuffer = new StringBuilder();
    /** 当前 tool_use 的 id */
    private String currentToolId;
    /** 当前 tool_use 的 name */
    private String currentToolName;

    // ——— 同步控制 ———
    /** 用于阻塞等待流完成（countDown 在 message_stop 或 onFailure/onClosed 时触发） */
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    /** 流处理过程中的错误（如果有） */
    private volatile String error;
    /** message_start 中的 input_tokens（message_delta 中不包含此值） */
    private int inputTokens;

    public StreamAssembler(ObjectMapper mapper, Consumer<String> onTextDelta) {
        this.mapper = mapper;
        this.onTextDelta = onTextDelta;
    }

    // ==================== EventSourceListener 覆写 ====================

    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
        try {
            // type 对应 SSE 的 "event:" 字段值
            // data 对应 SSE 的 "data:" 字段值（JSON 字符串）
            switch (type) {
                case "message_start":
                    handleMessageStart(data);
                    break;
                case "content_block_start":
                    handleContentBlockStart(data);
                    break;
                case "content_block_delta":
                    handleContentBlockDelta(data);
                    break;
                case "content_block_stop":
                    handleContentBlockStop();
                    break;
                case "message_delta":
                    handleMessageDelta(data);
                    break;
                case "message_stop":
                    // 整个消息结束，释放等待锁
                    completionLatch.countDown();
                    break;
                case "ping":
                    // 心跳事件，忽略
                    break;
                default:
                    // 未知事件类型，忽略（API 可能新增事件类型）
                    break;
            }
        } catch (Exception e) {
            error = "Error processing SSE event '" + type + "': " + e.getMessage();
            completionLatch.countDown();
        }
    }

    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        if (t != null) {
            error = "SSE connection failed: " + t.getMessage();
        } else if (response != null) {
            error = "SSE connection failed with HTTP " + response.code();
        } else {
            error = "SSE connection failed (unknown reason)";
        }
        // 显式关闭 response 释放连接，防止资源泄漏
        if (response != null) {
            response.close();
        }
        completionLatch.countDown();
    }

    @Override
    public void onClosed(EventSource eventSource) {
        // 正常关闭（有时在 message_stop 之后触发，有时替代它）
        completionLatch.countDown();
    }

    // ==================== 事件处理方法 ====================

    /**
     * message_start 事件：响应开始，提取 id 和 model
     *
     * data 格式：
     *   {"type":"message_start","message":{"id":"msg_xxx","model":"claude-sonnet-4-6","role":"assistant",...}}
     */
    private void handleMessageStart(String data) throws Exception {
        JsonNode root = mapper.readTree(data);
        JsonNode message = root.get("message");
        if (message != null) {
            if (message.has("id")) {
                responseBuilder.id(message.get("id").asText());
            }
            if (message.has("model")) {
                responseBuilder.model(message.get("model").asText());
            }
            if (message.has("role")) {
                responseBuilder.role(message.get("role").asText());
            }
            // 提取 input_tokens（只在 message_start 中出现）
            if (message.has("usage")) {
                JsonNode usage = message.get("usage");
                if (usage.has("input_tokens")) {
                    inputTokens = usage.get("input_tokens").asInt();
                }
            }
        }
    }

    /**
     * content_block_start 事件：一个新的 content block 开始
     *
     * data 格式（text）：
     *   {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
     *
     * data 格式（tool_use）：
     *   {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_xxx","name":"Read","input":{}}}
     */
    private void handleContentBlockStart(String data) throws Exception {
        JsonNode root = mapper.readTree(data);
        JsonNode contentBlock = root.get("content_block");
        if (contentBlock == null) return;

        currentBlockType = contentBlock.get("type").asText();

        if ("text".equals(currentBlockType)) {
            // 重置文本缓冲区，准备接收 text_delta
            textBuffer.setLength(0);
        } else if ("tool_use".equals(currentBlockType)) {
            // 记录 tool_use 的元信息，重置 JSON 拼接缓冲区
            currentToolId = contentBlock.get("id").asText();
            currentToolName = contentBlock.get("name").asText();
            toolInputBuffer.setLength(0);
        }
    }

    /**
     * content_block_delta 事件：增量数据到达
     *
     * text_delta 格式：
     *   {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     *
     * input_json_delta 格式：
     *   {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"file"}}
     */
    private void handleContentBlockDelta(String data) throws Exception {
        JsonNode root = mapper.readTree(data);
        JsonNode delta = root.get("delta");
        if (delta == null) return;

        String deltaType = delta.get("type").asText();

        if ("text_delta".equals(deltaType)) {
            String text = delta.get("text").asText();
            // 1. 实时回调输出到终端（用户立刻看到文字逐字出现）
            if (onTextDelta != null) {
                onTextDelta.accept(text);
            }
            // 2. 累积到缓冲区（content_block_stop 时构建完整 TextBlock）
            textBuffer.append(text);

        } else if ("input_json_delta".equals(deltaType)) {
            // JSON 片段拼接，不要尝试解析不完整的 JSON！
            // 必须等 content_block_stop 时才能解析
            String partialJson = delta.get("partial_json").asText();
            toolInputBuffer.append(partialJson);
        }
    }

    /**
     * content_block_stop 事件：当前 block 结束，构建完整的 ContentBlock
     */
    @SuppressWarnings("unchecked")
    private void handleContentBlockStop() throws Exception {
        ContentBlock block;

        if ("text".equals(currentBlockType)) {
            // 用累积的文本构建 TextBlock
            block = new TextBlock(textBuffer.toString());

        } else if ("tool_use".equals(currentBlockType)) {
            // 现在 toolInputBuffer 中的 JSON 是完整的，可以安全解析了
            Map<String, Object> input;
            String jsonStr = toolInputBuffer.toString();
            if (jsonStr.isEmpty()) {
                input = new HashMap<>();
            } else {
                input = mapper.readValue(jsonStr, Map.class);
            }
            block = new ToolUseBlock(currentToolId, currentToolName, input);

        } else {
            // 未知 block 类型，跳过
            return;
        }

        responseBuilder.addContentBlock(block);

        // 清空状态，准备下一个 block
        currentBlockType = null;
        textBuffer.setLength(0);
        toolInputBuffer.setLength(0);
    }

    /**
     * message_delta 事件：消息级别的元信息更新（stop_reason、usage）
     *
     * data 格式：
     *   {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}
     */
    private void handleMessageDelta(String data) throws Exception {
        JsonNode root = mapper.readTree(data);

        JsonNode delta = root.get("delta");
        if (delta != null && delta.has("stop_reason")) {
            responseBuilder.stopReason(delta.get("stop_reason").asText());
        }

        JsonNode usage = root.get("usage");
        if (usage != null) {
            int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
            // input_tokens 在 message_start 中已提取，message_delta 只有 output_tokens
            responseBuilder.usage(new ApiResponse.Usage(inputTokens, outputTokens));
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 阻塞等待流完成，返回组装好的 ApiResponse
     *
     * @param timeoutSeconds 最大等待秒数
     * @return 完整的 ApiResponse
     * @throws Exception 超时或流处理错误
     */
    public ApiResponse getResponse(long timeoutSeconds) throws Exception {
        boolean completed = completionLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            throw new java.io.IOException("SSE stream timed out after " + timeoutSeconds + " seconds");
        }
        if (error != null) {
            throw new java.io.IOException(error);
        }
        return responseBuilder.build();
    }

    /**
     * 便捷方法：默认超时 300 秒
     */
    public ApiResponse getResponse() throws Exception {
        return getResponse(300);
    }

    /** 获取流处理过程中的错误（如果有） */
    public String getError() {
        return error;
    }
}

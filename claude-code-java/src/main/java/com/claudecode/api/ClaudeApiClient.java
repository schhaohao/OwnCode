package com.claudecode.api;

import com.claudecode.api.model.ApiRequest;
import com.claudecode.api.model.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ClaudeApiClient — Claude API 通信客户端
 *
 * 核心职责：
 *   封装与 Claude Messages API 的 HTTP 通信，支持流式(SSE)和非流式两种模式。
 *
 * API 端点：
 *   POST https://api.anthropic.com/v1/messages
 *
 * 请求头：
 *   - x-api-key: {你的API Key}
 *   - anthropic-version: 2023-06-01
 *   - content-type: application/json
 *
 * 依赖：
 *   - OkHttpClient：HTTP 通信
 *   - Jackson ObjectMapper：JSON 序列化/反序列化
 *   - OkHttp EventSource：SSE 流式响应处理
 *
 * 需要实现的方法：
 *
 * 1. 构造函数 ClaudeApiClient(String apiKey, String model)
 *    - 初始化 OkHttpClient（建议设置较长的读取超时，如120秒，因为LLM响应可能较慢）
 *    - 初始化 Jackson ObjectMapper
 *    - 保存 apiKey 和 model
 *
 * 2. public ApiResponse sendMessage(ApiRequest request)
 *    - 非流式调用（简单版本，用于调试）
 *    - 将 ApiRequest 序列化为 JSON
 *    - 发送 POST 请求，等待完整响应
 *    - 将响应 JSON 反序列化为 ApiResponse
 *    - stream 字段设为 false
 *
 * 3. public ApiResponse sendMessageStream(ApiRequest request, Consumer<String> onTextDelta)
 *    - 流式调用（正式版本，实时输出）
 *    - 将 ApiRequest 序列化为 JSON，stream 字段设为 true
 *    - 使用 OkHttp 的 EventSource 建立 SSE 连接
 *    - 创建 StreamAssembler 来处理 SSE 事件
 *    - onTextDelta 回调用于实时输出文本片段到终端
 *    - 等待流完成后，从 StreamAssembler 获取组装好的 ApiResponse
 *
 * 请求体结构（JSON）：
 *   {
 *     "model": "claude-sonnet-4-6",
 *     "max_tokens": 8192,
 *     "system": "你是一个代码助手...",
 *     "stream": true,
 *     "tools": [ ...工具定义数组... ],
 *     "messages": [ ...对话历史... ]
 *   }
 *
 * 错误处理：
 * - HTTP 401: API Key 无效
 * - HTTP 429: 速率限制，需要等待重试
 * - HTTP 500+: 服务端错误
 * - 网络超时: 提示用户检查网络
 * - 建议对 429 实现简单的退避重试（等待几秒后重试）
 *
 * @author sunchenhao
 * @date 2026/3/29
 */
public class ClaudeApiClient {

    /** Claude Messages API 端点 */
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    /** API 版本号（必须在请求头中携带） */
    private static final String API_VERSION = "2023-06-01";

    /** 429 限流时最大重试次数 */
    private static final int MAX_RETRIES = 3;

    private final String apiKey;
    private final String defaultModel;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * @param apiKey API 密钥（从环境变量 ANTHROPIC_API_KEY 获取）
     * @param defaultModel 默认模型名称，如 "claude-sonnet-4-6"
     */
    public ClaudeApiClient(String apiKey, String defaultModel) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.mapper = new ObjectMapper();

        // LLM 响应可能很慢（复杂任务需要长时间思考），所以设置较长的超时
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)    // 5分钟读取超时
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 允许自定义 OkHttpClient 和 API URL（用于测试）
     */
    ClaudeApiClient(String apiKey, String defaultModel, OkHttpClient httpClient) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper();
    }

    // ==================== 非流式调用 ====================

    /**
     * 非流式调用 — 发送请求，等待完整响应后一次性返回
     *
     * 适用场景：调试、简单测试、不需要实时输出的场景
     *
     * 流程：
     *   1. 序列化 ApiRequest → JSON（强制 stream=false）
     *   2. POST 到 API 端点
     *   3. 等待完整响应
     *   4. 反序列化 JSON → ApiResponse
     *
     * @throws IOException 网络错误或 API 返回错误状态码
     */
    public ApiResponse sendMessage(ApiRequest request) throws IOException {
        // 确保 stream=false（非流式模式）
        ApiRequest actualRequest = ensureStreamFlag(request, false);
        String jsonBody = mapper.writeValueAsString(actualRequest);

        Request httpRequest = buildHttpRequest(jsonBody);

        // 带重试的请求发送
        return executeWithRetry(httpRequest);
    }

    /**
     * 执行 HTTP 请求，支持 429 限流自动重试
     */
    private ApiResponse executeWithRetry(Request httpRequest) throws IOException {
        int retries = 0;
        while (true) {
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                int code = response.code();

                // 成功
                if (code == 200) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return mapper.readValue(responseBody, ApiResponse.class);
                }

                // 429 限流：等待后重试
                if (code == 429 && retries < MAX_RETRIES) {
                    retries++;
                    long waitSeconds = getRetryAfterSeconds(response, retries);
                    try {
                        Thread.sleep(waitSeconds * 1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for rate limit retry");
                    }
                    continue;  // 重试
                }

                // 其他错误
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException(formatHttpError(code, errorBody));
            }
        }
    }

    // ==================== 流式调用 ====================

    /**
     * 流式调用 — 通过 SSE 实时接收响应，文本片段实时回调输出
     *
     * 适用场景：正式使用，用户能实时看到 LLM 输出的文字逐字出现
     *
     * 流程：
     *   1. 序列化 ApiRequest → JSON（强制 stream=true）
     *   2. 创建 StreamAssembler（SSE 事件处理器）
     *   3. 通过 OkHttp EventSource 建立 SSE 连接
     *   4. StreamAssembler 逐事件处理：
     *      - text_delta → 调用 onTextDelta 回调实时输出
     *      - 其他事件 → 逐步组装 ApiResponse
     *   5. 流结束后返回完整的 ApiResponse
     *
     * @param request    API 请求
     * @param onTextDelta 文本增量回调（每收到一个文本片段就调用，用于实时输出到终端）
     *                    传 null 表示不需要实时输出
     * @throws Exception 网络错误、流处理错误或超时
     */
    public ApiResponse sendMessageStream(ApiRequest request, Consumer<String> onTextDelta) throws Exception {
        // 确保 stream=true（流式模式）
        ApiRequest actualRequest = ensureStreamFlag(request, true);
        String jsonBody = mapper.writeValueAsString(actualRequest);

        Request httpRequest = buildHttpRequest(jsonBody);

        // 创建 StreamAssembler 来处理 SSE 事件流
        StreamAssembler assembler = new StreamAssembler(mapper, onTextDelta);

        // 通过 OkHttp EventSource 建立 SSE 连接
        // EventSources.createFactory 创建的 EventSource 会自动在后台线程处理 SSE 事件
        EventSource.Factory factory = EventSources.createFactory(httpClient);
        factory.newEventSource(httpRequest, assembler);

        // 阻塞等待流完成（StreamAssembler 内部用 CountDownLatch 同步）
        // 超时时间 300 秒（与 httpClient.readTimeout 一致）
        return assembler.getResponse(300);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 构建 HTTP 请求（通用，流式和非流式共用）
     *
     * 设置三个必须的请求头：
     * - x-api-key: API 密钥
     * - anthropic-version: API 版本号
     * - content-type: application/json
     */
    Request buildHttpRequest(String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json"));

        return new Request.Builder()
                .url(API_URL)
                .post(body)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("content-type", "application/json")
                .build();
    }

    /**
     * 确保 request 的 stream 字段为指定值
     *
     * 因为 ApiRequest 是不可变的（final 字段），如果 stream 值不对，
     * 需要用 Builder 重新构建一个。这里简单地总是重新构建。
     */
    private ApiRequest ensureStreamFlag(ApiRequest request, boolean stream) {
        return ApiRequest.builder()
                .model(request.getModel() != null ? request.getModel() : defaultModel)
                .maxTokens(request.getMaxTokens())
                .system(request.getSystem())
                .stream(stream)
                .tools(request.getTools())
                .messages(request.getMessages())
                .build();
    }

    /**
     * 从 429 响应中提取 Retry-After 秒数，或使用指数退避
     */
    private long getRetryAfterSeconds(Response response, int retryCount) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter);
            } catch (NumberFormatException ignored) {
            }
        }
        // 指数退避：2s, 4s, 8s
        return (long) Math.pow(2, retryCount);
    }

    /**
     * 格式化 HTTP 错误信息，让用户一眼看懂问题
     */
    private String formatHttpError(int code, String body) {
        switch (code) {
            case 401:
                return "API Key is invalid or missing (HTTP 401). Check your ANTHROPIC_API_KEY.";
            case 403:
                return "Access denied (HTTP 403). Your API key may not have permission for this model.";
            case 429:
                return "Rate limited (HTTP 429). Too many requests, please wait. Response: " + body;
            case 500:
            case 502:
            case 503:
                return "Claude API server error (HTTP " + code + "). Please try again later. Response: " + body;
            default:
                return "Claude API error (HTTP " + code + "): " + body;
        }
    }

    // ==================== Getter ====================

    public String getDefaultModel() { return defaultModel; }
    ObjectMapper getMapper() { return mapper; }
}

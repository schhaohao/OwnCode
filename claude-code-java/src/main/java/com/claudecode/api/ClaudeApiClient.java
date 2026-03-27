package com.claudecode.api;

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
 */
public class ClaudeApiClient {

    // TODO: 实现
}

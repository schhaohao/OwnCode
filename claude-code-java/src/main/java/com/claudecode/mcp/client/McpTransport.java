package com.claudecode.mcp.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

/**
 * MCP 传输层抽象接口
 *
 * 负责与 MCP Server 之间的底层通信。
 * 当前实现：StdioTransport（子进程 stdin/stdout）
 * 后续可扩展：HttpSseTransport（HTTP + SSE）
 */
public interface McpTransport extends Closeable {

    /**
     * 启动传输连接（如启动子进程）
     */
    void start() throws IOException;

    /**
     * 发送 JSON-RPC 请求并等待响应（阻塞）
     *
     * @param request 带 id 的请求
     * @return 匹配的响应
     * @throws IOException 通信失败或超时
     */
    JsonRpcResponse send(JsonRpcRequest request) throws IOException;

    /**
     * 发送 JSON-RPC 通知（无 id，不等待响应）
     *
     * @param method 方法名，如 "notifications/initialized"
     * @param params 参数，可以为 null
     */
    void sendNotification(String method, Map<String, Object> params) throws IOException;

    /**
     * 检查传输是否仍然活跃
     */
    boolean isAlive();

    /**
     * 关闭传输连接（销毁子进程等）
     */
    @Override
    void close() throws IOException;
}

package com.claudecode.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * stdio Transport 实现
 *
 * 通过 ProcessBuilder 启动 MCP Server 子进程，
 * 用 stdin 发送 JSON-RPC 请求，从 stdout 读取响应。
 *
 * 请求-响应通过 JSON-RPC id 字段匹配：
 * - 发送请求时注册 CompletableFuture 到 pendingRequests
 * - 读取线程从 stdout 解析响应，按 id 完成对应的 Future
 */
public class StdioTransport implements McpTransport {

    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final String serverName;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final ObjectMapper mapper;

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread stderrThread;

    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonRpcResponse>> pendingRequests
            = new ConcurrentHashMap<>();

    public StdioTransport(String serverName, String command, List<String> args,
                          Map<String, String> env, ObjectMapper mapper) {
        this.serverName = serverName;
        this.command = command;
        this.args = args;
        this.env = env;
        this.mapper = mapper;
    }

    @Override
    public void start() throws IOException {
        List<String> cmdList = new ArrayList<>();
        cmdList.add(command);
        cmdList.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.redirectErrorStream(false);

        // 设置环境变量
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        startReaderThread();
        startStderrThread();
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request) throws IOException {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Use sendNotification() for requests without id");
        }

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(request.getId(), future);

        writeMessage(request);

        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(request.getId());
            throw new IOException("MCP request timed out after " + DEFAULT_TIMEOUT_MS + "ms: " + request.getMethod());
        } catch (ExecutionException e) {
            pendingRequests.remove(request.getId());
            throw new IOException("MCP request failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            pendingRequests.remove(request.getId());
            Thread.currentThread().interrupt();
            throw new IOException("MCP request interrupted: " + request.getMethod());
        }
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        JsonRpcRequest notification = JsonRpcRequest.notification(method, params);
        writeMessage(notification);
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public void close() throws IOException {
        // 完成所有 pending 请求
        for (CompletableFuture<JsonRpcResponse> f : pendingRequests.values()) {
            f.completeExceptionally(new IOException("MCP transport closed"));
        }
        pendingRequests.clear();

        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
        }
        if (process != null) {
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
    }

    /** 生成下一个 JSON-RPC request id */
    public int nextId() {
        return idCounter.getAndIncrement();
    }

    // ==================== 内部方法 ====================

    private void writeMessage(JsonRpcRequest request) throws IOException {
        String json = mapper.writeValueAsString(request);
        synchronized (writer) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    /**
     * 启动 stdout 读取守护线程
     *
     * 持续从子进程 stdout 读取行，解析 JSON-RPC 响应，
     * 按 id 匹配并完成对应的 CompletableFuture。
     */
    private void startReaderThread() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JsonRpcResponse response = mapper.readValue(line, JsonRpcResponse.class);
                        if (response.getId() != null) {
                            CompletableFuture<JsonRpcResponse> future = pendingRequests.remove(response.getId());
                            if (future != null) {
                                future.complete(response);
                            }
                        }
                        // id 为 null 的是 Server 发来的通知，当前忽略
                    } catch (Exception e) {
                        System.err.println("[MCP:" + serverName + "] Failed to parse response: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                // 子进程退出时流关闭，正常退出
            }
            // 进程结束，完成所有 pending 请求为异常
            for (CompletableFuture<JsonRpcResponse> f : pendingRequests.values()) {
                f.completeExceptionally(new IOException("MCP server '" + serverName + "' disconnected"));
            }
        });
        readerThread.setDaemon(true);
        readerThread.setName("mcp-reader-" + serverName);
        readerThread.start();
    }

    /**
     * 启动 stderr 读取守护线程
     *
     * 将子进程的 stderr 输出到 System.err，便于调试。
     */
    private void startStderrThread() {
        BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        stderrThread = new Thread(() -> {
            try {
                String line;
                while ((line = stderrReader.readLine()) != null) {
                    System.err.println("[MCP:" + serverName + "] " + line);
                }
            } catch (IOException ignored) {}
        });
        stderrThread.setDaemon(true);
        stderrThread.setName("mcp-stderr-" + serverName);
        stderrThread.start();
    }
}

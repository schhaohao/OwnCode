package com.claudecode.mcp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/**
 * MCP 客户端 — 管理与单个 MCP Server 的完整生命周期
 *
 * 协议流程：
 *   1. initialize() 握手 + notifications/initialized 通知
 *   2. listTools() 发现工具
 *   3. callTool() 调用工具（运行时按需调用）
 *   4. close() 关闭连接
 */
public class McpClient implements Closeable {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String CLIENT_NAME = "claude-code-java";
    private static final String CLIENT_VERSION = "1.0";

    private final String serverName;
    private final McpTransport transport;
    private final ObjectMapper mapper;

    private String serverVersion;
    private boolean initialized = false;

    public McpClient(String serverName, McpTransport transport, ObjectMapper mapper) {
        this.serverName = serverName;
        this.transport = transport;
        this.mapper = mapper;
    }

    /**
     * 启动传输并完成 MCP initialize 握手
     */
    public void initialize() throws IOException {
        // 启动传输（启动子进程）
        transport.start();

        // 构建 initialize 请求参数
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Collections.emptyMap());

        Map<String, String> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        params.put("clientInfo", clientInfo);

        // 发送 initialize 请求
        int id = ((StdioTransport) transport).nextId();
        JsonRpcRequest request = JsonRpcRequest.request(id, "initialize", params);
        JsonRpcResponse response = transport.send(request);

        if (response.isError()) {
            throw new IOException("MCP initialize failed for '" + serverName + "': "
                    + response.getError().getMessage());
        }

        // 解析 server info
        if (response.getResult() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getResult();
            Object serverInfo = result.get("serverInfo");
            if (serverInfo instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> info = (Map<String, Object>) serverInfo;
                this.serverVersion = (String) info.get("version");
            }
        }

        // 发送 notifications/initialized 通知
        transport.sendNotification("notifications/initialized", null);

        initialized = true;
        System.err.println("[MCP:" + serverName + "] Connected (version: " + serverVersion + ")");
    }

    /**
     * 调用 tools/list 获取该 Server 暴露的所有工具定义
     */
    public List<McpToolDefinition> listTools() throws IOException {
        ensureInitialized();

        int id = ((StdioTransport) transport).nextId();
        JsonRpcRequest request = JsonRpcRequest.request(id, "tools/list", Collections.emptyMap());
        JsonRpcResponse response = transport.send(request);

        if (response.isError()) {
            throw new IOException("MCP tools/list failed for '" + serverName + "': "
                    + response.getError().getMessage());
        }

        // 解析工具列表
        List<McpToolDefinition> tools = new ArrayList<>();
        if (response.getResult() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getResult();
            Object toolsObj = result.get("tools");
            if (toolsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> toolList = (List<Object>) toolsObj;
                for (Object item : toolList) {
                    McpToolDefinition def = mapper.convertValue(item, McpToolDefinition.class);
                    tools.add(def);
                }
            }
        }

        System.err.println("[MCP:" + serverName + "] Discovered " + tools.size() + " tools");
        return tools;
    }

    /**
     * 调用 tools/call 执行指定工具
     *
     * @param toolName  工具原始名称（不含 mcp__ 前缀）
     * @param arguments 工具参数
     * @return 调用结果
     */
    public McpToolCallResult callTool(String toolName, Map<String, Object> arguments) throws IOException {
        ensureInitialized();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Collections.emptyMap());

        int id = ((StdioTransport) transport).nextId();
        JsonRpcRequest request = JsonRpcRequest.request(id, "tools/call", params);
        JsonRpcResponse response = transport.send(request);

        if (response.isError()) {
            return McpToolCallResult.error(
                    "MCP tool call failed: " + response.getError().getMessage());
        }

        // 解析 tools/call 的响应
        return parseToolCallResult(response.getResult());
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }

    public String getServerName() { return serverName; }
    public boolean isAlive() { return transport.isAlive(); }

    // ==================== 内部方法 ====================

    private void ensureInitialized() throws IOException {
        if (!initialized) {
            throw new IOException("McpClient for '" + serverName + "' not initialized. Call initialize() first.");
        }
        if (!transport.isAlive()) {
            throw new IOException("MCP server '" + serverName + "' is not running.");
        }
    }

    /**
     * 解析 tools/call 的 result 字段
     *
     * 格式：{"content": [{"type":"text","text":"..."}], "isError": false}
     */
    private McpToolCallResult parseToolCallResult(Object result) {
        if (!(result instanceof Map)) {
            return McpToolCallResult.error("Unexpected tools/call result format");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;

        boolean isError = Boolean.TRUE.equals(resultMap.get("isError"));

        StringBuilder textBuilder = new StringBuilder();
        Object contentObj = resultMap.get("content");
        if (contentObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> contentList = (List<Object>) contentObj;
            for (Object item : contentList) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) item;
                    String type = (String) block.get("type");
                    if ("text".equals(type)) {
                        if (textBuilder.length() > 0) textBuilder.append("\n");
                        textBuilder.append(block.get("text"));
                    }
                }
            }
        }

        String text = textBuilder.length() > 0 ? textBuilder.toString() : "(empty response)";
        return isError ? McpToolCallResult.error(text) : McpToolCallResult.success(text);
    }

    // ==================== 数据类 ====================

    /**
     * MCP Server 返回的工具定义
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpToolDefinition {

        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("inputSchema")
        private Map<String, Object> inputSchema;

        public McpToolDefinition() {}

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, Object> getInputSchema() {
            return inputSchema != null ? inputSchema : Collections.emptyMap();
        }
    }

    /**
     * MCP 工具调用结果
     */
    public static class McpToolCallResult {
        private final String text;
        private final boolean isError;

        private McpToolCallResult(String text, boolean isError) {
            this.text = text;
            this.isError = isError;
        }

        public static McpToolCallResult success(String text) {
            return new McpToolCallResult(text, false);
        }

        public static McpToolCallResult error(String text) {
            return new McpToolCallResult(text, true);
        }

        public String getText() { return text; }
        public boolean isError() { return isError; }
    }
}

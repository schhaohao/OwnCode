package com.claudecode.mcp;

import com.claudecode.mcp.client.McpClient;
import com.claudecode.mcp.client.McpClient.McpToolDefinition;
import com.claudecode.mcp.client.StdioTransport;
import com.claudecode.mcp.config.McpServerConfig;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 管理器 — MCP 子系统的总入口
 *
 * 协调完整流程：加载配置 → 连接各 MCP Server → 发现工具 → 注册到 ToolRegistry
 *
 * 设计原则：
 * - 单个 Server 连接失败不影响其他 Server（只打印警告）
 * - 所有 MCP 工具通过 McpToolAdapter 适配为 Tool 接口，对 AgentLoop 透明
 * - 实现 Closeable，程序退出时清理所有子进程
 */
public class McpManager implements Closeable {

    private final ObjectMapper mapper;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpManager(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 初始化所有配置的 MCP Server 并将其工具注册到 ToolRegistry
     *
     * @param configs  serverName → McpServerConfig 的映射
     * @param registry 工具注册中心（MCP 工具将注册到这里）
     */
    public void initializeAndRegister(Map<String, McpServerConfig> configs, ToolRegistry registry) {
        if (configs == null || configs.isEmpty()) {
            return;
        }

        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig config = entry.getValue();

            try {
                connectAndRegister(serverName, config, registry);
            } catch (Exception e) {
                System.err.println("[MCP] Failed to connect to server '" + serverName + "': " + e.getMessage());
                // 单个 Server 失败不影响整体启动
            }
        }
    }

    /**
     * 连接单个 MCP Server，发现工具并注册
     */
    private void connectAndRegister(String serverName, McpServerConfig config,
                                    ToolRegistry registry) throws IOException {
        // 1. 创建 Transport
        StdioTransport transport = new StdioTransport(
                serverName,
                config.getCommand(),
                config.getArgs(),
                config.getEnv(),
                mapper);

        // 2. 创建 Client 并初始化（握手）
        McpClient client = new McpClient(serverName, transport, mapper);
        client.initialize();
        clients.put(serverName, client);

        // 3. 发现工具
        List<McpToolDefinition> tools = client.listTools();

        // 4. 为每个工具创建适配器并注册
        for (McpToolDefinition toolDef : tools) {
            McpToolAdapter adapter = new McpToolAdapter(
                    serverName,
                    toolDef.getName(),
                    toolDef.getDescription(),
                    toolDef.getInputSchema(),
                    client);

            try {
                registry.register(adapter);
            } catch (IllegalArgumentException e) {
                System.err.println("[MCP] Skipping duplicate tool: " + adapter.name());
            }
        }
    }

    /**
     * 关闭所有 MCP Client（销毁子进程）
     */
    @Override
    public void close() throws IOException {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                System.err.println("[MCP] Error closing server '" + entry.getKey() + "': " + e.getMessage());
            }
        }
        clients.clear();
    }

    /** 获取已连接的 MCP Server 数量 */
    public int getConnectedServerCount() {
        return clients.size();
    }
}

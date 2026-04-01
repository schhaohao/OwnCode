package com.claudecode.mcp;

import com.claudecode.mcp.client.McpClient;
import com.claudecode.mcp.client.McpClient.McpToolCallResult;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.util.Map;

/**
 * MCP 工具适配器 — 将 MCP Server 的远程工具适配为本地 Tool 接口
 *
 * 这是整个 MCP 集成的核心桥接层。每个 MCP 工具对应一个 McpToolAdapter 实例，
 * 注册到 ToolRegistry 后，AgentLoop 无法区分它和内置工具——完全透明。
 *
 * 工具名称格式：mcp__<serverName>__<toolName>
 * 例如：mcp__filesystem__read_file, mcp__context7__query-docs
 */
public class McpToolAdapter implements Tool {

    /** 名称分隔符（与 Claude Code 官方一致） */
    private static final String SEPARATOR = "__";
    private static final String PREFIX = "mcp";

    private final String serverName;
    private final String originalToolName;
    private final String qualifiedName;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final McpClient mcpClient;

    public McpToolAdapter(String serverName, String originalToolName,
                          String description, Map<String, Object> inputSchema,
                          McpClient mcpClient) {
        this.serverName = serverName;
        this.originalToolName = originalToolName;
        this.qualifiedName = PREFIX + SEPARATOR + serverName + SEPARATOR + originalToolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.mcpClient = mcpClient;
    }

    @Override
    public String name() {
        return qualifiedName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    /**
     * MCP 工具默认需要权限审批（安全第一）
     * 外部 Server 的工具行为不可预测，所以默认走 Human-in-the-loop
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    /**
     * 执行 MCP 工具：通过 McpClient 发送 tools/call JSON-RPC 请求
     */
    @Override
    public ToolResult execute(Map<String, Object> input) {
        try {
            McpToolCallResult result = mcpClient.callTool(originalToolName, input);
            return result.isError()
                    ? ToolResult.error(result.getText())
                    : ToolResult.success(result.getText());
        } catch (Exception e) {
            return ToolResult.error("MCP tool '" + qualifiedName + "' failed: " + e.getMessage());
        }
    }

    /** 获取所属 MCP Server 名称 */
    public String getServerName() { return serverName; }

    /** 获取工具在 MCP Server 中的原始名称 */
    public String getOriginalToolName() { return originalToolName; }
}

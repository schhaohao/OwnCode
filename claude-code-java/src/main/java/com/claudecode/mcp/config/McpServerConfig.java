package com.claudecode.mcp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP Server 的配置
 *
 * 对应 settings.json 中 mcpServers 下的一个条目：
 * {
 *   "command": "npx",
 *   "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
 *   "env": {"NODE_ENV": "production"}
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {

    @JsonProperty("command")
    private String command;

    @JsonProperty("args")
    private List<String> args;

    @JsonProperty("env")
    private Map<String, String> env;

    public McpServerConfig() {}

    public McpServerConfig(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args;
        this.env = env;
    }

    public String getCommand() { return command; }

    public List<String> getArgs() {
        return args != null ? args : Collections.emptyList();
    }

    public Map<String, String> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }
}

package com.claudecode.mcp.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 配置文件加载器
 *
 * 搜索路径（优先级从高到低）：
 * 1. <workingDir>/.claude-code-java/settings.json
 * 2. ~/.claude-code-java/settings.json
 *
 * 项目级配置覆盖用户级的同名 Server。
 * env 值支持 ${ENV_VAR} 语法，从 System.getenv() 解析。
 */
public class McpConfigLoader {

    private static final String CONFIG_DIR = ".claude-code-java";
    private static final String CONFIG_FILE = "settings.json";
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ObjectMapper mapper;

    public McpConfigLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 加载 MCP Server 配置
     *
     * @param workingDirectory 项目工作目录
     * @return serverName → McpServerConfig 的映射，无配置时返回空 Map
     */
    public Map<String, McpServerConfig> load(String workingDirectory) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 先加载用户级（低优先级）
        File userConfig = new File(
                System.getProperty("user.home"), CONFIG_DIR + File.separator + CONFIG_FILE);
        merged.putAll(loadFromFile(userConfig));

        // 再加载项目级（高优先级，覆盖同名）
        if (workingDirectory != null) {
            File projectConfig = new File(
                    workingDirectory, CONFIG_DIR + File.separator + CONFIG_FILE);
            merged.putAll(loadFromFile(projectConfig));
        }

        // 解析环境变量
        for (McpServerConfig config : merged.values()) {
            resolveEnvVars(config);
        }

        return merged;
    }

    private Map<String, McpServerConfig> loadFromFile(File file) {
        if (!file.exists() || !file.isFile()) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = mapper.readTree(file);
            JsonNode mcpServers = root.get("mcpServers");
            if (mcpServers == null || !mcpServers.isObject()) {
                return Collections.emptyMap();
            }
            return mapper.convertValue(mcpServers,
                    new TypeReference<LinkedHashMap<String, McpServerConfig>>() {});
        } catch (IOException e) {
            System.err.println("[MCP] Failed to load config from " + file.getAbsolutePath() + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 将 env 中的 ${ENV_VAR} 替换为实际环境变量值
     */
    private void resolveEnvVars(McpServerConfig config) {
        Map<String, String> env = config.getEnv();
        if (env.isEmpty()) return;

        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            resolved.put(entry.getKey(), resolveEnvValue(entry.getValue()));
        }
        // env 是从 Jackson 反序列化来的可变 Map，直接清空重填
        env.clear();
        env.putAll(resolved);
    }

    private String resolveEnvValue(String value) {
        if (value == null) return null;
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String envValue = System.getenv(envName);
            matcher.appendReplacement(sb, envValue != null ? Matcher.quoteReplacement(envValue) : "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

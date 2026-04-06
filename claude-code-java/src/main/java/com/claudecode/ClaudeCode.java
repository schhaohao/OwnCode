package com.claudecode;

import com.claudecode.api.ClaudeApiClient;
import com.claudecode.cli.Repl;
import com.claudecode.cli.TerminalRenderer;
import com.claudecode.command.CommandRegistry;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.ForkExecutor;
import com.claudecode.mcp.McpManager;
import com.claudecode.mcp.config.McpConfigLoader;
import com.claudecode.mcp.config.McpServerConfig;
import com.claudecode.permission.PermissionManager;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.SkillTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.Map;

/**
 * 程序入口 — 整个应用的启动点
 *
 * 职责：
 * 1. 解析命令行参数（如 --api-key, --model 等）
 * 2. 读取环境变量 ANTHROPIC_API_KEY（如果命令行没传的话）
 * 3. 初始化各核心组件：
 *    - ClaudeApiClient（API通信）
 *    - ToolRegistry（注册所有内置工具）
 *    - PermissionManager（权限管理）
 *    - ConversationHistory（对话历史）
 *    - AgentLoop（核心循环引擎）
 * 4. 创建 Repl 实例，启动交互式命令行循环
 *
 * 启动流程：
 *   main()
 *     → 解析参数 & 读取API Key
 *     → new ClaudeApiClient(apiKey, model)
 *     → new ToolRegistry() → 注册所有Tool实现
 *     → new PermissionManager()
 *     → new AgentLoop(apiClient, toolRegistry, permissionManager)
 *     → new Repl(agentLoop) → repl.start()
 *
 * 提示：
 * - API Key 优先从命令行参数获取，其次从环境变量 ANTHROPIC_API_KEY 获取
 * - 如果都没有，打印错误信息并退出
 * - 默认模型可以设为 "claude-sonnet-4-6"
 */
public class ClaudeCode {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    public static void main(String[] args) {
        // 1. 解析命令行参数
        String apiKey = null;
        String model = DEFAULT_MODEL;
        String baseUrl = null;
        String systemPrompt = loadSystemPrompt();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--api-key":
                    if (i + 1 < args.length) apiKey = args[++i];
                    break;
                case "--model":
                    if (i + 1 < args.length) model = args[++i];
                    break;
                case "--base-url":
                    if (i + 1 < args.length) baseUrl = args[++i];
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }

        // 2. 如果命令行没传，从环境变量获取（使用独立前缀，避免与官方 Claude Code 冲突）
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("CCJ_API_KEY");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = System.getenv("CCJ_BASE_URL");
        }

        // 3. 如果还没有，从 settings.json 读取持久化配置
        String workingDirectory = System.getProperty("user.dir");
        JsonNode settings = loadSettings(workingDirectory);
        if (settings != null) {
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = getSettingsString(settings, "apiKey");
            }
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = getSettingsString(settings, "baseUrl");
            }
            String settingsModel = getSettingsString(settings, "model");
            if (settingsModel != null && model.equals(DEFAULT_MODEL)) {
                model = settingsModel;
            }
        }

        // 4. 没有 API Key 则退出
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: API key is required.");
            System.err.println("Provide it via: --api-key, CCJ_API_KEY env var, or settings.json");
            System.exit(1);
        }

        try {
            // 5. 初始化各核心组件

            ClaudeApiClient apiClient = baseUrl != null
                    ? new ClaudeApiClient(apiKey, model, baseUrl)
                    : new ClaudeApiClient(apiKey, model);

            ToolRegistry toolRegistry = new ToolRegistry(workingDirectory);
            toolRegistry.registerBuiltinTools();

            // MCP 工具注册：从 settings.json 加载 MCP Server 配置，连接并发现工具
            ObjectMapper objectMapper = new ObjectMapper();
            McpConfigLoader configLoader = new McpConfigLoader(objectMapper);
            Map<String, McpServerConfig> mcpConfigs = configLoader.load(workingDirectory);
            McpManager mcpManager = new McpManager(objectMapper);
            if (!mcpConfigs.isEmpty()) {
                mcpManager.initializeAndRegister(mcpConfigs, toolRegistry);
            }

            // Skill 系统初始化：
            // 1. 创建 CommandRegistry，扫描 ~/.claude/skills/ 和 .claude/skills/ 目录
            // 2. 解析所有 SKILL.md 文件，注册为 PromptCommand
            // 3. 创建 ForkExecutor（Skill Fork 模式的执行器，共享 apiClient/toolRegistry/permissionManager）
            // 4. 创建 SkillTool（Skill 与 Tool 体系的桥梁，支持 Inline + Fork 两种模式）
            //    并注册到 ToolRegistry，这样 LLM 就可以通过标准的 tool_use 调用机制来触发 Skill
            CommandRegistry commandRegistry = new CommandRegistry(workingDirectory);
            commandRegistry.initialize();

            PermissionManager permissionManager = new PermissionManager();
            TerminalRenderer terminalRenderer = new TerminalRenderer();

            // 创建 ForkExecutor：Skill Fork 模式的核心执行器
            // 它持有与主 AgentLoop 共享的依赖（apiClient, toolRegistry, permissionManager）
            // 每次 fork 执行时，ForkExecutor 会利用这些依赖创建独立的子 AgentLoop
            ForkExecutor forkExecutor = new ForkExecutor(
                    apiClient, toolRegistry, permissionManager,
                    terminalRenderer, System.out::print);

            toolRegistry.register(new SkillTool(commandRegistry, forkExecutor));

            // 创建 AgentLoop 时传入 CommandRegistry 和共享的 TerminalRenderer，
            // 它会在 buildRequest() 中将 Skill 列表注入系统提示词
            AgentLoop agentLoop = new AgentLoop(
                    apiClient, toolRegistry, permissionManager,
                    systemPrompt, commandRegistry,
                    terminalRenderer, System.out::print);

            // 6. 启动交互式命令行循环
            Repl repl = new Repl(agentLoop);
            repl.setConnectionInfo(model, baseUrl, apiKey);
            repl.setCommandRegistry(commandRegistry);  // 注入 CommandRegistry，启用 /skill 路由

            // 注入 JLine LineReader 到 PermissionManager，避免 Scanner/JLine 抢 System.in
            permissionManager.setInputReader(prompt -> repl.readLine(prompt));

            // 程序退出时清理 MCP 子进程
            McpManager finalMcpManager = mcpManager;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { finalMcpManager.close(); } catch (IOException ignored) {}
            }));

            repl.start();

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: claude-code-java [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --api-key <key>    API key (or set CCJ_API_KEY env var, or settings.json)");
        System.out.println("  --base-url <url>   Custom API base URL (or set CCJ_BASE_URL env var, or settings.json)");
        System.out.println("  --model <model>    Model name (default: " + DEFAULT_MODEL + ", or settings.json)");
        System.out.println("  --help             Show this help message");
    }

    /**
     * 从 settings.json 加载配置
     *
     * 搜索路径（项目级覆盖用户级）：
     * 1. ~/.claude-code-java/settings.json
     * 2. <workingDir>/.claude-code-java/settings.json
     */
    private static JsonNode loadSettings(String workingDirectory) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode merged = null;

        // 用户级
        File userFile = new File(System.getProperty("user.home"),
                ".claude-code-java" + File.separator + "settings.json");
        merged = readJsonFile(mapper, userFile);

        // 项目级（覆盖用户级）
        if (workingDirectory != null) {
            File projectFile = new File(workingDirectory,
                    ".claude-code-java" + File.separator + "settings.json");
            JsonNode projectSettings = readJsonFile(mapper, projectFile);
            if (projectSettings != null) {
                merged = projectSettings;
            }
        }

        return merged;
    }

    private static JsonNode readJsonFile(ObjectMapper mapper, File file) {
        if (file.exists() && file.isFile()) {
            try {
                return mapper.readTree(file);
            } catch (IOException e) {
                System.err.println("[Config] Failed to read " + file.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static String getSettingsString(JsonNode settings, String key) {
        JsonNode node = settings.get(key);
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    /**
     * 从 classpath 加载 system-prompt.txt
     */
    private static String loadSystemPrompt() {
        try (InputStream is = ClaudeCode.class.getResourceAsStream("/system-prompt.txt")) {
            if (is == null) {
                System.err.println("[Config] system-prompt.txt not found in classpath, using fallback.");
                return "You are a helpful coding assistant.";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            System.err.println("[Config] Failed to load system-prompt.txt: " + e.getMessage());
            return "You are a helpful coding assistant.";
        }
    }
}

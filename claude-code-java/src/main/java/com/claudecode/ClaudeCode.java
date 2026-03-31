package com.claudecode;

import com.claudecode.api.ClaudeApiClient;
import com.claudecode.cli.Repl;
import com.claudecode.core.AgentLoop;
import com.claudecode.permission.PermissionManager;
import com.claudecode.tool.ToolRegistry;

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

    private static final String SYSTEM_PROMPT =
            "You are an interactive agent that helps users with software engineering tasks. "
            + "You have access to tools for reading files, editing files, executing shell commands, "
            + "searching code, and more. Use the tools to accomplish the user's requests. "
            + "Be concise and direct in your responses. "
            + "When you need to explore the codebase, use the appropriate tools. "
            + "When modifying code, read the file first to understand the context.";

    public static void main(String[] args) {
        // 1. 解析命令行参数
        String apiKey = null;
        String model = DEFAULT_MODEL;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--api-key":
                    if (i + 1 < args.length) apiKey = args[++i];
                    break;
                case "--model":
                    if (i + 1 < args.length) model = args[++i];
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }

        // 2. 如果命令行没传 API Key，从环境变量获取
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("ANTHROPIC_API_KEY");
        }

        // 3. 没有 API Key 则退出
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: API key is required.");
            System.err.println("Set the ANTHROPIC_API_KEY environment variable or use --api-key <key>");
            System.exit(1);
        }

        try {
            // 4. 初始化各核心组件
            String workingDirectory = System.getProperty("user.dir");

            ClaudeApiClient apiClient = new ClaudeApiClient(apiKey, model);

            ToolRegistry toolRegistry = new ToolRegistry(workingDirectory);
            toolRegistry.registerBuiltinTools();

            PermissionManager permissionManager = new PermissionManager();

            AgentLoop agentLoop = new AgentLoop(
                    apiClient, toolRegistry, permissionManager, SYSTEM_PROMPT);

            // 5. 启动交互式命令行循环
            Repl repl = new Repl(agentLoop);

            // 注入 JLine LineReader 到 PermissionManager，避免 Scanner/JLine 抢 System.in
            permissionManager.setInputReader(prompt -> repl.readLine(prompt));

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
        System.out.println("  --api-key <key>    Anthropic API key (or set ANTHROPIC_API_KEY env var)");
        System.out.println("  --model <model>    Model name (default: " + DEFAULT_MODEL + ")");
        System.out.println("  --help             Show this help message");
    }
}

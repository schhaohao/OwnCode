package com.claudecode;

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

    public static void main(String[] args) {
        // TODO: 实现启动逻辑
    }
}

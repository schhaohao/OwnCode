package com.claudecode.tool;

/**
 * ToolRegistry — 工具注册中心
 *
 * 核心职责：
 *   集中管理所有可用工具，提供注册、查找、执行的统一入口。
 *   AgentLoop 不直接持有工具实例，而是通过 ToolRegistry 来操作。
 *
 * 需要实现的方法：
 *
 * 1. public void register(Tool tool)
 *    - 注册一个工具
 *    - 用 tool.name() 作为 key 存入内部 Map
 *    - 如果 name 重复，抛出异常或覆盖（建议抛异常，便于发现配置错误）
 *
 * 2. public Tool getTool(String name)
 *    - 根据名称查找工具
 *    - 找不到时返回 null 或抛异常
 *
 * 3. public ToolResult execute(String toolName, Map<String, Object> input)
 *    - 便捷方法：查找工具 + 执行
 *    - 找不到工具时返回 ToolResult.error("Unknown tool: " + toolName)
 *    - 执行异常时捕获并返回 ToolResult.error(异常信息)
 *
 * 4. public List<ToolDefinition> getAllDefinitions()
 *    - 返回所有已注册工具的 ToolDefinition 列表
 *    - 供 AgentLoop 构建 API 请求时使用
 *    - 每个 ToolDefinition 从 Tool 的 name(), description(), inputSchema() 构建
 *
 * 5. public void registerBuiltinTools()
 *    - 注册所有内置工具（建议在这里集中注册，清晰明了）
 *    - register(new ReadFileTool());
 *    - register(new BashTool());
 *    - register(new EditFileTool());
 *    - register(new WriteFileTool());
 *    - register(new GlobTool());
 *    - register(new GrepTool());
 *
 * 内部存储：
 *   private Map<String, Tool> tools = new LinkedHashMap<>();
 *   （用 LinkedHashMap 保持注册顺序，序列化到 API 时顺序稳定）
 */
public class ToolRegistry {

    // TODO: 实现
}

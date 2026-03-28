package com.claudecode.tool;

import com.claudecode.api.model.ToolDefinition;
import com.claudecode.tool.impl.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 *
 * @author sunchenhao
 * @date 2026/3/28
 */
public class ToolRegistry {

    /**
     * 工具存储：name → Tool 实例
     * 用 LinkedHashMap 保持注册顺序，序列化到 API 的 tools 数组时顺序稳定
     */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 工作目录，BashTool 等需要 */
    private final String workingDirectory;

    public ToolRegistry(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * 注册一个工具
     *
     * @throws IllegalArgumentException 如果同名工具已存在（防止配置错误）
     */
    public void register(Tool tool) {
        String name = tool.name();
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        tools.put(name, tool);
    }

    /**
     * 根据名称查找工具
     *
     * @return 工具实例，找不到时返回 null
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 便捷方法：查找工具 + 执行
     *
     * 封装了两层安全保护：
     * 1. 工具不存在 → 返回 error（LLM 会据此调整策略）
     * 2. 执行抛异常 → 捕获并返回 error（不让异常传播到 AgentLoop）
     */
    public ToolResult execute(String toolName, Map<String, Object> input) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + toolName);
        }
        try {
            return tool.execute(input);
        } catch (Exception e) {
            return ToolResult.error("Tool '" + toolName + "' threw exception: " + e.getMessage());
        }
    }

    /**
     * 返回所有已注册工具的 ToolDefinition 列表
     *
     * 使用场景：AgentLoop 构建 API 请求时
     *   ApiRequest.builder()
     *       .tools(toolRegistry.getAllDefinitions())
     *       ...
     */
    public List<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(ToolDefinition::fromTool)
                .collect(Collectors.toList());
    }

    /**
     * 注册所有内置工具
     *
     * 只注册已实现的工具（name 非空的）；
     * 未完成的工具（name 为空）跳过，避免注册空名工具
     */
    public void registerBuiltinTools() {
        registerIfReady(new ReadFileTool());
        registerIfReady(new BashTool(workingDirectory));
        registerIfReady(new EditFileTool());
        registerIfReady(new WriteFileTool());
        registerIfReady(new GlobTool());
        registerIfReady(new GrepTool());
    }

    /**
     * 只在工具已实现（name 非空）时注册
     * 未完成的工具 stub 返回空字符串 name，跳过即可
     */
    private void registerIfReady(Tool tool) {
        if (tool.name() != null && !tool.name().isEmpty()) {
            register(tool);
        }
    }

    /** 已注册工具数量 */
    public int size() {
        return tools.size();
    }
}

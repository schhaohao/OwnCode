package com.claudecode.tool;

/**
 * Tool — 工具接口，所有工具的抽象契约
 *
 * 每个工具都必须实现这个接口。工具是 Claude Code 的核心扩展点——
 * LLM 通过调用工具来与外部世界交互（读文件、执行命令、搜索代码等）。
 *
 * 一个工具的生命周期：
 *   1. 启动时通过 ToolRegistry 注册
 *   2. AgentLoop 构建 API 请求时，从每个工具获取 ToolDefinition（name + description + schema）
 *   3. LLM 返回 tool_use 时，AgentLoop 通过 name 查找工具，调用 execute()
 *   4. execute() 的返回值包装为 tool_result 反馈给 LLM
 *
 * 需要定义的方法：
 *
 * 1. String name()
 *    - 返回工具名称，如 "Read", "Bash", "Edit"
 *    - 必须匹配正则: ^[a-zA-Z0-9_-]{1,64}$
 *    - LLM 在 tool_use 中通过这个名字引用工具
 *
 * 2. String description()
 *    - 返回工具的详细描述（给 LLM 看的）
 *    - 这是 LLM 决定是否使用这个工具的核心依据
 *    - 应该包含：工具的功能、使用场景、参数含义、注意事项
 *    - 建议至少 3-4 句话，越清晰越好
 *
 * 3. Map<String, Object> inputSchema()
 *    - 返回 JSON Schema 格式的输入参数定义
 *    - 必须是 {"type": "object", "properties": {...}, "required": [...]} 结构
 *    - 示例：
 *      {
 *        "type": "object",
 *        "properties": {
 *          "file_path": {"type": "string", "description": "文件绝对路径"},
 *          "limit": {"type": "integer", "description": "最多读取行数"}
 *        },
 *        "required": ["file_path"]
 *      }
 *
 * 4. boolean requiresPermission()
 *    - 是否需要用户审批才能执行
 *    - true: 写入类工具（Bash, Edit, Write）— 执行前需要用户确认
 *    - false: 只读类工具（Read, Glob, Grep）— 自动执行
 *
 * 5. ToolResult execute(Map<String, Object> input)
 *    - 执行工具的核心方法
 *    - input 是 LLM 传入的参数（已从 JSON 解析为 Map）
 *    - 返回 ToolResult（包含执行结果文本 + 是否出错标记）
 *    - 实现时要注意：
 *      a. 参数校验：检查必填参数是否存在
 *      b. 异常处理：捕获异常，返回 ToolResult.error() 而不是抛出
 *      c. 输出限制：如果结果过大（如读取大文件），应截断
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public interface Tool {

    String name();

    String description();

    java.util.Map<String, Object> inputSchema();

    boolean requiresPermission();

    ToolResult execute(java.util.Map<String, Object> input);
}

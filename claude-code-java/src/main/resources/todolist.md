建议实现顺序
从底层往上，从简单到复杂：

顺序	文件	说明
1	ToolResult	最简单，2个字段 + 2个工厂方法
2	ContentBlock	数据模型，选择继承方案或单类方案
3	Message	数据模型，依赖 ContentBlock
4	ToolDefinition	数据模型，3个字段
5	ApiRequest / ApiResponse	数据模型，Builder模式练手
6	Tool 接口	已写好，只需确认
7	ReadFileTool	第一个工具实现，最简单的只读工具
8	BashTool	ProcessBuilder 执行命令
9	ToolRegistry	Map + 注册/查找逻辑
10	ClaudeApiClient	HTTP 调用（先做非流式版本）
11	ConversationHistory	List 管理
12	PermissionManager	简化版：只看 requiresPermission
13	AgentLoop	核心，组装上面所有组件
14	Repl	JLine3 交互循环
15	ClaudeCode	入口，串联一切
16	StreamAssembler	SSE解析（可后期从非流式升级为流式）
17-20	其余工具和渲染	按需补充
每个文件里都有详细的中文注释，说明了要做什么、怎么做、注意什么。打开任意文件就能开始写代码。写不下去了随时问我！
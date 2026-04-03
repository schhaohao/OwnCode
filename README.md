# Claude Code Java

用 Java 复刻的简易版 Claude Code CLI —— 一个基于 Claude API 的交互式编程助手。

> 📖 **学习文档**：[https://schhaohao.github.io/docs/](https://schhaohao.github.io/docs/)

## 技术栈

- Java 11
- OkHttp + SSE（API 通信 & 流式响应）
- Jackson（JSON 序列化）
- JLine3（终端交互）
- JUnit 5（单元测试）

## 项目结构

```
src/main/java/com/claudecode/
├── ClaudeCode.java              # 程序入口
├── core/
│   ├── AgentLoop.java           # 核心 Agent 循环（思考→工具调用→反馈）
│   ├── ConversationHistory.java # 对话历史管理
│   └── ContextManager.java      # 上下文窗口管理 & 压缩
├── api/
│   ├── ClaudeApiClient.java     # Claude API 客户端
│   ├── StreamAssembler.java     # SSE 流式响应解析
│   └── model/                   # API 数据模型
│       ├── ContentBlock.java    # 内容块基类（TextBlock / ToolUseBlock / ToolResultBlock）
│       ├── Message.java         # 对话消息
│       ├── ToolDefinition.java  # 工具定义
│       ├── ApiRequest.java      # API 请求体
│       └── ApiResponse.java     # API 响应体
├── command/                     # Skill/命令系统（Skill = type='prompt' 的 Command）
│   ├── Command.java             # 命令接口（顶层抽象）
│   ├── CommandType.java         # 命令类型枚举（PROMPT / BUILTIN）
│   ├── CommandSource.java       # 命令来源枚举（BUNDLED / DISK / MCP / PLUGIN）
│   ├── PromptCommand.java       # Skill 核心实现（type=prompt 的 Command）
│   ├── CommandRegistry.java     # 命令注册中心（汇聚所有来源）
│   ├── CommandRenderer.java     # 内容渲染器（变量替换 + Shell 预处理）
│   └── loader/
│       ├── SkillFrontmatter.java # YAML frontmatter 解析 POJO
│       └── SkillLoader.java      # 磁盘 SKILL.md 扫描与解析
├── tool/
│   ├── Tool.java                # 工具接口
│   ├── ToolRegistry.java        # 工具注册中心
│   ├── ToolResult.java          # 工具执行结果
│   └── impl/                    # 内置工具实现
│       ├── BashTool.java        # Shell 命令执行
│       ├── ReadFileTool.java    # 文件读取
│       ├── EditFileTool.java    # 文件编辑（字符串替换）
│       ├── WriteFileTool.java   # 文件写入
│       ├── GlobTool.java        # 文件名搜索
│       ├── GrepTool.java        # 文件内容搜索
│       └── SkillTool.java       # Skill 执行入口（Tool ↔ Command 桥梁）
├── mcp/                         # MCP（Model Context Protocol）集成
│   ├── McpManager.java          # MCP 子系统总管理器（生命周期编排）
│   ├── McpToolAdapter.java      # 远程工具 → 本地 Tool 接口的适配器
│   ├── client/
│   │   ├── McpTransport.java    # 传输层抽象接口
│   │   ├── StdioTransport.java  # stdio 传输实现（子进程 stdin/stdout）
│   │   ├── McpClient.java       # 单个 MCP Server 的客户端（握手/发现/调用）
│   │   ├── JsonRpcRequest.java  # JSON-RPC 2.0 请求消息
│   │   └── JsonRpcResponse.java # JSON-RPC 2.0 响应消息
│   └── config/
│       ├── McpConfigLoader.java # 配置加载器（settings.json → McpServerConfig）
│       └── McpServerConfig.java # 单个 MCP Server 配置数据模型
├── permission/
│   ├── PermissionManager.java   # 权限管理
│   └── PermissionRule.java      # 权限规则
└── cli/
    ├── Repl.java                # 交互式命令行
    └── TerminalRenderer.java    # 终端输出渲染
```

## 构建 & 运行

```bash
# 编译
mvn clean compile

# 运行测试
mvn test

# 打包
mvn clean package

# 运行（需要设置 API Key）
cd claude-code-java
mvn clean package -DskipTests

# 指定你自己的 API 地址
java -jar target/claude-code-java-1.0-SNAPSHOT.jar \
  --base-url https://your-api-host.com \
  --api-key your-key \
  --model your-model-name

# 或者用环境变量
export ANTHROPIC_BASE_URL="https://your-api-host.com"
export ANTHROPIC_API_KEY="your-key"
java -jar target/claude-code-java-1.0-SNAPSHOT.jar --model your-model-name

```

## 核心原理

```
用户输入 → 构建请求(system + tools + messages) → 调用 Claude API
                                                       ↓
              ┌─── stop_reason == "end_turn" ← 检查响应
              ↓                                        ↓
         输出结果                          stop_reason == "tool_use"
         等待下次输入                                   ↓
                                               执行工具，收集结果
                                                       ↓
                                               追加到对话历史 → 回到调用 API
```

## Skill 系统

支持通过 SKILL.md 文件定义自定义技能（Skill），让 LLM 按照预设的提示词执行特定任务。

### 工作原理

```
启动时：扫描 ~/.claude-code-java/skills/ 和 .claude-code-java/skills/
           ↓
  解析 SKILL.md（YAML frontmatter + Markdown 正文）
           ↓
  注册到 CommandRegistry → 生成 Skill 列表注入系统提示词
           ↓
  用户 /name 触发 或 LLM 通过 SkillTool 自动调用
           ↓
  CommandRenderer 渲染（$ARGUMENTS 替换 + !`cmd` 预处理）
           ↓
  渲染后的提示词注入对话 → LLM 按指令执行
```

### 创建 Skill

在 `~/.claude-code-java/skills/<name>/SKILL.md` 中创建：

```markdown
---
description: 审查代码质量和效率
allowed-tools:
  - Read
  - Bash
---

你是一个代码审查专家，请检查 $ARGUMENTS 中指定的文件...
```

启动后通过 `/name` 或让 LLM 自动调用。

## MCP（Model Context Protocol）集成

本项目支持通过 MCP 协议接入外部工具服务器，扩展 Agent 的能力边界。

### 工作原理

```
启动时：settings.json → 加载 MCP Server 配置
           ↓
  ProcessBuilder 启动子进程（stdio 通信）
           ↓
  JSON-RPC 握手（initialize + notifications/initialized）
           ↓
  tools/list 发现远程工具
           ↓
  McpToolAdapter 适配为 Tool 接口 → 注册到 ToolRegistry
           ↓
  AgentLoop 透明使用（无法区分内置工具和 MCP 工具）
```

### 配置方式

在 `~/.claude-code-java/settings.json` 或项目目录下 `.claude-code-java/settings.json` 中配置：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": { "NODE_ENV": "production" }
    }
  }
}
```

项目级配置优先于用户级配置，`env` 值支持 `${ENV_VAR}` 环境变量插值。

### 关键设计

- **适配器模式**：`McpToolAdapter` 将远程 MCP 工具适配为本地 `Tool` 接口，AgentLoop 零感知
- **命名规范**：MCP 工具名格式为 `mcp__<serverName>__<toolName>`（与 Claude Code 官方一致）
- **安全第一**：所有 MCP 工具默认需要用户审批（Human-in-the-loop）
- **容错隔离**：单个 Server 连接失败不影响其他 Server 和整体启动
- **进程管理**：JVM 退出时通过 ShutdownHook 清理所有 MCP 子进程

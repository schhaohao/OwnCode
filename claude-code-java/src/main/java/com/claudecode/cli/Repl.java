package com.claudecode.cli;

/**
 * Repl — 交互式命令行循环 (Read-Eval-Print Loop)
 *
 * 核心职责：
 *   提供用户交互界面，接收用户输入，交给 AgentLoop 处理，展示结果。
 *   这是用户直接面对的层，决定了使用体验。
 *
 * 依赖：
 * - AgentLoop: 处理用户输入的核心引擎
 * - JLine3 的 LineReader: 提供命令行编辑、历史记录、自动补全
 * - TerminalRenderer: 输出渲染（颜色、Markdown 等）
 *
 * 需要实现的方法：
 *
 * 1. 构造函数 Repl(AgentLoop agentLoop)
 *    - 初始化 JLine3 Terminal 和 LineReader
 *    - 配置提示符（prompt），如 "claude> " 或 "> "
 *    - 配置历史文件（~/.claude-code-java/history）用于跨会话历史
 *
 * 2. public void start()
 *    - 主循环：
 *      a. 打印欢迎信息
 *      b. while (true):
 *         - 使用 lineReader.readLine("claude> ") 读取用户输入
 *         - 处理特殊命令：
 *           "/exit" 或 "/quit" → 退出
 *           "/clear" → 清空对话历史，开始新会话
 *           "/help"  → 显示帮助信息
 *         - 空输入 → 跳过
 *         - 正常输入 → 调用 agentLoop.run(userInput)
 *         - 捕获异常，打印错误信息但不退出循环
 *      c. 用户退出时打印告别信息
 *
 * 3. private void printWelcome()
 *    - 打印启动信息，如：
 *      ╔══════════════════════════════════════╗
 *      ║       Claude Code Java v1.0          ║
 *      ║   Type /help for available commands   ║
 *      ╚══════════════════════════════════════╝
 *
 * 4. private void printHelp()
 *    - 打印可用命令列表
 *
 * JLine3 初始化示例代码：
 *   Terminal terminal = TerminalBuilder.builder()
 *       .system(true)
 *       .build();
 *   LineReader reader = LineReaderBuilder.builder()
 *       .terminal(terminal)
 *       .history(new DefaultHistory())
 *       .variable(LineReader.HISTORY_FILE, historyPath)
 *       .build();
 *
 * 用户体验注意事项：
 * - AgentLoop.run() 内部会通过回调实时输出流式文本（SSE text_delta）
 *   所以 Repl 不需要等 run() 返回后再显示结果
 * - run() 返回后，打印一个空行作为分隔，准备接收下一轮输入
 * - Ctrl+C 应该中断当前处理（如果有的话），而不是退出程序
 *   可以通过 JLine3 的 signal handler 实现
 */
public class Repl {

    // TODO: 实现
}

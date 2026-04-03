package com.claudecode.cli;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.PromptCommand;
import com.claudecode.core.AgentLoop;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
 *      ║       Own Code Java v1.0          ║
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

    /** 命令行提示符 */
    private static final String PROMPT = "claude> ";

    /** 历史文件目录 */
    private static final String HISTORY_DIR = ".claude-code-java";

    private final AgentLoop agentLoop;
    private final TerminalRenderer renderer;
    private final Terminal terminal;
    private final LineReader lineReader;

    /**
     * 命令注册中心（可选）
     *
     * 用于斜杠命令路由：当用户输入 /name 时，先检查是否是已注册的 Skill，
     * 如果是，则转发给 AgentLoop 处理。
     * 可以为 null（向后兼容：不使用 Skill 系统时）。
     */
    private CommandRegistry commandRegistry;

    private volatile boolean running = true;

    // 启动横幅展示的连接配置
    private String configModel;
    private String configBaseUrl;
    private String configApiKey;

    public Repl(AgentLoop agentLoop) throws IOException {
        this.agentLoop = agentLoop;
        this.renderer = new TerminalRenderer();

        // 初始化 JLine3 终端
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        // 配置历史文件路径：~/.claude-code-java/history
        Path historyDir = Paths.get(System.getProperty("user.home"), HISTORY_DIR);
        Files.createDirectories(historyDir);
        Path historyFile = historyDir.resolve("history");

        // 初始化 LineReader（支持行编辑、历史记录）
        this.lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();
    }

    /**
     * 启动交互式命令行循环
     *
     * 流程：
     *   1. 打印欢迎信息
     *   2. while(true) 循环读取用户输入
     *   3. 处理特殊命令或交给 AgentLoop
     *   4. 捕获异常但不退出循环
     */
    public void start() {
        renderer.renderWelcome(configModel, configBaseUrl, configApiKey);

        try {
            while (running) {
                String input;
                try {
                    input = lineReader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    System.out.println();
                    continue;
                } catch (EndOfFileException e) {
                    printGoodbye();
                    break;
                }

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                String trimmed = input.trim();

                if (handleCommand(trimmed)) {
                    continue;
                }

                try {
                    agentLoop.run(trimmed);
                    System.out.println();
                } catch (Exception e) {
                    renderer.renderError("Failed to process request: " + e.getMessage());
                }
            }
        } finally {
            // 确保 Terminal 资源被正确释放（恢复终端状态）
            try {
                terminal.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * 处理特殊命令
     *
     * 命令路由优先级（从高到低）：
     * 1. 内置命令：/exit, /quit, /clear, /help（硬编码，最高优先级）
     * 2. Skill 命令：/simplify, /commit 等（从 CommandRegistry 动态查找）
     * 3. 未知命令：打印错误提示
     *
     * 当用户输入 /skill-name args 时，处理流程：
     * 1. 从 / 后截取命令名和参数
     * 2. 在 CommandRegistry 中查找是否有匹配的 Skill
     * 3. 如果找到，将其转化为普通用户消息交给 AgentLoop 处理
     *    （AgentLoop 中 LLM 会调用 SkillTool 来执行 Skill）
     * 4. 如果没找到，提示未知命令
     *
     * 为什么不在 Repl 中直接执行 Skill，而是转发给 AgentLoop？
     *   因为 Skill 的执行需要 LLM 参与——Skill 本质是提示词，需要 LLM 来解读和执行。
     *   直接将 /name 转化为用户消息，LLM 看到后会调用 SkillTool，这与官方行为一致。
     *
     * @return true 如果输入是特殊命令（已处理），false 如果是普通输入
     */
    private boolean handleCommand(String input) {
        // 内置命令匹配（最高优先级）
        switch (input.toLowerCase()) {
            case "/exit":
            case "/quit":
                printGoodbye();
                running = false;
                return true;

            case "/clear":
                agentLoop.getHistory().clear();
                renderer.renderSystemMessage("Conversation history cleared.");
                return true;

            case "/help":
                renderHelp();
                return true;

            default:
                // 不是内置命令，检查是否是 / 开头的 Skill 命令
                if (input.startsWith("/")) {
                    return handleSlashCommand(input);
                }
                return false;
        }
    }

    /**
     * 处理斜杠命令（/ 开头的非内置命令）
     *
     * 将 /skill-name args 拆分为命令名和参数，
     * 在 CommandRegistry 中查找匹配的 Skill，
     * 找到则转发给 AgentLoop 处理。
     *
     * 转发方式：将用户输入原样传给 agentLoop.run()。
     * LLM 会看到类似 "/simplify src/main" 的消息，
     * 结合 system-reminder 中的 Skill 列表说明，
     * LLM 会调用 SkillTool 来加载和执行对应的 Skill。
     *
     * @param input 以 / 开头的用户输入
     * @return true 表示已处理（不管是成功执行还是报错）
     */
    private boolean handleSlashCommand(String input) {
        // 从 / 后截取命令名和参数
        // 例如 "/simplify src/main" → cmd="simplify", args="src/main"
        String withoutSlash = input.substring(1);
        String[] parts = withoutSlash.split("\\s+", 2);
        String cmd = parts[0];

        // 检查 CommandRegistry 中是否有匹配的 Skill
        if (commandRegistry != null && commandRegistry.hasCommand(cmd)) {
            try {
                // 将 /cmd args 原样转发给 AgentLoop
                // LLM 会根据 system-reminder 中的 Skill 列表调用 SkillTool
                agentLoop.run(input);
                System.out.println();
            } catch (Exception e) {
                renderer.renderError("Skill execution failed: " + e.getMessage());
            }
            return true;
        }

        // 未知命令
        renderer.renderError("Unknown command: " + input + ". Type /help for available commands.");
        return true;
    }

    /**
     * 渲染帮助信息（含动态 Skill 列表）
     *
     * 在原有的内置命令帮助基础上，追加显示所有用户可调用的 Skill。
     * Skill 列表从 CommandRegistry 动态获取。
     */
    private void renderHelp() {
        renderer.renderHelp();

        // 如果有已注册的 Skill，额外展示 Skill 列表
        if (commandRegistry != null && commandRegistry.size() > 0) {
            java.util.List<PromptCommand> skills = commandRegistry.getUserInvocableCommands();
            if (!skills.isEmpty()) {
                System.out.println("  " + "\033[1m" + "Available skills:" + "\033[0m");
                for (PromptCommand skill : skills) {
                    String desc = skill.description() != null ? skill.description() : "";
                    if (desc.length() > 60) desc = desc.substring(0, 57) + "...";
                    System.out.println("  " + "\033[36m" + "/" + skill.name() + "\033[0m"
                            + (desc.isEmpty() ? "" : " - " + desc));
                }
                System.out.println();
            }
        }
    }

    private void printGoodbye() {
        renderer.renderSystemMessage("Goodbye!");
    }

    /**
     * 设置启动横幅展示的连接配置信息
     */
    public void setConnectionInfo(String model, String baseUrl, String apiKey) {
        this.configModel = model;
        this.configBaseUrl = baseUrl;
        this.configApiKey = apiKey;
    }

    /**
     * 设置命令注册中心
     *
     * 注入 CommandRegistry 后，Repl 将支持 /skill-name 斜杠命令路由。
     * 如果不调用此方法（commandRegistry 为 null），
     * 所有 / 开头的非内置命令都会提示"Unknown command"。
     *
     * @param commandRegistry 命令注册中心实例
     */
    public void setCommandRegistry(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    /**
     * 暴露 LineReader 的 readLine 方法，供 PermissionManager 注入使用
     * 解决 Scanner(System.in) 与 JLine raw mode 冲突问题
     */
    public String readLine(String prompt) {
        return lineReader.readLine(prompt);
    }
}

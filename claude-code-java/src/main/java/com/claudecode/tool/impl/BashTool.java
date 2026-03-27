package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BashTool — Shell 命令执行工具
 *
 * 功能：执行用户指定的 Shell 命令并返回输出结果。
 * 这是最强大也最危险的工具，必须 requiresPermission() 返回 true。
 *
 * @author sunchenhao
 * @date 2026/3/27
 */
public class BashTool implements Tool {

    /** 默认超时时间：120秒 */
    private static final long DEFAULT_TIMEOUT_MS = 120_000;

    /** 输出最大长度：100KB，超出则截断 */
    private static final int MAX_OUTPUT_LENGTH = 100 * 1024;

    /** 工作目录（命令在此目录下执行） */
    private final String workingDirectory;

    public BashTool(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    // ===================== Tool 接口实现 =====================

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        // 这段描述是给 LLM 看的，LLM 根据它来决定何时使用这个工具
        // 写得越清晰，LLM 使用得越准确
        return "Executes a given bash command and returns its output. "
                + "Use this tool for running system commands, scripts, builds, tests, "
                + "and any operation that requires shell execution. "
                + "The working directory persists between calls. "
                + "Avoid using this for tasks that have dedicated tools "
                + "(e.g., use Read instead of cat, use Grep instead of grep).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        // 构建 JSON Schema 格式的参数定义
        // 这会被序列化为 API 请求中 tools[].input_schema 字段
        //
        // 最终 JSON 形态：
        // {
        //   "type": "object",
        //   "properties": {
        //     "command": { "type": "string", "description": "..." },
        //     "timeout": { "type": "integer", "description": "..." }
        //   },
        //   "required": ["command"]
        // }
        //
        // 注意：这里用 LinkedHashMap 保证 key 的顺序稳定（序列化后更可读）

        Map<String, Object> commandProp = new LinkedHashMap<>();
        commandProp.put("type", "string");
        commandProp.put("description", "The bash command to execute");

        Map<String, Object> timeoutProp = new LinkedHashMap<>();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Timeout in milliseconds, default 120000");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", commandProp);
        properties.put("timeout", timeoutProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("command"));

        return schema;
    }

    @Override
    public boolean requiresPermission() {
        // Bash 能执行任意命令，必须经过用户审批
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        // ——— 第1步：提取参数 ———
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("Parameter 'command' is required");
        }

        long timeout = DEFAULT_TIMEOUT_MS;
        Object timeoutObj = input.get("timeout");
        if (timeoutObj instanceof Number) {
            timeout = ((Number) timeoutObj).longValue();
        }

        // ——— 第2步：构建进程 ———
        // 用 bash -c 执行命令字符串，这样支持管道、重定向等 shell 语法
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(new java.io.File(workingDirectory));
        // 将 stderr 合并到 stdout，这样一次读取就能拿到所有输出
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // ——— 第3步：异步读取输出 ———
            // 关键修复：输出读取必须放在单独的线程中！
            // 如果在主线程同步 readLine()，它会阻塞直到进程结束，
            // 导致后面的 waitFor(timeout) 永远不会超时。
            // 守护线程持续读取输出，主线程用 waitFor 控制超时。
            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() > 0) {
                                output.append("\n");
                            }
                            output.append(line);

                            // 输出截断保护：防止超长输出撑爆上下文窗口
                            if (output.length() > MAX_OUTPUT_LENGTH) {
                                output.append("\n... (output truncated, exceeded ")
                                        .append(MAX_OUTPUT_LENGTH / 1024).append("KB)");
                                break;
                            }
                        }
                    }
                } catch (java.io.IOException e) {
                    // 进程被 destroyForcibly() 杀掉时，流会关闭，readLine 抛 IOException
                    // 这是正常的超时退出路径，不需要处理
                }
            });
            outputReader.setDaemon(true);  // 守护线程：主线程退出时自动结束
            outputReader.start();

            // ——— 第4步：等待进程结束（带超时） ———
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时：强制杀掉进程，守护线程会因流关闭而退出
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + timeout + "ms: " + command);
            }

            // ——— 第5步：等待输出读取完成 ———
            // 进程已结束，但守护线程可能还在读取缓冲区中剩余的输出
            outputReader.join(3000);

            // ——— 第6步：根据退出码构造结果 ———
            int exitCode = process.exitValue();
            String result;
            synchronized (output) {
                result = output.toString();
            }

            // 退出码非零不一定是 error（如 grep 没找到返回1），
            // 所以统一用 success 返回，把退出码附上，让 LLM 自行判断
            if (exitCode != 0) {
                result += "\nExit code: " + exitCode;
            }

            return ToolResult.success(result);

        } catch (Exception e) {
            // IO异常、中断异常等，统一作为工具错误返回
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        }
    }
}

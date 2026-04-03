package com.claudecode.command;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CommandRenderer — Skill 内容渲染器
 *
 * =============================================
 *  这个类做什么？
 * =============================================
 *
 * 当一个 Skill 被触发时（无论是用户通过 /name 触发还是 LLM 通过 SkillTool 调用），
 * SKILL.md 的 Markdown 正文不能直接注入到对话中——它可能包含需要预处理的特殊语法。
 *
 * CommandRenderer 负责将「原始内容」渲染为「最终内容」，经过两个处理阶段：
 *
 * 阶段 1：Shell 预处理（!`command` 语法）
 *   在发送给 LLM 之前，先在本地执行 shell 命令，将输出内联替换到内容中。
 *   LLM 只会看到命令的输出结果，看不到原始命令。
 *
 *   示例：
 *     原始内容：当前分支是 !`git branch --show-current`
 *     渲染结果：当前分支是 main
 *
 * 阶段 2：变量替换
 *   将特殊变量占位符替换为实际值。
 *
 *   支持的变量：
 *   - $ARGUMENTS      → 用户传入的全部参数字符串
 *   - $ARGUMENTS[0]   → 第一个参数（按空格分割）
 *   - $ARGUMENTS[1]   → 第二个参数
 *   - $0, $1, $2...   → $ARGUMENTS[0] 的简写
 *   - ${CLAUDE_SKILL_DIR} → SKILL.md 所在目录的绝对路径
 *
 * =============================================
 *  为什么 Shell 预处理在变量替换之前？
 * =============================================
 *
 * 因为 shell 命令的输出可能包含 $ 符号（比如环境变量值），
 * 如果先做变量替换，可能会误把 shell 输出中的 $ 当作变量来替换。
 * 先执行 shell 命令，再做变量替换，可以避免这个问题。
 *
 * =============================================
 *  安全注意事项
 * =============================================
 *
 * Shell 预处理会在本地执行任意命令！这是一个强大但危险的功能。
 * 当前版本直接执行，未来应该：
 * 1. 对来自不信任来源的 Skill 禁用 shell 预处理
 * 2. 设置执行超时
 * 3. 在权限管理器中增加对 shell 预处理的审批
 *
 * 设计参考：
 *   对应 Claude Code 中 SKILL.md 的动态上下文注入机制
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see PromptCommand
 */
public class CommandRenderer {

    /**
     * Shell 预处理的正则匹配模式
     *
     * 匹配 !`command` 格式：
     * - ! 是触发标记
     * - ` 是命令开始标记
     * - (.+?) 是命令内容（非贪婪匹配，防止跨越多个 !`...`）
     * - ` 是命令结束标记
     *
     * 示例匹配：
     *   !`git branch --show-current`  → 捕获 "git branch --show-current"
     *   !`echo hello`                 → 捕获 "echo hello"
     */
    private static final Pattern SHELL_PATTERN = Pattern.compile("!`(.+?)`");

    /**
     * 索引变量的正则匹配模式
     *
     * 匹配 $ARGUMENTS[N] 或 $N 格式：
     * - $ARGUMENTS[0], $ARGUMENTS[1] 等
     * - $0, $1, $2 等（简写形式）
     */
    private static final Pattern INDEXED_ARG_PATTERN = Pattern.compile("\\$ARGUMENTS\\[(\\d+)]|\\$(\\d+)");

    /** Shell 命令执行的超时时间（毫秒） */
    private static final long SHELL_TIMEOUT_MS = 10_000;

    /**
     * 渲染 Skill 的原始内容，生成最终可注入对话的文本
     *
     * 这是对外的核心方法。调用链路：
     *   SkillTool.execute()
     *     → CommandRenderer.render(command, args)
     *       → 阶段1: processShellPreprocessing()
     *       → 阶段2: replaceVariables()
     *     → 返回渲染后的文本
     *
     * @param command  要渲染的 PromptCommand（包含原始内容和路径信息）
     * @param args     用户传入的参数字符串（例如 /simplify 后面的 "src/main"）
     * @return 渲染后的最终文本，可以直接注入对话上下文
     */
    public String render(PromptCommand command, String args) {
        String content = command.getRawContent();

        // 阶段 1：Shell 预处理（执行 !`command` 并替换为输出）
        content = processShellPreprocessing(content);

        // 阶段 2：变量替换（$ARGUMENTS, ${CLAUDE_SKILL_DIR} 等）
        content = replaceVariables(content, command, args);

        return content;
    }

    /**
     * 阶段 1：Shell 预处理
     *
     * 扫描内容中所有 !`command` 模式，逐个执行 shell 命令，
     * 将命令输出替换到原文中。
     *
     * 处理逻辑：
     *   1. 用正则找到所有 !`command` 匹配
     *   2. 对每个匹配，通过 Runtime.exec() 执行命令
     *   3. 读取命令的标准输出
     *   4. 将 !`command` 替换为输出结果（去掉首尾空白）
     *   5. 如果命令执行失败，替换为错误信息
     *
     * 示例：
     *   输入：  "当前分支：!`git branch --show-current`，提交数：!`git rev-list --count HEAD`"
     *   输出：  "当前分支：main，提交数：42"
     *
     * @param content 包含 !`command` 语法的原始内容
     * @return 命令输出替换后的内容
     */
    private String processShellPreprocessing(String content) {
        if (content == null || !content.contains("!`")) {
            // 快速路径：如果内容中没有 !` 标记，直接返回，避免不必要的正则扫描
            return content;
        }

        Matcher matcher = SHELL_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            // 提取 !` 和 ` 之间的命令文本
            String command = matcher.group(1);
            String output = executeShellCommand(command);
            // Matcher.appendReplacement 中 $ 和 \ 是特殊字符，需要转义
            matcher.appendReplacement(result, Matcher.quoteReplacement(output));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 执行一个 shell 命令并返回其标准输出
     *
     * 实现细节：
     * - 使用 bash -c 执行命令（支持管道、重定向等 shell 特性）
     * - 设置超时（默认 10 秒），防止命令挂起
     * - 只捕获标准输出（stdout），忽略标准错误（stderr）
     * - 命令失败时返回错误信息字符串（不抛异常）
     *
     * @param command 要执行的 shell 命令字符串
     * @return 命令的标准输出（去掉首尾空白），失败时返回错误信息
     */
    private String executeShellCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(false);  // 不合并 stderr 到 stdout
            Process process = pb.start();

            // 读取标准输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append("\n");
                    output.append(line);
                }
            }

            // 等待进程结束（带超时）
            boolean finished = process.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "[Shell preprocessing timeout: " + command + "]";
            }

            return output.toString().trim();
        } catch (Exception e) {
            return "[Shell preprocessing error: " + e.getMessage() + "]";
        }
    }

    /**
     * 阶段 2：变量替换
     *
     * 将内容中的特殊变量占位符替换为实际值。
     *
     * 替换顺序很重要：
     *   1. 先替换 $ARGUMENTS[N] 和 $N（索引变量）
     *   2. 再替换 $ARGUMENTS（全量参数）
     *   3. 最后替换 ${CLAUDE_SKILL_DIR}（路径变量）
     *
     * 为什么顺序重要？
     *   因为 $ARGUMENTS 是 $ARGUMENTS[0] 的前缀。
     *   如果先替换 $ARGUMENTS，那 $ARGUMENTS[0] 就会变成 "实际参数[0]"，
     *   而不是预期的「第一个参数」。所以必须先替换更长的模式。
     *
     * @param content 经过 shell 预处理后的内容
     * @param command PromptCommand 实例（用于获取 skillDir）
     * @param args    用户传入的参数字符串
     * @return 变量替换后的最终内容
     */
    private String replaceVariables(String content, PromptCommand command, String args) {
        if (content == null) return "";
        if (args == null) args = "";

        // 将参数字符串按空格分割为数组（用于索引访问）
        String[] argArray = args.isEmpty() ? new String[0] : args.split("\\s+");

        // 步骤 1：替换索引变量 $ARGUMENTS[N] 和 $N
        Matcher indexedMatcher = INDEXED_ARG_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (indexedMatcher.find()) {
            // group(1) 匹配 $ARGUMENTS[N] 中的 N
            // group(2) 匹配 $N 中的 N
            String indexStr = indexedMatcher.group(1) != null
                    ? indexedMatcher.group(1)
                    : indexedMatcher.group(2);
            int index = Integer.parseInt(indexStr);
            String replacement = index < argArray.length ? argArray[index] : "";
            indexedMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        indexedMatcher.appendTail(sb);
        content = sb.toString();

        // 步骤 2：替换 $ARGUMENTS（全量参数字符串）
        content = content.replace("$ARGUMENTS", args);

        // 步骤 3：替换 ${CLAUDE_SKILL_DIR}（SKILL.md 所在目录的绝对路径）
        if (command.getSkillDir() != null) {
            content = content.replace("${CLAUDE_SKILL_DIR}",
                    command.getSkillDir().toAbsolutePath().toString());
        }

        return content;
    }
}

package com.claudecode.memory.instruction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * InstructionLoader — 加载多层级 CLAUDE.md 指令文件。
 *
 * <p>完整 Claude Code 的指令系统层级更多。这里实现一个足够实用的简化版，
 * 但仍然保留“多来源合并”的核心思想：</p>
 *
 * <ol>
 *   <li>用户全局指令：{@code ~/.claude/CLAUDE.md}</li>
 *   <li>应用级全局指令：{@code ~/.claude-code-java/CLAUDE.md}</li>
 *   <li>项目指令：{@code <project>/CLAUDE.md}</li>
 *   <li>项目本地私有指令：{@code <project>/CLAUDE.local.md}</li>
 * </ol>
 *
 * <p>每层都保留标题，最终拼成一段可直接附加到 system prompt 的文本。</p>
 */
public class InstructionLoader {

    public String loadInstructions(Path projectRoot) throws IOException {
        StringBuilder sb = new StringBuilder();

        appendIfExists(sb, Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md"),
                "Global User Instructions");
        appendIfExists(sb, Path.of(System.getProperty("user.home"), ".claude-code-java", "CLAUDE.md"),
                "Claude Code Java Instructions");

        if (projectRoot != null) {
            appendIfExists(sb, projectRoot.resolve("CLAUDE.md"), "Project Instructions");
            appendIfExists(sb, projectRoot.resolve("CLAUDE.local.md"), "Local Project Instructions");
        }

        return sb.toString().trim();
    }

    private void appendIfExists(StringBuilder sb, Path path, String title) throws IOException {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return;
        }

        String content = Files.readString(path).trim();
        if (content.isEmpty()) {
            return;
        }

        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("## ").append(title).append("\n");
        sb.append(content);
    }
}

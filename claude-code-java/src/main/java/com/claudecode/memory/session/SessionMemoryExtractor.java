package com.claudecode.memory.session;

import com.claudecode.api.model.ContentBlock;
import com.claudecode.api.model.Message;
import com.claudecode.api.model.TextBlock;
import com.claudecode.api.model.ToolResultBlock;
import com.claudecode.api.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SessionMemoryExtractor — 从消息历史中抽取结构化会话摘要。
 *
 * <p>完整版 Claude Code 会在后台 fork 一个子代理周期性提取会话记忆。
 * 对当前这个 Java 版本，我们先实现一个“零额外 API 调用”的启发式提取器：</p>
 *
 * <ul>
 *   <li>稳定、可测试</li>
 *   <li>不会和主对话争抢 token 预算</li>
 *   <li>已经足够支持 L2 压缩，把历史折叠成结构化摘要</li>
 * </ul>
 *
 * <p>后续如果要换成 LLM 提取器，只需要保持 {@link #extract(List)} 的输出结构一致。</p>
 */
public class SessionMemoryExtractor {

    private static final Pattern FILE_PATTERN =
            Pattern.compile("([\\w./\\\\-]+\\.(?:java|kt|ts|tsx|js|jsx|json|xml|yaml|yml|md|txt|properties))");

    /**
     * 从消息序列中提取各 section 的文本。
     */
    public SessionMemorySnapshot extract(List<Message> messages) {
        String firstUser = findFirstUserText(messages);
        String latestUser = findLatestRoleText(messages, "user");
        String latestAssistant = findLatestRoleText(messages, "assistant");

        Set<String> files = collectFiles(messages);
        List<String> toolCalls = collectToolCalls(messages);
        List<String> errors = collectErrors(messages);

        SessionMemorySnapshot snapshot = new SessionMemorySnapshot();
        snapshot.put("Session Title", clip(firstUser, 120));
        snapshot.put("Current State", clip(latestAssistant, 400));
        snapshot.put("Task Specification", clip(firstUser, 800));
        snapshot.put("Files and Functions", join(files, 8));
        snapshot.put("Workflow", join(toolCalls, 8));
        snapshot.put("Errors & Corrections", join(errors, 6));
        snapshot.put("Key Results", clip(latestAssistant, 500));
        snapshot.put("Worklog", clip(buildWorklog(messages), 1200));
        snapshot.put("Latest User Intent", clip(latestUser, 500));
        return snapshot;
    }

    private String findFirstUserText(List<Message> messages) {
        for (Message message : messages) {
            if ("user".equals(message.getRole())) {
                String text = message.getTextContent();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String findLatestRoleText(List<Message> messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (role.equals(message.getRole())) {
                String text = message.getTextContent();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private Set<String> collectFiles(List<Message> messages) {
        Set<String> files = new LinkedHashSet<>();
        for (Message message : messages) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock) {
                    Matcher matcher = FILE_PATTERN.matcher(((TextBlock) block).getText());
                    while (matcher.find()) {
                        files.add(matcher.group(1));
                    }
                } else if (block instanceof ToolUseBlock) {
                    ToolUseBlock toolUse = (ToolUseBlock) block;
                    if (toolUse.getInput() != null) {
                        Matcher matcher = FILE_PATTERN.matcher(toolUse.getInput().toString());
                        while (matcher.find()) {
                            files.add(matcher.group(1));
                        }
                    }
                }
            }
        }
        return files;
    }

    private List<String> collectToolCalls(List<Message> messages) {
        List<String> toolCalls = new ArrayList<>();
        for (Message message : messages) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolUseBlock) {
                    ToolUseBlock toolUse = (ToolUseBlock) block;
                    toolCalls.add(toolUse.getName() + " " + compactInput(toolUse));
                }
            }
        }
        return toolCalls;
    }

    private List<String> collectErrors(List<Message> messages) {
        List<String> errors = new ArrayList<>();
        for (Message message : messages) {
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolResultBlock) {
                    ToolResultBlock toolResult = (ToolResultBlock) block;
                    if (toolResult.isError() || looksLikeError(toolResult.getContent())) {
                        errors.add(clip(toolResult.getContent(), 240));
                    }
                } else if (block instanceof TextBlock) {
                    String text = ((TextBlock) block).getText();
                    if (looksLikeError(text)) {
                        errors.add(clip(text, 240));
                    }
                }
            }
        }
        return errors;
    }

    private boolean looksLikeError(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("exception")
                || lower.contains("failed")
                || lower.contains("denied")
                || lower.contains("not found");
    }

    private String buildWorklog(List<Message> messages) {
        List<String> lines = new ArrayList<>();
        for (Message message : messages) {
            String text = message.getTextContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            String prefix = "user".equals(message.getRole()) ? "User: " : "Assistant: ";
            lines.add(prefix + clip(text, 180));
        }
        return String.join("\n", lines);
    }

    private String compactInput(ToolUseBlock toolUse) {
        if (toolUse.getInput() == null || toolUse.getInput().isEmpty()) {
            return "";
        }
        return clip(toolUse.getInput().toString(), 120);
    }

    private String join(Set<String> values, int maxItems) {
        return join(new ArrayList<>(values), maxItems);
    }

    private String join(List<String> values, int maxItems) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> clipped = new ArrayList<>();
        for (int i = 0; i < values.size() && i < maxItems; i++) {
            String value = values.get(i);
            if (value != null && !value.isBlank()) {
                clipped.add("- " + clip(value.trim(), 200));
            }
        }
        return String.join("\n", clipped);
    }

    private String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }
}

package com.claudecode.memory.util;

import java.nio.file.Path;

/**
 * PathSanitizer — 将路径和标题转换为稳定、安全的 slug。
 *
 * <p>记忆目录需要根据项目根路径生成一个稳定目录名，例如：</p>
 * <pre>
 * /Users/alice/work/demo  ->  users-alice-work-demo
 * </pre>
 *
 * <p>同时记忆条目文件名也需要类似的 slug 化逻辑，避免把空格、斜杠、
 * 中文标点等直接写入文件名导致兼容性问题。</p>
 */
public final class PathSanitizer {

    private PathSanitizer() {
    }

    /**
     * 将任意文本转换为文件系统友好的 slug。
     *
     * <p>策略非常保守：只保留英文字母、数字和中划线，其他字符一律折叠为
     * 单个中划线。这样虽然牺牲了一部分可读性，但能显著降低跨平台问题。</p>
     */
    public static String slugify(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }

        String normalized = raw.trim()
                .replace('\\', '-')
                .replace('/', '-')
                .replace(':', '-')
                .replace(' ', '-')
                .toLowerCase();

        normalized = normalized.replaceAll("[^a-z0-9\\-]+", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+", "");
        normalized = normalized.replaceAll("-+$", "");
        return normalized;
    }

    /**
     * 将项目根路径转换为记忆存储目录名。
     */
    public static String sanitizeProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            return "unknown-project";
        }
        return slugify(projectRoot.toAbsolutePath().normalize().toString());
    }
}

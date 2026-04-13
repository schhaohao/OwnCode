package com.claudecode.memory.util;

import org.yaml.snakeyaml.Yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FrontmatterParser — 解析 Markdown 文件头部的 YAML frontmatter。
 *
 * <p>记忆条目用 frontmatter 存元数据，这是最接近 Claude Code 原始设计、
 * 同时也最适合人类直接编辑的一种表示方式。</p>
 *
 * <p>本解析器只负责两件事：</p>
 * <ol>
 *   <li>把 frontmatter 解析成键值对</li>
 *   <li>把正文 body 原样提取出来</li>
 * </ol>
 *
 * <p>如果文件没有 frontmatter，不报错，直接把整篇文本当作 body。</p>
 */
public final class FrontmatterParser {

    /**
     * 非贪婪匹配第一段 frontmatter，剩余内容全部视为正文。
     */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\R(.*?)\\R---\\R?(.*)$", Pattern.DOTALL);

    private static final Yaml YAML = new Yaml();

    private FrontmatterParser() {
    }

    public static ParsedFrontmatter parse(String rawMarkdown) {
        String safeRaw = rawMarkdown != null ? rawMarkdown : "";
        Matcher matcher = FRONTMATTER_PATTERN.matcher(safeRaw);
        if (!matcher.matches()) {
            return new ParsedFrontmatter(Collections.emptyMap(), safeRaw);
        }

        String yamlText = matcher.group(1);
        String body = matcher.group(2);

        Object loaded = YAML.load(yamlText);
        if (!(loaded instanceof Map)) {
            return new ParsedFrontmatter(Collections.emptyMap(), body);
        }

        Map<?, ?> rawMap = (Map<?, ?>) loaded;
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            attributes.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return new ParsedFrontmatter(attributes, body);
    }

    /**
     * ParsedFrontmatter — frontmatter 解析结果。
     */
    public static class ParsedFrontmatter {

        private final Map<String, Object> attributes;
        private final String body;

        public ParsedFrontmatter(Map<String, Object> attributes, String body) {
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            this.body = body != null ? body : "";
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public String getBody() {
            return body;
        }
    }
}

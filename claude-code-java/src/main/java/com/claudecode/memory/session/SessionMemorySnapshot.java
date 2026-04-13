package com.claudecode.memory.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SessionMemorySnapshot — 一次提取动作产出的结构化 section 集合。
 */
public class SessionMemorySnapshot {

    private final Map<String, String> sections = new LinkedHashMap<>();

    public void put(String section, String value) {
        sections.put(section, value != null ? value : "");
    }

    public Map<String, String> getSections() {
        return Collections.unmodifiableMap(sections);
    }
}

package com.claudecode.memory.model;

/**
 * MemoryType — 持久化记忆的语义分类。
 *
 * <p>这里直接对齐调研文档里的四种核心类型，目的是让记忆文件不只是
 * “一段文本”，而是带有足够清晰的用途标签，方便后续检索、筛选和解释：</p>
 *
 * <ul>
 *   <li>{@link #USER}：用户画像、偏好、背景知识</li>
 *   <li>{@link #FEEDBACK}：用户对工作方式的纠偏或确认</li>
 *   <li>{@link #PROJECT}：无法从代码直接推导出的项目上下文</li>
 *   <li>{@link #REFERENCE}：外部系统、文档、工单等引用指针</li>
 * </ul>
 */
public enum MemoryType {

    /** 用户角色、偏好、能力边界、表达习惯等。 */
    USER,

    /** 用户明确给出的反馈，例如“不要这样做”“以后优先那样做”。 */
    FEEDBACK,

    /** 项目背景、业务约束、组织决策等无法仅靠读代码得到的事实。 */
    PROJECT,

    /** 外部资料、工单、规范链接等“指向性记忆”。 */
    REFERENCE;

    /**
     * 将 frontmatter 中的小写类型安全地转回枚举。
     *
     * @param raw frontmatter 中的 type 字段
     * @return 匹配到的记忆类型；空值或未知值时回退为 PROJECT
     */
    public static MemoryType fromFrontmatter(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return PROJECT;
        }

        for (MemoryType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return PROJECT;
    }

    /**
     * frontmatter 输出统一使用小写，和调研文档示例保持一致。
     */
    public String toFrontmatterValue() {
        return name().toLowerCase();
    }
}

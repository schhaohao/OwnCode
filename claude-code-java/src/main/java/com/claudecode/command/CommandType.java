package com.claudecode.command;

/**
 * CommandType — 命令类型枚举
 *
 * 在 Claude Code 的架构中，Command（命令）有两种类型：
 *
 * 1. PROMPT 类型（也就是 Skill）：
 *    - 本质是一段「结构化的提示词」，不是可执行代码
 *    - 被加载后注入到 LLM 的对话上下文中，LLM 根据这段提示词来执行任务
 *    - 典型例子：/simplify（审查代码质量）、/commit（生成 git commit）
 *    - 存储在 SKILL.md 文件中，使用 YAML frontmatter + Markdown 正文格式
 *
 * 2. BUILTIN 类型：
 *    - 由代码直接实现的固定逻辑命令
 *    - 不经过 LLM，直接在本地执行
 *    - 典型例子：/help（显示帮助）、/clear（清空历史）、/exit（退出程序）
 *
 * 为什么需要区分？
 *   因为两种类型的命令执行路径完全不同：
 *   - PROMPT 类型需要经过「加载 → 渲染 → 注入对话 → LLM 执行」的流程
 *   - BUILTIN 类型直接调用 Java 方法即可
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/types/command.ts 中的 type 字段：
 *   PromptCommand = { type: 'prompt', ... }
 *
 * @author sunchenhao
 * @date 2026/4/3
 */
public enum CommandType {

    /**
     * Prompt 类型命令（即 Skill）
     *
     * 特点：
     * - 内容是一段 Markdown 格式的提示词
     * - 被调用时，提示词会被渲染（变量替换、shell 预处理）后注入到对话上下文
     * - LLM 接收到这段提示词后，按照其中的指令来完成任务
     * - 可以通过 SkillTool 被 LLM 主动调用，也可以通过用户输入 /name 触发
     */
    PROMPT,

    /**
     * 内置命令类型
     *
     * 特点：
     * - 由 Java 代码直接实现固定逻辑
     * - 不经过 LLM，在本地直接执行
     * - 典型的如 /help、/clear、/exit 等基础命令
     * - 当前版本这些命令仍然在 Repl.handleCommand() 中硬编码处理，
     *   后续可以迁移到 Command 体系中统一管理
     */
    BUILTIN
}

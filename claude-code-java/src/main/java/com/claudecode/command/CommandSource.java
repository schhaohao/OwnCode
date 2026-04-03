package com.claudecode.command;

/**
 * CommandSource — 命令来源枚举
 *
 * 在 Claude Code 中，Skill（即 PROMPT 类型的 Command）可以从 5 个不同的来源加载。
 * 所有来源最终汇聚到 CommandRegistry 统一管理。
 *
 * 来源的优先级（从低到高）：
 *   BUNDLED < DISK（用户级）< DISK（项目级）< PLUGIN < MCP
 *   同名 Skill 时，高优先级来源会覆盖低优先级来源。
 *
 * 举个例子：
 *   假设你有一个 bundled 的 /simplify skill，同时在项目的 .claude/skills/simplify/SKILL.md
 *   中也定义了一个同名的 skill。那么项目级的定义会覆盖内置的，因为 DISK 优先级高于 BUNDLED。
 *
 * 设计参考：
 *   对应 Claude Code 源码 src/types/command.ts 中的 source 字段：
 *   PromptCommand = { source: 'builtin' | 'mcp' | 'plugin' | 'bundled' | SettingSource, ... }
 *
 * @author sunchenhao
 * @date 2026/4/3
 */
public enum CommandSource {

    /**
     * 内置 Skill（Bundled）
     *
     * 随应用一起发布的预置 Skill，位于源码的 resources/skills/ 目录下。
     * 例如：simplify、commit、review-pr 等。
     * 优先级最低——用户可以通过磁盘 Skill 覆盖同名的内置 Skill。
     */
    BUNDLED,

    /**
     * 磁盘 Skill（Disk）
     *
     * 从文件系统加载的 Skill，有两个扫描路径：
     * 1. 用户级：~/.claude-code-java/skills/<name>/SKILL.md（对所有项目生效）
     * 2. 项目级：<项目根目录>/.claude-code-java/skills/<name>/SKILL.md（仅对当前项目生效）
     *
     * 项目级会覆盖用户级的同名 Skill。
     * 这是用户自定义 Skill 最常用的方式。
     */
    DISK,

    /**
     * MCP 服务器提供的 Skill
     *
     * 通过 MCP（Model Context Protocol）协议从远程服务器动态获取。
     * 当前版本暂未实现，预留给未来扩展。
     */
    MCP,

    /**
     * 插件 Skill（Plugin）
     *
     * 由内置插件或市场安装的第三方插件提供。
     * 使用 pluginName:skillName 的命名空间格式，不会与其他来源冲突。
     * 当前版本暂未实现，预留给未来扩展。
     */
    PLUGIN
}

package com.claudecode.command.loader;

import com.claudecode.command.CommandSource;
import com.claudecode.command.PromptCommand;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * SkillLoader — 从磁盘加载 Skill 的加载器
 *
 * =============================================
 *  核心职责
 * =============================================
 *
 * 扫描文件系统中约定的目录，找到所有 SKILL.md 文件，解析它们，
 * 并将每个 SKILL.md 转换为一个 {@link PromptCommand} 实例。
 *
 * =============================================
 *  扫描路径（两级）
 * =============================================
 *
 * SkillLoader 会扫描以下两个位置（按优先级从低到高）：
 *
 * 1. 用户级：~/.claude-code-java/skills/
 *    - 对所有项目生效
 *    - 适合放置个人通用的 Skill（如代码风格检查、翻译等）
 *    - 示例：~/.claude-code-java/skills/my-linter/SKILL.md
 *
 * 2. 项目级：<工作目录>/.claude-code-java/skills/
 *    - 仅对当前项目生效
 *    - 适合放置项目特定的 Skill（如部署脚本、代码生成等）
 *    - 示例：/my-project/.claude-code-java/skills/deploy/SKILL.md
 *
 * 当两级目录中存在同名 Skill 时，项目级覆盖用户级。
 *
 * =============================================
 *  目录结构约定
 * =============================================
 *
 * 每个 Skill 是一个目录，目录名就是 Skill 的默认名称：
 *
 * <pre>
 * .claude/skills/
 * ├── simplify/               ← Skill 目录，名称为 "simplify"
 * │   ├── SKILL.md            ← 必需：Skill 定义文件
 * │   ├── scripts/            ← 可选：辅助脚本
 * │   │   └── lint.sh
 * │   └── templates/          ← 可选：模板文件
 * │       └── pr-template.md
 * ├── deploy/                 ← 另一个 Skill
 * │   └── SKILL.md
 * └── ...
 * </pre>
 *
 * =============================================
 *  SKILL.md 文件格式
 * =============================================
 *
 * 文件由两部分组成，用 --- 分隔：
 *
 * <pre>
 * ---                           ← 第一个 --- 标记 frontmatter 开始
 * description: 审查代码质量
 * allowed-tools:
 *   - Read
 *   - Bash
 * context: inline
 * ---                           ← 第二个 --- 标记 frontmatter 结束
 *                                 ← 之后的所有内容都是 Markdown 正文
 * 你是一个代码审查专家...
 * 请检查以下文件的代码质量...
 * $ARGUMENTS
 * </pre>
 *
 * =============================================
 *  解析流程
 * =============================================
 *
 * 对每个 SKILL.md 文件：
 * 1. 读取文件全部内容
 * 2. 用 --- 分割出 frontmatter（YAML 部分）和 body（Markdown 正文）
 * 3. 使用 SnakeYAML 将 YAML 文本解析为 Map
 * 4. 手动将 Map 映射到 SkillFrontmatter 对象（处理连字符命名到驼峰的转换）
 * 5. 调用 PromptCommand.fromFrontmatter() 组装最终对象
 *
 * 为什么不直接用 SnakeYAML 的 loadAs(SkillFrontmatter.class)？
 *   因为 YAML 字段名使用连字符（如 allowed-tools），
 *   而 Java 字段名使用驼峰（如 allowedTools）。
 *   SnakeYAML 默认的 JavaBean 映射不支持这种转换，
 *   所以我们先解析为 Map，再手动映射。
 *
 * 设计参考：
 *   对应 Claude Code 源码中从 .claude/skills/ 目录加载 Skill 的逻辑
 *
 * @author sunchenhao
 * @date 2026/4/3
 * @see SkillFrontmatter
 * @see PromptCommand
 */
public class SkillLoader {

    /** 用户主目录路径（~） */
    private static final String USER_HOME = System.getProperty("user.home");

    /** 用户级 Skill 目录：~/.claude-code-java/skills/ */
    private static final String USER_SKILLS_DIR = ".claude-code-java" + java.io.File.separator + "skills";

    /** 项目级 Skill 目录：<workDir>/.claude-code-java/skills/ */
    private static final String PROJECT_SKILLS_DIR = ".claude-code-java" + java.io.File.separator + "skills";

    /** SKILL.md 文件名（大小写敏感） */
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** SnakeYAML 实例（线程安全的，可以复用） */
    private final Yaml yaml = new Yaml();

    /** 工作目录（项目根目录） */
    private final String workingDirectory;

    /**
     * 构造 SkillLoader
     *
     * @param workingDirectory 当前工作目录（项目根目录），
     *                         用于定位项目级 .claude/skills/ 目录
     */
    public SkillLoader(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * 加载所有磁盘 Skill
     *
     * 扫描用户级和项目级目录，加载所有 SKILL.md 文件。
     * 同名 Skill 时，项目级覆盖用户级（通过 Map 的 put 覆盖实现）。
     *
     * 加载过程中的任何错误（文件读取失败、YAML 格式错误等）
     * 都会被捕获并打印警告，不会中断其他 Skill 的加载。
     * 这确保了单个损坏的 SKILL.md 不会影响整个系统。
     *
     * @return 所有成功加载的 PromptCommand 列表
     */
    public List<PromptCommand> loadAll() {
        // 使用 LinkedHashMap 保持插入顺序，同时支持同名覆盖
        Map<String, PromptCommand> commandMap = new LinkedHashMap<>();

        // 第一步：加载用户级 Skill（优先级低）
        Path userSkillsDir = Paths.get(USER_HOME, USER_SKILLS_DIR);
        loadFromDirectory(userSkillsDir, commandMap);

        // 第二步：加载项目级 Skill（优先级高，会覆盖同名的用户级 Skill）
        if (workingDirectory != null) {
            Path projectSkillsDir = Paths.get(workingDirectory, PROJECT_SKILLS_DIR);
            loadFromDirectory(projectSkillsDir, commandMap);
        }

        return new ArrayList<>(commandMap.values());
    }

    /**
     * 从指定目录加载所有 Skill
     *
     * 扫描目录下的每个子目录，查找 SKILL.md 文件。
     * 成功加载的 Skill 以 name 为 key 放入 commandMap。
     *
     * 目录结构期望：
     * <pre>
     * skillsDir/
     * ├── skill-a/
     * │   └── SKILL.md      ← 会被加载
     * ├── skill-b/
     * │   └── SKILL.md      ← 会被加载
     * ├── README.md          ← 被忽略（不是目录）
     * └── empty-dir/         ← 被忽略（没有 SKILL.md）
     * </pre>
     *
     * @param skillsDir  要扫描的 skills 目录路径
     * @param commandMap 结果收集 Map（name → PromptCommand）
     */
    private void loadFromDirectory(Path skillsDir, Map<String, PromptCommand> commandMap) {
        // 目录不存在则跳过（这很正常——用户可能还没创建 .claude/skills/ 目录）
        if (!Files.isDirectory(skillsDir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries
                .filter(Files::isDirectory)      // 只扫描子目录
                .forEach(skillDir -> {
                    Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                    if (Files.isRegularFile(skillFile)) {
                        try {
                            PromptCommand command = loadSingleSkill(skillFile, skillDir);
                            if (command != null) {
                                commandMap.put(command.name(), command);
                            }
                        } catch (Exception e) {
                            // 单个 Skill 加载失败不影响其他 Skill
                            System.err.println("[Skill] Failed to load "
                                    + skillFile.toAbsolutePath() + ": " + e.getMessage());
                        }
                    }
                });
        } catch (IOException e) {
            System.err.println("[Skill] Failed to scan directory "
                    + skillsDir.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * 加载单个 SKILL.md 文件，解析为 PromptCommand
     *
     * 解析流程：
     * 1. 读取文件全部内容
     * 2. 分割 frontmatter 和 body
     * 3. 解析 YAML frontmatter → SkillFrontmatter
     * 4. 组装 PromptCommand
     *
     * @param skillFile SKILL.md 文件路径
     * @param skillDir  SKILL.md 所在目录路径（目录名用作默认 Skill 名称）
     * @return 解析后的 PromptCommand，解析失败时返回 null
     * @throws IOException 文件读取失败时抛出
     */
    private PromptCommand loadSingleSkill(Path skillFile, Path skillDir) throws IOException {
        // 1. 读取文件全部内容
        String fileContent = new String(Files.readAllBytes(skillFile), java.nio.charset.StandardCharsets.UTF_8);

        // 2. 分割 frontmatter 和 body
        //    frontmatter 被两个 --- 包裹在文件开头
        String[] parts = splitFrontmatterAndBody(fileContent);
        String yamlText = parts[0];   // YAML frontmatter 文本（可能为空）
        String bodyText = parts[1];   // Markdown 正文

        // 3. 解析 YAML frontmatter
        SkillFrontmatter frontmatter = parseFrontmatter(yamlText);

        // 4. 使用目录名作为默认 Skill 名称
        String defaultName = skillDir.getFileName().toString();

        // 5. 组装 PromptCommand
        return PromptCommand.fromFrontmatter(frontmatter, bodyText, skillDir,
                CommandSource.DISK, defaultName);
    }

    /**
     * 将 SKILL.md 文件内容分割为 frontmatter 和 body 两部分
     *
     * 分割规则：
     * - 文件必须以 --- 开头（第一行是 ---）
     * - 第二个 --- 标记 frontmatter 结束
     * - 第二个 --- 之后的所有内容都是 body
     * - 如果文件不以 --- 开头，认为没有 frontmatter，整个文件都是 body
     *
     * 示例：
     * <pre>
     * ---                    ← 识别为 frontmatter 开始
     * description: hello     ← frontmatter 内容
     * ---                    ← 识别为 frontmatter 结束
     * body content here      ← body 内容
     * </pre>
     *
     * @param content 文件全部内容
     * @return 长度为 2 的数组：[frontmatter文本, body文本]
     */
    private String[] splitFrontmatterAndBody(String content) {
        // 去掉可能的 BOM 标记
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        String trimmed = content.trim();

        // 检查是否以 --- 开头
        if (!trimmed.startsWith("---")) {
            // 没有 frontmatter，整个内容都是 body
            return new String[]{"", content};
        }

        // 找第二个 --- 的位置
        // 第一个 --- 从位置 0 开始，所以从位置 3 开始找第二个
        int secondDash = trimmed.indexOf("---", 3);
        if (secondDash == -1) {
            // 只有一个 ---，视为没有 frontmatter
            return new String[]{"", content};
        }

        // 提取 frontmatter（两个 --- 之间的内容）
        String frontmatter = trimmed.substring(3, secondDash).trim();

        // 提取 body（第二个 --- 之后的内容）
        String body = trimmed.substring(secondDash + 3).trim();

        return new String[]{frontmatter, body};
    }

    /**
     * 将 YAML 文本解析为 SkillFrontmatter 对象
     *
     * 为什么不用 yaml.loadAs(SkillFrontmatter.class)？
     *   因为 YAML 字段名使用连字符风格（如 allowed-tools, user-invocable），
     *   而 Java 字段名使用驼峰风格（如 allowedTools, userInvocable）。
     *   SnakeYAML 默认不支持这种映射，所以我们：
     *   1. 先解析为 Map<String, Object>
     *   2. 手动将连字符键映射到 SkillFrontmatter 的 setter
     *
     * 这种方式虽然多写了一些代码，但更加健壮——
     * 未知的 YAML 键会被安全忽略，不会导致解析异常。
     *
     * @param yamlText YAML 格式的 frontmatter 文本
     * @return 解析后的 SkillFrontmatter 对象
     */
    @SuppressWarnings("unchecked")
    private SkillFrontmatter parseFrontmatter(String yamlText) {
        SkillFrontmatter fm = new SkillFrontmatter();

        if (yamlText == null || yamlText.isEmpty()) {
            return fm;
        }

        // 使用 SnakeYAML 解析为 Map
        Object parsed = yaml.load(yamlText);
        if (!(parsed instanceof Map)) {
            // YAML 内容不是 Map 格式（可能是纯字符串或列表），返回默认值
            return fm;
        }

        Map<String, Object> map = (Map<String, Object>) parsed;

        // ---- 逐个字段映射（连字符 → 驼峰）----

        // name: string
        if (map.containsKey("name")) {
            fm.setName(String.valueOf(map.get("name")));
        }

        // description: string
        if (map.containsKey("description")) {
            fm.setDescription(String.valueOf(map.get("description")));
        }

        // disable-model-invocation: boolean
        if (map.containsKey("disable-model-invocation")) {
            fm.setDisableModelInvocation(toBoolean(map.get("disable-model-invocation")));
        }

        // user-invocable: boolean
        if (map.containsKey("user-invocable")) {
            fm.setUserInvocable(toBoolean(map.get("user-invocable")));
        }

        // allowed-tools: list<string>
        if (map.containsKey("allowed-tools")) {
            Object tools = map.get("allowed-tools");
            if (tools instanceof List) {
                List<String> toolList = new ArrayList<>();
                for (Object item : (List<?>) tools) {
                    toolList.add(String.valueOf(item));
                }
                fm.setAllowedTools(toolList);
            }
        }

        // context: string ("inline" or "fork")
        if (map.containsKey("context")) {
            fm.setContext(String.valueOf(map.get("context")));
        }

        // agent: string
        if (map.containsKey("agent")) {
            fm.setAgent(String.valueOf(map.get("agent")));
        }

        // argument-hint: string
        if (map.containsKey("argument-hint")) {
            fm.setArgumentHint(String.valueOf(map.get("argument-hint")));
        }

        // paths: list<string>
        if (map.containsKey("paths")) {
            Object paths = map.get("paths");
            if (paths instanceof List) {
                List<String> pathList = new ArrayList<>();
                for (Object item : (List<?>) paths) {
                    pathList.add(String.valueOf(item));
                }
                fm.setPaths(pathList);
            }
        }

        return fm;
    }

    /**
     * 安全地将 YAML 值转换为 boolean
     *
     * YAML 中的布尔值可能是 Boolean 类型（true/false），
     * 也可能是 String 类型（"true"/"false"/"yes"/"no"），
     * 这个方法统一处理这些情况。
     *
     * @param value YAML 解析出的值
     * @return 转换后的 boolean 值
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String s = ((String) value).toLowerCase();
            return "true".equals(s) || "yes".equals(s);
        }
        return false;
    }
}

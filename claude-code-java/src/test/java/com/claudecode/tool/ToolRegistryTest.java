package com.claudecode.tool;

import com.claudecode.api.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试
 *
 * 验证点：
 * 1. register / getTool 基本注册查找
 * 2. 重复注册应抛异常
 * 3. execute 便捷方法（成功 / 工具不存在 / 工具抛异常）
 * 4. getAllDefinitions 转换
 * 5. registerBuiltinTools 批量注册
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(System.getProperty("java.io.tmpdir"));
    }

    // ==================== register / getTool ====================

    @Test
    void register_andGetTool() {
        Tool fakeTool = createFakeTool("MyTool");

        registry.register(fakeTool);

        assertNotNull(registry.getTool("MyTool"));
        assertEquals("MyTool", registry.getTool("MyTool").name());
    }

    @Test
    void getTool_notFound_shouldReturnNull() {
        assertNull(registry.getTool("NonExistent"));
    }

    @Test
    void register_duplicateName_shouldThrow() {
        registry.register(createFakeTool("Dup"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(createFakeTool("Dup")));
    }

    // ==================== execute ====================

    @Test
    void execute_existingTool_shouldReturnResult() {
        registry.register(createFakeTool("Echo"));

        ToolResult result = registry.execute("Echo", Map.of("msg", "hello"));

        assertFalse(result.isError());
        assertEquals("fake output", result.getContent());
    }

    @Test
    void execute_unknownTool_shouldReturnError() {
        ToolResult result = registry.execute("Unknown", Map.of());

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("Unknown tool"));
    }

    @Test
    void execute_toolThrowsException_shouldCatchAndReturnError() {
        // 注册一个执行时会抛异常的工具
        Tool brokenTool = new Tool() {
            @Override public String name() { return "Broken"; }
            @Override public String description() { return ""; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean requiresPermission() { return false; }
            @Override public ToolResult execute(Map<String, Object> input) {
                throw new RuntimeException("something exploded");
            }
        };
        registry.register(brokenTool);

        ToolResult result = registry.execute("Broken", Map.of());

        assertTrue(result.isError());
        assertTrue(result.getContent().contains("something exploded"));
    }

    // ==================== getAllDefinitions ====================

    @Test
    void getAllDefinitions_shouldConvertAllTools() {
        registry.register(createFakeTool("ToolA"));
        registry.register(createFakeTool("ToolB"));

        List<ToolDefinition> defs = registry.getAllDefinitions();

        assertEquals(2, defs.size());
        assertEquals("ToolA", defs.get(0).getName());
        assertEquals("ToolB", defs.get(1).getName());
    }

    @Test
    void getAllDefinitions_empty_shouldReturnEmptyList() {
        assertTrue(registry.getAllDefinitions().isEmpty());
    }

    // ==================== registerBuiltinTools ====================

    @Test
    void registerBuiltinTools_shouldRegisterImplementedTools() {
        registry.registerBuiltinTools();

        // 已实现的工具应该被注册
        assertNotNull(registry.getTool("Read"));
        assertNotNull(registry.getTool("Bash"));

        // 总数应该 >= 2（至少 Read 和 Bash 已实现）
        assertTrue(registry.size() >= 2);
    }

    @Test
    void registerBuiltinTools_stubToolsShouldBeSkipped() {
        registry.registerBuiltinTools();

        // name 为空的 stub 工具不应该被注册
        assertNull(registry.getTool(""));
    }

    // ==================== 辅助方法 ====================

    /** 创建一个假工具用于测试，不依赖文件系统 */
    private Tool createFakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "fake tool " + name; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }
            @Override public boolean requiresPermission() { return false; }
            @Override public ToolResult execute(Map<String, Object> input) {
                return ToolResult.success("fake output");
            }
        };
    }
}

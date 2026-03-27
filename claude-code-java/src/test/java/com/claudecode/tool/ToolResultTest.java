package com.claudecode.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolResult 单元测试
 *
 * 验证点：
 * 1. success() 工厂方法创建的结果 isError=false
 * 2. error() 工厂方法创建的结果 isError=true
 * 3. content 内容正确传递
 */
class ToolResultTest {

    @Test
    void success_shouldCreateNonErrorResult() {
        ToolResult result = ToolResult.success("hello world");

        assertEquals("hello world", result.getContent());
        assertFalse(result.isError());
    }

    @Test
    void error_shouldCreateErrorResult() {
        ToolResult result = ToolResult.error("something went wrong");

        assertEquals("something went wrong", result.getContent());
        assertTrue(result.isError());
    }

    @Test
    void success_withEmptyContent() {
        ToolResult result = ToolResult.success("");

        assertEquals("", result.getContent());
        assertFalse(result.isError());
    }

    @Test
    void success_withNullContent() {
        // null 内容也应该能处理，不抛异常
        ToolResult result = ToolResult.success(null);

        assertNull(result.getContent());
        assertFalse(result.isError());
    }
}

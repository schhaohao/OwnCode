package com.claudecode.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * JSON-RPC 2.0 请求消息
 *
 * 两种形式：
 * - 请求（有 id）：需要等待响应
 * - 通知（无 id）：不需要响应，如 notifications/initialized
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcRequest {

    @JsonProperty("jsonrpc")
    private final String jsonrpc = "2.0";

    @JsonProperty("id")
    private final Integer id;

    @JsonProperty("method")
    private final String method;

    @JsonProperty("params")
    private final Map<String, Object> params;

    private JsonRpcRequest(Integer id, String method, Map<String, Object> params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }

    /** 创建带 id 的请求（需要等待响应） */
    public static JsonRpcRequest request(int id, String method, Map<String, Object> params) {
        return new JsonRpcRequest(id, method, params);
    }

    /** 创建通知（无 id，不需要响应） */
    public static JsonRpcRequest notification(String method, Map<String, Object> params) {
        return new JsonRpcRequest(null, method, params);
    }

    public String getJsonrpc() { return jsonrpc; }
    public Integer getId() { return id; }
    public String getMethod() { return method; }
    public Map<String, Object> getParams() { return params; }
}

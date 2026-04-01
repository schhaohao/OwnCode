package com.claudecode.mcp.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 响应消息
 *
 * 成功时 result 有值、error 为 null；
 * 失败时 error 有值、result 为 null。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcResponse {

    @JsonProperty("jsonrpc")
    private String jsonrpc;

    @JsonProperty("id")
    private Integer id;

    @JsonProperty("result")
    private Object result;

    @JsonProperty("error")
    private JsonRpcError error;

    public JsonRpcResponse() {}

    public String getJsonrpc() { return jsonrpc; }
    public Integer getId() { return id; }
    public Object getResult() { return result; }
    public JsonRpcError getError() { return error; }

    public boolean isError() {
        return error != null;
    }

    /**
     * JSON-RPC 2.0 错误对象
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonRpcError {

        @JsonProperty("code")
        private int code;

        @JsonProperty("message")
        private String message;

        @JsonProperty("data")
        private Object data;

        public JsonRpcError() {}

        public int getCode() { return code; }
        public String getMessage() { return message; }
        public Object getData() { return data; }

        @Override
        public String toString() {
            return "JsonRpcError{code=" + code + ", message='" + message + "'}";
        }
    }
}

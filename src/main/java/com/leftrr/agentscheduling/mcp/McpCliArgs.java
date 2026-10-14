package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把 agent_scheduling MCP server 挂给 claude/codex 的 CLI 启动参数构造——server 本体
 * （AppToolsServer / HttpAppToolsServer）不经过此类，它只服务客户端接线。
 * 端点由 Main 启动时经 {@link HttpAppToolsServer} 拉起后注入 {@link #configure}；
 * 未配置就构造参数属编程错误，直接抛出。
 */
public final class McpCliArgs {
    /** 主进程内 MCP server 的注册名：claude 配置键、codex 配置键、claude 工具名前缀共用。 */
    static final String SERVER_ID = "agent_scheduling";
    /** streamable HTTP 端点路径（HttpAppToolsServer 注册与客户端配置共用）。 */
    static final String MCP_ENDPOINT = "/mcp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile String endpointUrl;
    private static volatile String bearerToken;

    private McpCliArgs() {}

    /** HttpAppToolsServer 启动成功后注入端点与访问令牌。 */
    public static void configure(String url, String token) {
        endpointUrl = url;
        bearerToken = token;
    }

    public static String endpointUrl() {
        String url = endpointUrl;
        if (url == null) {
            throw new IllegalStateException("MCP server 端点未配置（HttpAppToolsServer 未启动）");
        }
        return url;
    }

    /** 仅供同包探针/测试断言用。 */
    static String bearerToken() {
        String token = bearerToken;
        if (token == null) {
            throw new IllegalStateException("MCP server 端点未配置（HttpAppToolsServer 未启动）");
        }
        return token;
    }

    /** claude 侧：--mcp-config 内联 JSON，type=http + Bearer token。 */
    public static List<String> claudeArgs() {
        ObjectNode server = MAPPER.createObjectNode();
        server.put("type", "http");
        server.put("url", endpointUrl());
        server.putObject("headers").put("Authorization", "Bearer " + bearerToken);
        ObjectNode config = MAPPER.createObjectNode();
        config.set("mcpServers", MAPPER.createObjectNode().set(SERVER_ID, server));
        return List.of("--mcp-config", config.toString());
    }

    /** codex 侧：两段 -c TOML 覆盖（streamable HTTP server 用 url + http_headers）。 */
    public static List<String> codexArgs() {
        try {
            return List.of(
                    "-c", "mcp_servers." + SERVER_ID + ".url=" + MAPPER.writeValueAsString(endpointUrl()),
                    "-c", "mcp_servers." + SERVER_ID + ".http_headers=" + MAPPER.writeValueAsString(
                            MAPPER.createObjectNode().put("Authorization", "Bearer " + bearerToken)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 MCP server 配置失败", e);
        }
    }
}

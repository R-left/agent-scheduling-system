package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把 agent_scheduling MCP server 挂载给 claude/codex 的 CLI 启动参数
 */
public final class McpCliArgs {
    /* MCP server注册名：Claude 配置键、codex 配置键、Claude 工具名前缀共用。 */
    static final String SERVER_ID = "agent_scheduling";
    /* MCP server版本描述 */
    public static final String SERVER_VERSION = "1.0.0";
    /* streamable HTTP 端点路径 */
    static final String MCP_ENDPOINT = "/mcp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile String endpointUrl;

    private McpCliArgs() {}

    /**
     * mcpServer启动成功后注入端点
     *
     * @param url 端点
     */
    public static void configure(String url) {
        endpointUrl = url;
    }

    public static String endpointUrl() {
        String url = endpointUrl;
        if (url == null) {
            throw new IllegalStateException("MCP server 端点未配置（HttpAppToolsServer 未启动）");
        }
        return url;
    }

    /**
     * Claude挂载mcp
     * 使用--mcp-config 挂载
     *
     * @return
     */
    public static List<String> claudeArgs() {
        ObjectNode server = MAPPER.createObjectNode();
        server.put("type", "http");
        server.put("url", endpointUrl());
        ObjectNode config = MAPPER.createObjectNode();
        config.set("mcpServers", MAPPER.createObjectNode().set(SERVER_ID, server));
        return List.of("--mcp-config", config.toString());
    }
    /**
     * codex挂载mcp
     * 使用-c挂载
     *
     * @return
     */
    public static List<String> codexArgs() {
        try {
            return List.of(
                    "-c", "mcp_servers." + SERVER_ID + ".url=" + MAPPER.writeValueAsString(endpointUrl()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 MCP server 配置失败", e);
        }
    }
}

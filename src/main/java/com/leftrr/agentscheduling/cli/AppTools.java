package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * agent_os MCP 工具名常量，以及"把本进程 MCP server 挂给 claude/codex"的启动参数。
 *
 * TS 版经 import.meta.url 定位 node/tsx 启动 app-tools-server 脚本；Java 单一进程形态，
 * 统一用「当前 JVM 可执行 + 当前 classpath + server main class」拉起本进程内的 MCP stdio
 * server（M5 提供 com.leftrr.agentscheduling.mcp.AppToolsServer）。系统属性
 * agentos.app-tools.server-main 可覆盖 main class（调试用）。
 */
public final class AppTools {
    public static final String CLARIFICATION_TOOL_NAME = "request_clarification";
    public static final String PRODUCT_SPEC_TOOL_NAME = "request_spec_approval";
    public static final String DISPATCH_TASK_TOOL_NAME = "dispatch_task";
    public static final String CLAUDE_CLARIFICATION_TOOL_NAME =
            "mcp__agent_os__" + CLARIFICATION_TOOL_NAME;
    public static final String CLAUDE_PRODUCT_SPEC_TOOL_NAME =
            "mcp__agent_os__" + PRODUCT_SPEC_TOOL_NAME;
    public static final String CLAUDE_DISPATCH_TASK_TOOL_NAME =
            "mcp__agent_os__" + DISPATCH_TASK_TOOL_NAME;

    static final String DEFAULT_SERVER_MAIN_CLASS =
            "com.leftrr.agentscheduling.mcp.AppToolsServer";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppTools() {}

    public record ServerInvocation(String command, List<String> args) {}

    public static String serverMainClass() {
        return System.getProperty("agentos.app-tools.server-main", DEFAULT_SERVER_MAIN_CLASS);
    }

    public static ServerInvocation serverInvocation() {
        String command = ProcessHandle.current().info().command().orElse("java");
        List<String> args = List.of("-cp", System.getProperty("java.class.path", ""), serverMainClass());
        return new ServerInvocation(command, args);
    }

    /** 对齐 claudeAppToolArgs()：--mcp-config 内联 JSON 配置。 */
    public static List<String> claudeAppToolArgs() {
        ServerInvocation invocation = serverInvocation();
        ObjectNode server = MAPPER.createObjectNode();
        server.put("type", "stdio");
        server.put("command", invocation.command());
        server.set("args", MAPPER.valueToTree(invocation.args()));
        ObjectNode servers = MAPPER.createObjectNode();
        servers.set("agent_os", server);
        ObjectNode config = MAPPER.createObjectNode();
        config.set("mcpServers", servers);
        return List.of("--mcp-config", config.toString());
    }

    /** 对齐 codexAppToolArgs()：两段 -c TOML 覆盖，值部分为 JSON 序列化文本。 */
    public static List<String> codexAppToolArgs() {
        ServerInvocation invocation = serverInvocation();
        try {
            return List.of(
                    "-c", "mcp_servers.agent_os.command=" + MAPPER.writeValueAsString(invocation.command()),
                    "-c", "mcp_servers.agent_os.args=" + MAPPER.writeValueAsString(invocation.args()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 MCP server 调用参数失败", e);
        }
    }
}

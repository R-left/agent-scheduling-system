package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void restoreServerMainProperty() {
        System.clearProperty("agentos.app-tools.server-main");
    }

    @Test
    void exposesSixToolNameConstants() {
        assertEquals("request_clarification", AppTools.CLARIFICATION_TOOL_NAME);
        assertEquals("request_spec_approval", AppTools.PRODUCT_SPEC_TOOL_NAME);
        assertEquals("dispatch_task", AppTools.DISPATCH_TASK_TOOL_NAME);
        assertEquals("mcp__agent_os__request_clarification", AppTools.CLAUDE_CLARIFICATION_TOOL_NAME);
        assertEquals("mcp__agent_os__request_spec_approval", AppTools.CLAUDE_PRODUCT_SPEC_TOOL_NAME);
        assertEquals("mcp__agent_os__dispatch_task", AppTools.CLAUDE_DISPATCH_TASK_TOOL_NAME);
    }

    @Test
    void serverInvocationUsesCurrentJvmAndClasspath() {
        AppTools.ServerInvocation invocation = AppTools.serverInvocation();
        // 命令取当前 JVM 可执行文件；无法取得时兜底 "java"
        String expected = ProcessHandle.current().info().command().orElse("java");
        assertEquals(expected, invocation.command());
        // 参数恰为 3 个：-cp <classpath> <server main class>
        assertEquals(3, invocation.args().size());
        assertEquals("-cp", invocation.args().get(0));
        assertEquals(AppTools.serverMainClass(), invocation.args().get(2));
    }

    @Test
    void serverMainClassDefaultsAndOverridable() {
        assertEquals("com.leftrr.agentscheduling.mcp.AppToolsServer", AppTools.serverMainClass());
        System.setProperty("agentos.app-tools.server-main", "debug.Main");
        assertEquals("debug.Main", AppTools.serverMainClass());
    }

    @Test
    void claudeAppToolArgsBuildInlineMcpConfig() throws Exception {
        List<String> args = AppTools.claudeAppToolArgs();
        assertEquals(2, args.size());
        assertEquals("--mcp-config", args.get(0));

        JsonNode config = MAPPER.readTree(args.get(1));
        JsonNode server = config.get("mcpServers").get("agent_os");
        assertEquals("stdio", server.get("type").asText());

        AppTools.ServerInvocation invocation = AppTools.serverInvocation();
        assertEquals(invocation.command(), server.get("command").asText());
        JsonNode serverArgs = server.get("args");
        assertEquals(invocation.args().size(), serverArgs.size());
        for (int i = 0; i < invocation.args().size(); i++) {
            assertEquals(invocation.args().get(i), serverArgs.get(i).asText());
        }
    }

    @Test
    void codexAppToolArgsBuildTomlOverrides() throws Exception {
        List<String> args = AppTools.codexAppToolArgs();
        assertEquals(4, args.size());
        assertEquals("-c", args.get(0));
        assertEquals("-c", args.get(2));

        AppTools.ServerInvocation invocation = AppTools.serverInvocation();

        // get(1) 形如 mcp_servers.agent_os.command="..."，值部分为 JSON 序列化字符串
        assertTrue(args.get(1).startsWith("mcp_servers.agent_os.command="));
        String commandValue = args.get(1).substring("mcp_servers.agent_os.command=".length());
        assertEquals(invocation.command(), MAPPER.readTree(commandValue).asText());

        // get(3) 形如 mcp_servers.agent_os.args=[...]，值部分解析为数组后逐元素比对
        assertTrue(args.get(3).startsWith("mcp_servers.agent_os.args="));
        String argsValue = args.get(3).substring("mcp_servers.agent_os.args=".length());
        JsonNode parsed = MAPPER.readTree(argsValue);
        assertTrue(parsed.isArray());
        assertEquals(invocation.args().size(), parsed.size());
        for (int i = 0; i < invocation.args().size(); i++) {
            assertEquals(invocation.args().get(i), parsed.get(i).asText());
        }
    }
}

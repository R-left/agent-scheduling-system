package com.leftrr.agentscheduling.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M5 Task 0 冒烟测试：只验证 MCP Java SDK 2.0.1 的装配链真实可用——
 * 类路径完整、Jackson3 实现可经 ServiceLoader 找到（{@link McpJsonDefaults#getMapper()}）、
 * server builder 链（sync → serverInfo → toolCall → build）能构建出 {@link McpSyncServer}。
 * 全程不经 stdio 会话：StdioServerTransportProvider 用内存流构造，读端立即 EOF。
 * 业务工具注册是 Task 1 的活，这里只有一个假工具 probe_tool。
 */
class McpSmokeTest {

    @Test
    void syncServerAssemblesAndFakeHandlerEchoes() {
        // McpJsonDefaults.getMapper() 经 ServiceLoader 定位 jackson3 实现；找不到会抛异常，本身就是断言
        var mapper = McpJsonDefaults.getMapper();
        assertNotNull(mapper, "Jackson3 mapper must be discoverable via ServiceLoader");

        var transport = new StdioServerTransportProvider(
                mapper,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream());

        var probeTool = McpSchema.Tool.builder("probe_tool")
                .description("probe")
                .inputSchema(Map.of("type", "object"))
                .build();
        assertEquals("probe_tool", probeTool.name());

        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                (exchange, request) -> McpSchema.CallToolResult.builder()
                        .addContent(new McpSchema.TextContent("ok"))
                        .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("agent-os", "1.0.0")
                .toolCall(probeTool, handler)
                .build();
        assertNotNull(server, "McpSyncServer must build from the sync specification chain");
        assertEquals("agent-os", server.getServerInfo().name());
        assertEquals("1.0.0", server.getServerInfo().version());

        // 直接调 handler（不经 stdio / 不经 server 分发）：回执 content[0] 为 "ok"
        McpSchema.CallToolResult result =
                handler.apply(null, new McpSchema.CallToolRequest("probe_tool", Map.of()));
        McpSchema.TextContent text = assertInstanceOf(McpSchema.TextContent.class, result.content().get(0));
        assertEquals("ok", text.text());
        // 实测偏差：Java builder 缺省 isError = false（显式 Boolean.FALSE），非 TS 的「字段缺省」——Task 1 回执断言以此为锚
        assertEquals(Boolean.FALSE, result.isError());

        server.close();
    }
}

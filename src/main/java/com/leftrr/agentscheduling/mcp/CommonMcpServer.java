package com.leftrr.agentscheduling.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;

/**
 * 自定义MCP server
 * 注册 request_clarification / request_spec_approval / dispatch_task
 * 三工具。工具只做 schema 校验和固定文案回执，真实流程（发卡、建流程）由
 * CLI 适配器捕获 tool_call 后在主进程完成
 */
public final class CommonMcpServer {
    /* 工具集 */
    private final List<SyncToolSpecification> toolSpecifications;

    private CommonMcpServer(McpJsonMapper mapper) {
        toolSpecifications = List.of(
                specification(ToolDefinitions.CLARIFICATION, mapper),
                specification(ToolDefinitions.PRODUCT_SPEC, mapper),
                specification(ToolDefinitions.DISPATCH_TASK, mapper));
    }

    /**
     * 构建工具集
     * @return CommonMcpServer
     */
    public static CommonMcpServer build() {
        return new CommonMcpServer(McpJsonDefaults.getMapper());
    }

    /**
     * 以 streamable HTTP transport 装配 server
     */
    public HttpServletStreamableServerTransportProvider connect() {
        HttpServletStreamableServerTransportProvider transport =
            HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint(McpCliArgs.MCP_ENDPOINT)
                .securityValidator(ServerTransportSecurityValidator.NOOP)
                .build();
        McpServer.sync(transport)
            .serverInfo(McpCliArgs.SERVER_ID, McpCliArgs.SERVER_VERSION)
            .tools(toolSpecifications)
            .build();
        return transport;
    }

    /**
     * 组装MCP同步工具
     *
     * @param definition 工具元信息载体
     * @param mapper 序列化器
     * @return 同步工具定义
     */
    private static SyncToolSpecification specification(ToolDefinitions.ToolDefinition definition,
                                                       McpJsonMapper mapper) {
        Tool tool = Tool.builder(definition.name())
                .title(definition.title())
                .description(definition.description())
                .inputSchema(mapper, definition.inputSchema())
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(definition.handler())
                .build();
    }
}

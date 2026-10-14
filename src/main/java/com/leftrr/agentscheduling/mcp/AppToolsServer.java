package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.ProductSpec;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * agent_scheduling MCP server：注册 request_clarification / request_spec_approval / dispatch_task
 * 三工具。server 只做 schema 校验和固定文案回执，真实副作用（发卡、建流程）由
 * CLI 适配器捕获 tool_call 后在主进程完成——这里无凭证、无网络副作用。
 *
 * <p>两种 transport 走同一 {@link #toolSpecifications} 装配：生产形态是长驻
 * streamable HTTP（{@link HttpAppToolsServer}，内嵌 Tomcat 跑在主进程里，全 bot
 * 全运行共享）；stdio 形态（{@link #main}）保留作调试入口，由适配层以子进程拉起。
 * HTTP 形态下本类跑在主进程内，无进程边界问题；core 用 Jackson 2、SDK 是
 * Jackson 3，两套 mapper 只在 {@link #toCoreJsonNode} 处跨界，只传
 * Map/List/基本形态。</p>
 */
public final class AppToolsServer {

    public static final String SERVER_NAME = "agent-scheduling-mcp";
    public static final String SERVER_VERSION = "1.0.0";

    /**
     * core 侧用 Jackson 2，SDK 是 Jackson 3（tools.jackson 包名）。
     * 跨界只传 Map/List/String/数字等基本形态，见 toCoreJsonNode。
     */
    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    private final List<SyncToolSpecification> toolSpecifications;

    private AppToolsServer(McpJsonMapper mapper) {
        toolSpecifications = List.of(
                specification(ToolDefinitions.CLARIFICATION, clarificationHandler(), mapper),
                specification(ToolDefinitions.PRODUCT_SPEC, productSpecHandler(), mapper),
                specification(ToolDefinitions.DISPATCH_TASK, dispatchTaskHandler(), mapper));
    }

    /** 默认 mapper 装配（Jackson3 实现经 ServiceLoader 定位）。 */
    public static AppToolsServer build() {
        return build(McpJsonDefaults.getMapper());
    }

    /** 可注入 McpJsonMapper 的装配。 */
    public static AppToolsServer build(McpJsonMapper mapper) {
        return new AppToolsServer(mapper);
    }

    /**
     * stdio 入口（调试用）：进程保活由 SDK 的 stdio 读循环线程（非 daemon）承载，
     * EOF 或 server.close() 才退出，main 不用自旋。
     */
    public static void main(String[] args) {
        build().connect(new StdioServerTransportProvider(McpJsonDefaults.getMapper()));
    }

    /** 以 stdio transport 装配 server。 */
    public McpSyncServer connect(McpServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .tools(toolSpecifications)
                .build();
    }

    /** 以 streamable HTTP transport 装配 server（HttpAppToolsServer 用）。 */
    public McpSyncServer connect(McpStreamableServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .tools(toolSpecifications)
                .build();
    }

    private static SyncToolSpecification specification(ToolDefinitions.ToolDefinition definition,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler,
            McpJsonMapper mapper) {
        Tool tool = Tool.builder(definition.name())
                .title(definition.title())
                .description(definition.description())
                .inputSchema(mapper, definition.inputSchema())
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    // ------------------------------------------------------------------
    // 三段 handler
    // ------------------------------------------------------------------

    /** 澄清回执；N 取 questions 数组 size（core 复校验已保证 1-5）。 */
    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> clarificationHandler() {
        return (exchange, request) -> {
            JsonNode input = toCoreJsonNode(request);
            Clarification.ClarificationRequest parsed = Clarification.parseRequest(input);
            if (parsed == null) {
                return rejection(ToolDefinitions.CLARIFICATION.rejection());
            }
            int count = input.path("questions").size();
            return CallToolResult.builder()
                    .addTextContent(ToolDefinitions.CLARIFICATION.receipt()
                            .replace("{count}", String.valueOf(count)))
                    .build();
        };
    }

    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> productSpecHandler() {
        return (exchange, request) -> ProductSpec.parseRequest(toCoreJsonNode(request)) == null
                ? rejection(ToolDefinitions.PRODUCT_SPEC.rejection())
                : CallToolResult.builder()
                        .addTextContent(ToolDefinitions.PRODUCT_SPEC.receipt())
                        .build();
    }

    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> dispatchTaskHandler() {
        return (exchange, request) -> Collaboration.parseDispatchTaskRequest(toCoreJsonNode(request)) == null
                ? rejection(ToolDefinitions.DISPATCH_TASK.rejection())
                : CallToolResult.builder()
                        .addTextContent(ToolDefinitions.DISPATCH_TASK.receipt())
                        .build();
    }

    /** 复校验失败回执：isError=true + 一条 text。 */
    private static CallToolResult rejection(String message) {
        return CallToolResult.builder()
                .isError(Boolean.TRUE)
                .addTextContent(message)
                .build();
    }

    /**
     * Jackson 3 / Jackson 2 的边界：arguments 是 Jackson 3 反序列化出的普通 Map，
     * 用 Jackson 2 valueToTree 转 core JsonNode——只传基本形态、无自定义类型，转换安全。
     * arguments 为 null 时原样返回 null。
     */
    private static JsonNode toCoreJsonNode(CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        return arguments == null ? null : JACKSON2.valueToTree(arguments);
    }
}

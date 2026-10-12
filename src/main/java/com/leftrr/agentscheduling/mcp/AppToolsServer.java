package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.AppTools;
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

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * agent_os MCP server 装配：注册三工具（request_clarification / request_spec_approval / dispatch_task），
 * 工具元数据与回执文案逐字对齐 TS src/mcp/app-tools-server.ts。
 *
 * <p>server 本体只做 schema 校验与固定文案回执；真实副作用（发卡、建流程）由 CLI 适配器捕获
 * tool_call 后在主进程完成（项目 CLAUDE.md「src/mcp — agent_os 工具服务」段）。
 * 无凭证、无网络副作用（Review Focus 6）。</p>
 *
 * <p>capabilities 走 SDK 默认：tools 能力随工具注册自动声明，不显式定制（Review Focus 5）；
 * 不注册任何 TS 没有的工具。</p>
 *
 * <p>stdio 装配形态（R-M5-2）：main 入口在本类——对齐 M2 预钉的
 * {@code AppTools.DEFAULT_SERVER_MAIN_CLASS = com.leftrr.agentscheduling.mcp.AppToolsServer}
 * 与 TS「app-tools-server.ts 自身即入口」的单文件形态。本类由 cli 适配层以子进程方式启动
 * （AppTools.serverInvocation / AppTools.serverMainClass），stdin/stdout 为 JSON-RPC 通道；
 * 日志走 stderr——stdout 污染会破坏协议。测试用内存流 transport 走同一 {@link #connect} 入口。</p>
 */
public final class AppToolsServer {

    /** serverInfo（TS McpServer 构造字面，设计裁决 3）。 */
    public static final String SERVER_NAME = "agent-os";
    public static final String SERVER_VERSION = "1.0.0";

    /**
     * Jackson 2 mapper（core 侧通用）。SDK 是 Jackson 3（tools.jackson 包名），两套 mapper 的
     * 边界仅在 {@link #toCoreJsonNode}：跨 mapper 只传 Map/List/String/数字等基本形态
     * （CallToolRequest.arguments() 是 Jackson 3 反序列化的普通 Map），转换安全（设计裁决 6）。
     */
    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    // 工具 title（TS registerTool 元数据，逐字）。
    private static final String CLARIFICATION_TITLE = "向用户提问";
    private static final String PRODUCT_SPEC_TITLE = "提交产品文档";
    private static final String DISPATCH_TASK_TITLE = "把任务交给团队成员";

    // 工具 description：TS 为多行字符串数组 .join('')（无分隔符），此处拼成同一单串，含中文标点逐字。
    private static final String CLARIFICATION_DESCRIPTION =
            "当产品需求仍有会实质影响方案的歧义时，调用此工具向用户展示飞书问题卡片。"
                    + "一次最多提交 5 个问题，每题提供 2 到 4 个清晰选项。"
                    + "提交后不要自行补全用户答案，本轮回复可以简短收束。";
    private static final String PRODUCT_SPEC_DESCRIPTION =
            "产品方案已经生成后，调用此工具提交唯一的待确认产物。"
                    + "deliveryMode=local 时提交 specPath 与 ticketsPath，并确保文件真实存在。"
                    + "deliveryMode=lark-doc 时只提交 documentUrl，且必须使用 lark-doc 创建或更新成功结果中的 document.url；"
                    + "该文档必须同时包含产品说明与「实现任务（Tickets）」章节。"
                    + "同一份方案不要同时维护本地 Markdown 和飞书云文档，避免两个来源互相覆盖。"
                    + "summary 只写便于快速了解方案的摘要，完整内容保留在所选产物中。"
                    + "提交前必须完成需求澄清，确保这份方案已经可以确认。"
                    + "调用后停止工作，不要实现代码或委派团队成员。";
    private static final String DISPATCH_TASK_DESCRIPTION =
            "把任务确定性地交给一名已注册的长期团队成员。"
                    + "只有 CEO 助理可以在运行时调用；产品经理和开发者调用会被拒绝。"
                    + "targetBotId 必须是团队名单中的成员 id，不能填写自己。"
                    + "objective 写协作目标，instruction 写交给对方的完整要求，expectedOutput 写期望产出。"
                    + "调用后停止工作，等待对方完成并把结果交回。";

    /**
     * core 复校验失败的回执文案（自拟）。TS 侧 zod 校验失败由 SDK 返回 zod issue 明细
     * （如 '同一道问题的选项 ID 不能重复'），issue 文案逐条随入参变化，无统一逐字文案可对齐；
     * Java 侧文案自拟，只对齐形态（isError=true + 一条 text content，设计裁决 1）。
     */
    private static final String CLARIFICATION_REJECTION =
            "入参未通过 Agent OS 校验，本次 request_clarification 调用已被拒绝。";
    private static final String PRODUCT_SPEC_REJECTION =
            "入参未通过 Agent OS 校验，本次 request_spec_approval 调用已被拒绝。";
    private static final String DISPATCH_TASK_REJECTION =
            "入参未通过 Agent OS 校验，本次 dispatch_task 调用已被拒绝。";

    // 回执文案（TS 三段 handler 逐字，设计裁决 4）。
    private static final String CLARIFICATION_RECEIPT_PREFIX = "已把 ";
    private static final String CLARIFICATION_RECEIPT_SUFFIX = " 个问题交给 Agent OS，请等待用户回答。";
    private static final String PRODUCT_SPEC_RECEIPT = "唯一的产品方案产物已交给 Agent OS，等待用户查看。";
    private static final String DISPATCH_TASK_RECEIPT = "派发请求已交给 Agent OS，等待协作任务送达目标成员。";

    private final List<SyncToolSpecification> toolSpecifications;

    private AppToolsServer(McpJsonMapper mapper) {
        toolSpecifications = List.of(
                toolSpecification(AppTools.CLARIFICATION_TOOL_NAME, CLARIFICATION_TITLE,
                        CLARIFICATION_DESCRIPTION, ToolSchemas.CLARIFICATION_INPUT_SCHEMA,
                        clarificationHandler(), mapper),
                toolSpecification(AppTools.PRODUCT_SPEC_TOOL_NAME, PRODUCT_SPEC_TITLE,
                        PRODUCT_SPEC_DESCRIPTION, ToolSchemas.PRODUCT_SPEC_INPUT_SCHEMA,
                        productSpecHandler(), mapper),
                toolSpecification(AppTools.DISPATCH_TASK_TOOL_NAME, DISPATCH_TASK_TITLE,
                        DISPATCH_TASK_DESCRIPTION, ToolSchemas.DISPATCH_TASK_INPUT_SCHEMA,
                        dispatchTaskHandler(), mapper));
    }

    /** 默认 mapper 装配（Jackson3 实现经 ServiceLoader 定位）。 */
    public static AppToolsServer build() {
        return build(McpJsonDefaults.getMapper());
    }

    /** 可注入 McpJsonMapper 的装配（测试 / stdio 入口共用）。 */
    public static AppToolsServer build(McpJsonMapper mapper) {
        return new AppToolsServer(mapper);
    }

    /**
     * stdio 入口：由 cli 适配层以子进程方式启动（AppTools.serverMainClass 指向本类），
     * stdin/stdout 即 JSON-RPC 通道（StdioServerTransportProvider 单参构造接 System.in/out，
     * SDK 字节码实测）；日志走 stderr——stdout 污染会破坏协议。
     *
     * <p>接线形态：main 只做 {@code build() + connect(new StdioServerTransportProvider(mapper))}
     * ——build() 已含三工具装配与 McpServer.sync 链（connect 内），此处不重复装配、不双建 sync 链。
     * 进程保活由 SDK 的 stdio 读循环线程承载（stdio-inbound 为 Executors.newSingleThreadExecutor
     * 创建的非 daemon 线程，设计裁决 7 实测核实）：EOF 或 server.close() 才退出，main 无需自旋。</p>
     */
    public static void main(String[] args) {
        build().connect(new StdioServerTransportProvider(McpJsonDefaults.getMapper()));
    }

    /** 三工具注册清单（顺序：澄清 → 产品 → 协作，对齐 TS registerTool 顺序）。 */
    public List<SyncToolSpecification> toolSpecifications() {
        return toolSpecifications;
    }

    /** 以给定 transport 装配 server（Task 2 stdio 入口与测试内存流共用）。 */
    public McpSyncServer connect(McpServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .tools(toolSpecifications)
                .build();
    }

    private static SyncToolSpecification toolSpecification(String name, String title, String description,
            String schemaJson, BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler,
            McpJsonMapper mapper) {
        Tool tool = Tool.builder(name)
                .title(title)
                .description(description)
                .inputSchema(mapper, schemaJson)
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    // ------------------------------------------------------------------
    // 三段 handler（对齐 TS app-tools-server.ts 28-33 / 51-56 / 72-77）
    // ------------------------------------------------------------------

    /**
     * 澄清回执：core 复校验通过后回执「已把 N 个问题…」。N 从入参 questions 数组取 size
     * （设计裁决 2：TS 从 zod 解析结果取 questions.length；Java 侧 questions 无 default/transform，
     * 两者等值；core 复校验已保证其为 1-5 的数组）。
     */
    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> clarificationHandler() {
        return (exchange, request) -> {
            JsonNode input = toCoreJsonNode(request);
            Clarification.ClarificationRequest parsed = Clarification.parseRequest(input);
            if (parsed == null) {
                return rejection(CLARIFICATION_REJECTION);
            }
            int count = input.path("questions").size();
            return CallToolResult.builder()
                    .addTextContent(CLARIFICATION_RECEIPT_PREFIX + count + CLARIFICATION_RECEIPT_SUFFIX)
                    .build();
        };
    }

    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> productSpecHandler() {
        return (exchange, request) -> ProductSpec.parseRequest(toCoreJsonNode(request)) == null
                ? rejection(PRODUCT_SPEC_REJECTION)
                : CallToolResult.builder().addTextContent(PRODUCT_SPEC_RECEIPT).build();
    }

    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> dispatchTaskHandler() {
        return (exchange, request) -> Collaboration.parseDispatchTaskRequest(toCoreJsonNode(request)) == null
                ? rejection(DISPATCH_TASK_REJECTION)
                : CallToolResult.builder().addTextContent(DISPATCH_TASK_RECEIPT).build();
    }

    /** core 复校验失败回执：isError=true + 一条 text content（builder 缺省 isError=FALSE，成功路径不显式设）。 */
    private static CallToolResult rejection(String message) {
        return CallToolResult.builder()
                .isError(Boolean.TRUE)
                .addTextContent(message)
                .build();
    }

    /**
     * SDK（Jackson 3）与 core（Jackson 2）的边界：CallToolRequest.arguments() 是 Jackson 3
     * 反序列化出的普通 Map（内嵌 List/String/数字/布尔等基本形态），用 Jackson 2
     * ObjectMapper.valueToTree 转 core 侧 JsonNode——仅传基本形态，无自定义类型，转换安全
     * （设计裁决 6）。arguments 为 null（客户端未带参）时返回 null，core parseXxx(null) 返回 null。
     */
    private static JsonNode toCoreJsonNode(CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        return arguments == null ? null : JACKSON2.valueToTree(arguments);
    }
}

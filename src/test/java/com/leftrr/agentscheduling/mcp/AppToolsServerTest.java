package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.AppTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 Task 1：三工具 JSON Schema 黄金断言 + handler 行为 + 工具元数据 + server 装配。
 *
 * <p>schema 断言逐字段对齐 TS zod（clarification.ts / product-spec.ts / collaboration.ts）：
 * required 集合、min/max、pattern、minItems/maxItems、default 字段、
 * discriminatedUnion → anyOf + const + additionalProperties:false（.strict()）。</p>
 *
 * <p>handler 直接调 {@link SyncToolSpecification#callHandler()}（不经 stdio，exchange 传 null，
 * 对齐 McpSmokeTest 的直连范式）。注意：直连路径绕过 SDK 的 validateToolInputs 结构校验
 * （该层只在 server 的 tools/call 分发上执行，Task 2 端到端钉住其会话形态）；
 * 本文件钉住的是 handler 内 core 复校验的行为。</p>
 */
class AppToolsServerTest {

    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    private static final String CLARIFY_RECEIPT_1 = "已把 1 个问题交给 Agent OS，请等待用户回答。";
    private static final String CLARIFY_RECEIPT_5 = "已把 5 个问题交给 Agent OS，请等待用户回答。";
    private static final String SPEC_RECEIPT = "唯一的产品方案产物已交给 Agent OS，等待用户查看。";
    private static final String DISPATCH_RECEIPT = "派发请求已交给 Agent OS，等待协作任务送达目标成员。";

    private final AppToolsServer appToolsServer = AppToolsServer.build();

    // ------------------------------------------------------------------
    // schema 黄金断言：request_clarification（对齐 ClarificationRequestSchema）
    // ------------------------------------------------------------------

    @Test
    void clarificationSchemaRootStructure() throws Exception {
        JsonNode schema = jsonSchema(ToolSchemas.CLARIFICATION_INPUT_SCHEMA);
        assertEquals("object", schema.path("type").asText());
        assertEquals(List.of("title", "intro", "questions"), fieldNames(schema.path("properties")),
                "properties 键序对齐 zod 字段声明序");
        assertEquals(List.of("questions"), stringValues(schema.path("required")),
                "required 恰为 questions（title/intro 是 default/optional 字段）");
        assertFalse(schema.has("additionalProperties"),
                "zod 非严格 object 不落 additionalProperties（对齐 TS zod→JSON Schema 产物）");
    }

    @Test
    void clarificationSchemaTitleIntroFields() throws Exception {
        JsonNode schema = jsonSchema(ToolSchemas.CLARIFICATION_INPUT_SCHEMA);
        JsonNode title = schema.path("properties").path("title");
        assertEquals("string", title.path("type").asText());
        assertEquals(1, title.path("minLength").asInt());
        assertEquals(80, title.path("maxLength").asInt());
        assertEquals("需求澄清", title.path("default").asText(), "zod default('需求澄清')");
        JsonNode intro = schema.path("properties").path("intro");
        assertEquals("string", intro.path("type").asText());
        assertEquals(300, intro.path("maxLength").asInt());
        assertEquals("", intro.path("default").asText(), "zod optional().default('')");
        assertFalse(intro.has("minLength"), "intro 无 min(1)，只有 max(300)");
    }

    @Test
    void clarificationSchemaQuestionsArray() throws Exception {
        JsonNode questions = jsonSchema(ToolSchemas.CLARIFICATION_INPUT_SCHEMA)
                .path("properties").path("questions");
        assertEquals("array", questions.path("type").asText());
        assertEquals(1, questions.path("minItems").asInt());
        assertEquals(5, questions.path("maxItems").asInt());
        assertEquals("object", questions.path("items").path("type").asText());
    }

    @Test
    void clarificationSchemaQuestionFields() throws Exception {
        JsonNode question = jsonSchema(ToolSchemas.CLARIFICATION_INPUT_SCHEMA)
                .path("properties").path("questions").path("items");
        assertEquals(List.of("id", "prompt", "options", "recommendedOptionId"),
                fieldNames(question.path("properties")));
        assertEquals(List.of("id", "prompt", "options"), stringValues(question.path("required")),
                "recommendedOptionId optional 不进 required");
        assertEquals("^[a-z0-9_-]{1,32}$", question.path("properties").path("id").path("pattern").asText());
        JsonNode prompt = question.path("properties").path("prompt");
        assertEquals(1, prompt.path("minLength").asInt());
        assertEquals(300, prompt.path("maxLength").asInt());
        assertEquals("^[a-z0-9_-]{1,32}$",
                question.path("properties").path("recommendedOptionId").path("pattern").asText());
    }

    @Test
    void clarificationSchemaOptionFields() throws Exception {
        JsonNode options = jsonSchema(ToolSchemas.CLARIFICATION_INPUT_SCHEMA)
                .path("properties").path("questions").path("items")
                .path("properties").path("options");
        assertEquals("array", options.path("type").asText());
        assertEquals(2, options.path("minItems").asInt());
        assertEquals(4, options.path("maxItems").asInt());
        JsonNode option = options.path("items");
        assertEquals(List.of("id", "label"), fieldNames(option.path("properties")));
        assertEquals(List.of("id", "label"), stringValues(option.path("required")));
        assertEquals("^[a-z0-9_-]{1,32}$", option.path("properties").path("id").path("pattern").asText());
        JsonNode label = option.path("properties").path("label");
        assertEquals(1, label.path("minLength").asInt());
        assertEquals(100, label.path("maxLength").asInt());
    }

    // ------------------------------------------------------------------
    // schema 黄金断言：request_spec_approval（对齐 ProductSpecRequestSchema）
    // ------------------------------------------------------------------

    @Test
    void productSpecSchemaAnyOfTwoBranches() throws Exception {
        JsonNode schema = jsonSchema(ToolSchemas.PRODUCT_SPEC_INPUT_SCHEMA);
        assertTrue(schema.has("anyOf"), "discriminatedUnion → anyOf（设计裁决 1）");
        assertEquals(2, schema.path("anyOf").size());
        assertFalse(schema.has("type"), "union 根节点无 type");
    }

    @Test
    void productSpecSchemaLocalBranch() throws Exception {
        JsonNode local = jsonSchema(ToolSchemas.PRODUCT_SPEC_INPUT_SCHEMA).path("anyOf").get(0);
        assertEquals("object", local.path("type").asText());
        assertTrue(local.has("additionalProperties"), ".strict() 必须落 additionalProperties");
        assertFalse(local.path("additionalProperties").asBoolean());
        assertEquals(List.of("title", "summary", "deliveryMode", "specPath", "ticketsPath"),
                fieldNames(local.path("properties")));
        assertEquals(List.of("title", "summary", "deliveryMode", "specPath", "ticketsPath"),
                stringValues(local.path("required")), "strict 分支 required 全字段");
        JsonNode deliveryMode = local.path("properties").path("deliveryMode");
        assertEquals("string", deliveryMode.path("type").asText());
        assertEquals("local", deliveryMode.path("const").asText(), "z.literal('local') → const");
        assertEquals(80, local.path("properties").path("title").path("maxLength").asInt());
        assertEquals(1, local.path("properties").path("title").path("minLength").asInt());
        assertEquals(500, local.path("properties").path("summary").path("maxLength").asInt());
        JsonNode specPath = local.path("properties").path("specPath");
        assertEquals(1, specPath.path("minLength").asInt());
        assertEquals(240, specPath.path("maxLength").asInt());
        JsonNode ticketsPath = local.path("properties").path("ticketsPath");
        assertEquals(1, ticketsPath.path("minLength").asInt());
        assertEquals(240, ticketsPath.path("maxLength").asInt());
    }

    @Test
    void productSpecSchemaLarkBranch() throws Exception {
        JsonNode lark = jsonSchema(ToolSchemas.PRODUCT_SPEC_INPUT_SCHEMA).path("anyOf").get(1);
        assertEquals("object", lark.path("type").asText());
        assertTrue(lark.has("additionalProperties"));
        assertFalse(lark.path("additionalProperties").asBoolean());
        assertEquals(List.of("title", "summary", "deliveryMode", "documentUrl"),
                fieldNames(lark.path("properties")));
        assertEquals(List.of("title", "summary", "deliveryMode", "documentUrl"),
                stringValues(lark.path("required")));
        assertEquals("lark-doc", lark.path("properties").path("deliveryMode").path("const").asText());
        JsonNode documentUrl = lark.path("properties").path("documentUrl");
        assertEquals("string", documentUrl.path("type").asText());
        assertEquals("uri", documentUrl.path("format").asText(), "z.url() → format uri（refine 语义不进 schema）");
    }

    // ------------------------------------------------------------------
    // schema 黄金断言：dispatch_task（对齐 DispatchTaskRequestSchema）
    // ------------------------------------------------------------------

    @Test
    void dispatchSchemaFields() throws Exception {
        JsonNode schema = jsonSchema(ToolSchemas.DISPATCH_TASK_INPUT_SCHEMA);
        assertEquals("object", schema.path("type").asText());
        assertEquals(List.of("targetBotId", "objective", "instruction", "expectedOutput"),
                fieldNames(schema.path("properties")));
        assertEquals(List.of("targetBotId", "objective", "instruction"), stringValues(schema.path("required")),
                "expectedOutput optional 不进 required");
        assertEquals("^[a-z0-9][a-z0-9_-]{0,31}$",
                schema.path("properties").path("targetBotId").path("pattern").asText());
        JsonNode objective = schema.path("properties").path("objective");
        assertEquals(1, objective.path("minLength").asInt());
        assertEquals(200, objective.path("maxLength").asInt());
        JsonNode instruction = schema.path("properties").path("instruction");
        assertEquals(1, instruction.path("minLength").asInt());
        assertEquals(2000, instruction.path("maxLength").asInt());
        JsonNode expectedOutput = schema.path("properties").path("expectedOutput");
        assertEquals(1, expectedOutput.path("minLength").asInt());
        assertEquals(500, expectedOutput.path("maxLength").asInt());
        assertFalse(schema.has("additionalProperties"),
                "zod 非严格 object 不落 additionalProperties");
    }

    // ------------------------------------------------------------------
    // handler 行为（直连 callHandler；core 复校验路径）
    // ------------------------------------------------------------------

    @Test
    void clarifyHandlerSingleQuestionReturnsReceipt() {
        CallToolResult result = call(clarificationSpec(), clarifyArgs(question("q1")));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(CLARIFY_RECEIPT_1, textOf(result));
    }

    @Test
    void clarifyHandlerFiveQuestionsReturnsReceiptWithCount() {
        CallToolResult result = call(clarificationSpec(), clarifyArgs(
                question("q1"), question("q2"), question("q3"), question("q4"), question("q5")));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(CLARIFY_RECEIPT_5, textOf(result));
    }

    @Test
    void clarifyHandlerSemanticallyInvalidReturnsError() {
        // options 重复 id：结构合法（JSON Schema 无唯一性约束），core parseRequest 复校验拒绝（设计裁决 1）
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", "q1");
        question.put("prompt", "方向？");
        question.put("options", List.of(option("opt_a", "方案 A"), option("opt_a", "方案 A 副本")));
        CallToolResult result = call(clarificationSpec(), clarifyArgs(question));
        assertEquals(Boolean.TRUE, result.isError(), "core 复校验失败必须 isError=true");
        assertNotEquals(CLARIFY_RECEIPT_1, textOf(result), "失败不得回执成功文案");
    }

    @Test
    void specHandlerLocalValidReturnsReceipt() {
        CallToolResult result = call(productSpecSpec(), localSpecArgs());
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(SPEC_RECEIPT, textOf(result));
    }

    @Test
    void specHandlerLarkValidReturnsReceipt() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("deliveryMode", "lark-doc");
        args.put("title", "方案标题");
        args.put("summary", "方案摘要");
        args.put("documentUrl", "https://example.feishu.cn/docx/abcd1234");
        CallToolResult result = call(productSpecSpec(), args);
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(SPEC_RECEIPT, textOf(result));
    }

    @Test
    void specHandlerMissingDeliveryModeRejected() {
        // 直连 handler 不经 SDK 的 validateToolInputs（Task 2 端到端钉住其会话形态）；
        // 此路径由 core parseRequest 复校验拒绝（deliveryMode 缺失 → null → isError）
        Map<String, Object> args = localSpecArgs();
        args.remove("deliveryMode");
        CallToolResult result = call(productSpecSpec(), args);
        assertEquals(Boolean.TRUE, result.isError());
        assertNotEquals(SPEC_RECEIPT, textOf(result));
    }

    @Test
    void specHandlerCrossBranchFieldsRejected() {
        // local 分支混入 documentUrl：JSON Schema 层 additionalProperties:false 会拒、
        // core exactlyKeys 亦拒；直连 handler 走 core 复校验路径，两路径结果一致（isError=true）
        Map<String, Object> args = localSpecArgs();
        args.put("documentUrl", "https://example.feishu.cn/docx/abcd1234");
        CallToolResult result = call(productSpecSpec(), args);
        assertEquals(Boolean.TRUE, result.isError());
        assertNotEquals(SPEC_RECEIPT, textOf(result));
    }

    @Test
    void dispatchHandlerValidReturnsReceipt() {
        CallToolResult result = call(dispatchSpec(), dispatchArgs());
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(DISPATCH_RECEIPT, textOf(result));
    }

    @Test
    void dispatchHandlerMissingTargetBotIdRejected() {
        Map<String, Object> args = dispatchArgs();
        args.remove("targetBotId");
        CallToolResult result = call(dispatchSpec(), args);
        assertEquals(Boolean.TRUE, result.isError());
        assertNotEquals(DISPATCH_RECEIPT, textOf(result));
    }

    @Test
    void dispatchHandlerWithoutExpectedOutputReturnsReceipt() {
        Map<String, Object> args = dispatchArgs();
        args.remove("expectedOutput");
        CallToolResult result = call(dispatchSpec(), args);
        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertEquals(DISPATCH_RECEIPT, textOf(result));
    }

    // ------------------------------------------------------------------
    // 工具元数据逐字（TS registerTool 的 title/description，description 为多行 join 单串）
    // ------------------------------------------------------------------

    @Test
    void clarificationToolMetadataVerbatim() {
        McpSchema.Tool tool = clarificationSpec().tool();
        assertEquals(AppTools.CLARIFICATION_TOOL_NAME, tool.name());
        assertEquals("向用户提问", tool.title());
        assertEquals("当产品需求仍有会实质影响方案的歧义时，调用此工具向用户展示飞书问题卡片。"
                        + "一次最多提交 5 个问题，每题提供 2 到 4 个清晰选项。"
                        + "提交后不要自行补全用户答案，本轮回复可以简短收束。",
                tool.description());
    }

    @Test
    void productSpecToolMetadataVerbatim() {
        McpSchema.Tool tool = productSpecSpec().tool();
        assertEquals(AppTools.PRODUCT_SPEC_TOOL_NAME, tool.name());
        assertEquals("提交产品文档", tool.title());
        assertEquals("产品方案已经生成后，调用此工具提交唯一的待确认产物。"
                        + "deliveryMode=local 时提交 specPath 与 ticketsPath，并确保文件真实存在。"
                        + "deliveryMode=lark-doc 时只提交 documentUrl，且必须使用 lark-doc 创建或更新成功结果中的 document.url；"
                        + "该文档必须同时包含产品说明与「实现任务（Tickets）」章节。"
                        + "同一份方案不要同时维护本地 Markdown 和飞书云文档，避免两个来源互相覆盖。"
                        + "summary 只写便于快速了解方案的摘要，完整内容保留在所选产物中。"
                        + "提交前必须完成需求澄清，确保这份方案已经可以确认。"
                        + "调用后停止工作，不要实现代码或委派团队成员。",
                tool.description());
    }

    @Test
    void dispatchToolMetadataVerbatim() {
        McpSchema.Tool tool = dispatchSpec().tool();
        assertEquals(AppTools.DISPATCH_TASK_TOOL_NAME, tool.name());
        assertEquals("把任务交给团队成员", tool.title());
        assertEquals("把任务确定性地交给一名已注册的长期团队成员。"
                        + "只有 CEO 助理可以在运行时调用；产品经理和开发者调用会被拒绝。"
                        + "targetBotId 必须是团队名单中的成员 id，不能填写自己。"
                        + "objective 写协作目标，instruction 写交给对方的完整要求，expectedOutput 写期望产出。"
                        + "调用后停止工作，等待对方完成并把结果交回。",
                tool.description());
    }

    // ------------------------------------------------------------------
    // server 装配：恰三工具 + serverInfo（内存流 transport，不经真实 stdio）
    // ------------------------------------------------------------------

    @Test
    void serverAssemblesWithThreeToolsAndServerInfo() {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream());
        McpSyncServer server = appToolsServer.connect(transport);
        try {
            assertEquals("agent-os", server.getServerInfo().name(), "TS McpServer 构造字面（设计裁决 3）");
            assertEquals("1.0.0", server.getServerInfo().version());
            List<McpSchema.Tool> tools = server.listTools();
            assertEquals(3, tools.size(), "恰注册 TS 的三个工具，不多不少");
            assertEquals(
                    Set.of(AppTools.CLARIFICATION_TOOL_NAME,
                            AppTools.PRODUCT_SPEC_TOOL_NAME,
                            AppTools.DISPATCH_TASK_TOOL_NAME),
                    tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet()));
        } finally {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // 夹具与辅助
    // ------------------------------------------------------------------

    private SyncToolSpecification clarificationSpec() {
        return specFor(AppTools.CLARIFICATION_TOOL_NAME);
    }

    private SyncToolSpecification productSpecSpec() {
        return specFor(AppTools.PRODUCT_SPEC_TOOL_NAME);
    }

    private SyncToolSpecification dispatchSpec() {
        return specFor(AppTools.DISPATCH_TASK_TOOL_NAME);
    }

    private SyncToolSpecification specFor(String toolName) {
        return appToolsServer.toolSpecifications().stream()
                .filter(spec -> spec.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not registered: " + toolName));
    }

    /** 直连 handler（exchange 传 null，对齐 McpSmokeTest 范式；直连绕过 SDK 结构校验层）。 */
    private static CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), args));
    }

    private static String textOf(CallToolResult result) {
        assertEquals(1, result.content().size(), "回执恰一条 text content");
        TextContent text = assertInstanceOf(TextContent.class, result.content().get(0));
        return text.text();
    }

    private static JsonNode jsonSchema(String schemaText) throws Exception {
        return JACKSON2.readTree(schemaText);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** required 是字符串数组，取元素文本列表（含序）。 */
    private static List<String> stringValues(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Map<String, Object> option(String id, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        return option;
    }

    private static Map<String, Object> question(String id) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", id);
        question.put("prompt", "方向？");
        question.put("options", List.of(option("opt_a", "方案 A"), option("opt_b", "方案 B")));
        return question;
    }

    private static Map<String, Object> clarifyArgs(Map<String, Object>... questions) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("questions", List.of(questions));
        return args;
    }

    private static Map<String, Object> localSpecArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("deliveryMode", "local");
        args.put("title", "方案标题");
        args.put("summary", "方案摘要");
        args.put("specPath", "docs/spec.md");
        args.put("ticketsPath", "docs/tickets.md");
        return args;
    }

    private static Map<String, Object> dispatchArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("targetBotId", "dev_01");
        args.put("objective", "完成登录页");
        args.put("instruction", "按规范实现登录页");
        args.put("expectedOutput", "可运行的代码");
        return args;
    }
}

package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliRunResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8：ProductSpec 测试（对齐 TS 版 src/core/product-spec.ts 语义）。
 * JsonNode 用 Jackson readTree 构造；safeParse 失败 → parseRequest 返回 null。
 */
class ProductSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对齐 Node new Date().toISOString()：ISO 毫秒格式（3 位小数 + Z）。 */
    private static final Pattern ISO_MILLIS =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CliRunResult.ToolCall call(String toolName, JsonNode input) {
        return new CliRunResult.ToolCall("tool-1", toolName, input);
    }

    private static JsonNode localRequest() {
        return json("""
                {"deliveryMode":"local","title":"产品方案","summary":"摘要内容",
                 "specPath":"specs/product.md","ticketsPath":"tickets/list.md"}
                """);
    }

    private static JsonNode larkRequest() {
        return json("""
                {"deliveryMode":"lark-doc","title":"产品方案","summary":"摘要内容",
                 "documentUrl":"https://ex.feishu.cn/docx/AbC_1-2"}
                """);
    }

    private static JsonNode localRequestWith(String specPathJson, String ticketsPathJson) {
        return localRequestFull("\"产品方案\"", "\"摘要内容\"", specPathJson, ticketsPathJson);
    }

    private static JsonNode localRequestFull(String titleJson, String summaryJson,
            String specPathJson, String ticketsPathJson) {
        return json("{\"deliveryMode\":\"local\",\"title\":" + titleJson
                + ",\"summary\":" + summaryJson
                + ",\"specPath\":" + specPathJson + ",\"ticketsPath\":" + ticketsPathJson + "}");
    }

    private static ProductSpec.CreateFlowOptions options(ProductSpec.ProductSpecRequest request) {
        return new ProductSpec.CreateFlowOptions("task-1", "bot-leader", "sess-1",
                "ou-owner", "union-owner", null, request);
    }

    // ===== schema 解析 =====

    // 1. local 合法请求 → Local 变体，deliveryMode="local"，字段 trim 后保留
    @Test
    void parseRequestLocalValid() {
        JsonNode input = json("""
                {"deliveryMode":"local","title":"  产品方案  ","summary":" 摘要内容 ",
                 "specPath":" specs/product.md ","ticketsPath":"tickets/list.md\\t"}
                """);
        ProductSpec.ProductSpecRequest parsed = ProductSpec.parseRequest(input);
        ProductSpec.ProductSpecRequest.Local local = assertInstanceOf(
                ProductSpec.ProductSpecRequest.Local.class, parsed);
        assertEquals("local", local.deliveryMode());
        assertEquals("产品方案", local.title());
        assertEquals("摘要内容", local.summary());
        assertEquals("specs/product.md", local.specPath());
        assertEquals("tickets/list.md", local.ticketsPath());
    }

    // 2. lark-doc 合法请求 → Lark 变体，deliveryMode="lark-doc"，documentUrl 原样保留
    @Test
    void parseRequestLarkValid() {
        ProductSpec.ProductSpecRequest.Lark lark = assertInstanceOf(
                ProductSpec.ProductSpecRequest.Lark.class, ProductSpec.parseRequest(larkRequest()));
        assertEquals("lark-doc", lark.deliveryMode());
        assertEquals("产品方案", lark.title());
        assertEquals("摘要内容", lark.summary());
        assertEquals("https://ex.feishu.cn/docx/AbC_1-2", lark.documentUrl());
    }

    // 3. title 81 字符 / 全空白 → null；summary 501 字符 / 全空白 → null；恰 80/500 → 通过
    @Test
    void parseRequestTitleSummaryLimits() {
        assertNull(ProductSpec.parseRequest(localRequestFull(
                "\"" + "a".repeat(81) + "\"", "\"摘要内容\"", "\"specs/product.md\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestFull(
                "\"   \"", "\"摘要内容\"", "\"specs/product.md\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestFull(
                "\"产品方案\"", "\"" + "b".repeat(501) + "\"", "\"specs/product.md\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestFull(
                "\"产品方案\"", "\"   \"", "\"specs/product.md\"", "\"tickets/list.md\"")));
        // 恰 80 / 500 → 通过
        ProductSpec.ProductSpecRequest parsed = ProductSpec.parseRequest(localRequestFull(
                "\"" + "a".repeat(80) + "\"", "\"" + "c".repeat(500) + "\"",
                "\"specs/product.md\"", "\"tickets/list.md\""));
        assertEquals("a".repeat(80), assertInstanceOf(
                ProductSpec.ProductSpecRequest.Local.class, parsed).title());
        assertEquals("c".repeat(500), parsed.summary());
    }

    // 4. deliveryMode 缺失 / 非字符串 / "other" → null
    @Test
    void parseRequestDeliveryModeValidation() {
        assertNull(ProductSpec.parseRequest(json("""
                {"title":"t","summary":"s","specPath":"specs/product.md","ticketsPath":"tickets/list.md"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":123,"title":"t","summary":"s","specPath":"specs/product.md","ticketsPath":"tickets/list.md"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"other","title":"t","summary":"s","specPath":"specs/product.md","ticketsPath":"tickets/list.md"}
                """)));
    }

    // 5. specPath：以 / 开头 → null；.. 段（两种分隔符）→ null；241 字符 → null；空串 → null；恰 240 → 通过
    @Test
    void parseRequestSpecPathRules() {
        assertNull(ProductSpec.parseRequest(localRequestWith("\"/abs/path\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith("\"a/../b\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith("\"a\\\\..\\\\b\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith(
                "\"" + "a".repeat(241) + "\"", "\"tickets/list.md\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith("\"   \"", "\"tickets/list.md\"")));
        ProductSpec.ProductSpecRequest parsed = ProductSpec.parseRequest(
                localRequestWith("\"" + "a".repeat(240) + "\"", "\"tickets/list.md\""));
        assertEquals("a".repeat(240), assertInstanceOf(
                ProductSpec.ProductSpecRequest.Local.class, parsed).specPath());
    }

    // 6. ticketsPath 同 5（两条路径独立校验）
    @Test
    void parseRequestTicketsPathRules() {
        assertNull(ProductSpec.parseRequest(localRequestWith("\"specs/product.md\"", "\"/abs/path\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith("\"specs/product.md\"", "\"a/../b\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith("\"specs/product.md\"", "\"a\\\\..\\\\b\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith(
                "\"specs/product.md\"", "\"" + "a".repeat(241) + "\"")));
        assertNull(ProductSpec.parseRequest(localRequestWith("\"specs/product.md\"", "\"   \"")));
        ProductSpec.ProductSpecRequest parsed = ProductSpec.parseRequest(
                localRequestWith("\"specs/product.md\"", "\"" + "a".repeat(240) + "\""));
        assertEquals("a".repeat(240), assertInstanceOf(
                ProductSpec.ProductSpecRequest.Local.class, parsed).ticketsPath());
    }

    // 7. .strict() 未知键拒绝：local 多 documentUrl → null；lark 多 specPath → null
    @Test
    void parseRequestStrictUnknownKeys() {
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"local","title":"t","summary":"s",
                 "specPath":"specs/product.md","ticketsPath":"tickets/list.md",
                 "documentUrl":"https://ex.feishu.cn/docx/AbC_1-2"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"lark-doc","title":"t","summary":"s",
                 "documentUrl":"https://ex.feishu.cn/docx/AbC_1-2",
                 "specPath":"specs/product.md"}
                """)));
    }

    // 8. documentUrl：非法 URL / 无 scheme / 无 host / 无 /docx/ 路径段 → null；合法飞书 Docx 链接 → 通过
    @Test
    void parseRequestDocumentUrlValidation() {
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"lark-doc","title":"t","summary":"s","documentUrl":"not-a-url"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"lark-doc","title":"t","summary":"s","documentUrl":"example.com/docx/abc"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"lark-doc","title":"t","summary":"s","documentUrl":"https:/x"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"lark-doc","title":"t","summary":"s","documentUrl":"mailto:a@b"}
                """)));
        assertNull(ProductSpec.parseRequest(json("""
                {"deliveryMode":"lark-doc","title":"t","summary":"s","documentUrl":"https://ex.feishu.cn/wiki/abc"}
                """)));
        assertInstanceOf(ProductSpec.ProductSpecRequest.Lark.class,
                ProductSpec.parseRequest(larkRequest()));
    }

    // 9. documentToken：/docx/<token>（含尾斜杠）提取；多段路径 / 无 docx → null
    @Test
    void documentTokenExtraction() {
        assertEquals("Tok-1_2", ProductSpec.documentToken("https://e.cn/docx/Tok-1_2"));
        assertEquals("abc", ProductSpec.documentToken("https://e.cn/docx/abc/"));
        assertNull(ProductSpec.documentToken("https://e.cn/docx/a/b"));
        assertNull(ProductSpec.documentToken("https://e.cn/wiki/abc"));
    }

    // 10. findProductSpecRequest 回溯语义：靠后优先 / 后非法前合法 / 全非法 → null；其他工具名跳过
    @Test
    void findProductSpecRequestBacktracking() {
        // 靠后优先：两个合法请求，取靠后的
        List<CliRunResult.ToolCall> calls = List.of(
                call("request_spec_approval", localRequest()),
                call("request_spec_approval", larkRequest()));
        assertInstanceOf(ProductSpec.ProductSpecRequest.Lark.class,
                ProductSpec.findProductSpecRequest(calls));
        // 后非法前合法：靠后请求 schema 失败，回落到靠前的合法请求
        JsonNode invalidSpecPath = json("""
                {"deliveryMode":"local","title":"t","summary":"s",
                 "specPath":"/abs/path","ticketsPath":"tickets/list.md"}
                """);
        calls = List.of(
                call("request_spec_approval", localRequest()),
                call("request_spec_approval", invalidSpecPath));
        assertInstanceOf(ProductSpec.ProductSpecRequest.Local.class,
                ProductSpec.findProductSpecRequest(calls));
        // 全非法 → null
        calls = List.of(
                call("request_spec_approval", invalidSpecPath),
                call("request_spec_approval", json("""
                        {"deliveryMode":"lark-doc","title":"t","summary":"s","documentUrl":"not-a-url"}
                        """)));
        assertNull(ProductSpec.findProductSpecRequest(calls));
        // request_spec_approval 之外的工具名跳过（即便 payload 恰好是合法请求）
        calls = List.of(
                call("request_clarification", localRequest()),
                call("dispatch_task", larkRequest()));
        assertNull(ProductSpec.findProductSpecRequest(calls));
    }

    // ===== 流程库 =====

    // 11. create：token 32 位无连字符；同 (taskId, botId) pending 旧流程置 expired；approved/expired 不动；不同 bot/task 的 pending 不动
    @Test
    void storeCreateExpiresPendingFlows() throws Exception {
        ProductSpec.ProductSpecFlowStore store = new ProductSpec.ProductSpecFlowStore();
        ProductSpec.ProductSpecFlow first = store.create(options(ProductSpec.parseRequest(localRequest())));
        assertTrue(first.token().matches("[0-9a-f]{32}"));
        assertEquals(ProductSpec.SpecStatus.PENDING, first.status());

        ProductSpec.ProductSpecFlow second = store.create(options(ProductSpec.parseRequest(localRequest())));
        assertEquals(ProductSpec.SpecStatus.EXPIRED, first.status());
        assertEquals(ProductSpec.SpecStatus.PENDING, second.status());

        // approved / expired 的旧流程不动
        ProductSpec.ProductSpecFlow approvedSeed = new ProductSpec.ProductSpecFlow(
                "tok-approved", "task-1", "bot-leader", "sess-1", "ou-owner", "union-owner",
                null, ProductSpec.parseRequest(localRequest()),
                ProductSpec.SpecStatus.APPROVED, "2026-10-13T00:00:00.000Z");
        ProductSpec.ProductSpecFlow expiredSeed = new ProductSpec.ProductSpecFlow(
                "tok-expired", "task-1", "bot-leader", "sess-1", "ou-owner", "union-owner",
                null, ProductSpec.parseRequest(localRequest()),
                ProductSpec.SpecStatus.EXPIRED, null);
        ProductSpec.ProductSpecFlowStore seeded = new ProductSpec.ProductSpecFlowStore(
                List.of(approvedSeed, expiredSeed));
        seeded.create(options(ProductSpec.parseRequest(localRequest())));
        assertEquals(ProductSpec.SpecStatus.APPROVED, seeded.get("tok-approved").status());
        assertEquals(ProductSpec.SpecStatus.EXPIRED, seeded.get("tok-expired").status());

        // 不同 bot / 不同 task 的 pending 不动
        ProductSpec.CreateFlowOptions otherBot = new ProductSpec.CreateFlowOptions(
                "task-1", "bot-product", "sess-1", "ou-owner", "union-owner", null,
                ProductSpec.parseRequest(localRequest()));
        ProductSpec.CreateFlowOptions otherTask = new ProductSpec.CreateFlowOptions(
                "task-2", "bot-leader", "sess-1", "ou-owner", "union-owner", null,
                ProductSpec.parseRequest(localRequest()));
        ProductSpec.ProductSpecFlowStore cross = new ProductSpec.ProductSpecFlowStore();
        ProductSpec.ProductSpecFlow otherBotFlow = cross.create(otherBot);
        ProductSpec.ProductSpecFlow otherTaskFlow = cross.create(otherTask);
        cross.create(options(ProductSpec.parseRequest(localRequest())));
        assertEquals(ProductSpec.SpecStatus.PENDING, otherBotFlow.status());
        assertEquals(ProductSpec.SpecStatus.PENDING, otherTaskFlow.status());
    }

    // 12. approve：pending → approved 且 approvedAt 匹配 ISO 毫秒格式；非 pending / 未知 token → null
    @Test
    void storeApproveTransitions() throws Exception {
        ProductSpec.ProductSpecFlowStore store = new ProductSpec.ProductSpecFlowStore();
        ProductSpec.ProductSpecFlow flow = store.create(options(ProductSpec.parseRequest(localRequest())));
        ProductSpec.ProductSpecFlow approved = store.approve(flow.token());
        assertEquals(ProductSpec.SpecStatus.APPROVED, approved.status());
        assertTrue(ISO_MILLIS.matcher(approved.approvedAt()).matches());

        // 已 approved（非 pending）→ null
        assertNull(store.approve(flow.token()));
        // 未知 token → null
        assertNull(store.approve("missing-token"));
        // expired → null：pending 流程被后续 create 置 expired 后不可再 approve
        ProductSpec.ProductSpecFlow second = store.create(options(ProductSpec.parseRequest(localRequest())));
        ProductSpec.ProductSpecFlow third = store.create(options(ProductSpec.parseRequest(localRequest())));
        assertEquals(ProductSpec.SpecStatus.EXPIRED, second.status());
        assertNull(store.approve(second.token()));
        assertEquals(ProductSpec.SpecStatus.APPROVED, store.approve(third.token()).status());
        // 已 approved 的旧流程不被后续 create 置 expired（approved 不动）
        assertEquals(ProductSpec.SpecStatus.APPROVED, flow.status());
    }

    // 13. get：命中 / 未命中
    @Test
    void storeGetHitAndMiss() throws Exception {
        ProductSpec.ProductSpecFlowStore store = new ProductSpec.ProductSpecFlowStore();
        assertNull(store.get("no-such-token"));
        ProductSpec.ProductSpecFlow flow = store.create(options(ProductSpec.parseRequest(larkRequest())));
        assertSame(flow, store.get(flow.token()));
    }

    // 14. isProductSpecOwner 四分支：union 优先且相等/不等；缺 union 回落 openId
    @Test
    void isProductSpecOwnerFourBranches() {
        // 双方 union 都在且相等 → true
        assertTrue(ProductSpec.isProductSpecOwner("ou-a", "un-1", "ou-x", "un-1"));
        // 双方 union 都在但不等 → false
        assertFalse(ProductSpec.isProductSpecOwner("ou-a", "un-1", "ou-a", "un-2"));
        // flow 缺 union → 回落 openId 相等（即便 operator 带 union）→ true
        assertTrue(ProductSpec.isProductSpecOwner("ou-a", null, "ou-a", "un-1"));
        assertTrue(ProductSpec.isProductSpecOwner("ou-a", "", "ou-a", "un-1"));
        // openId 不等 → false
        assertFalse(ProductSpec.isProductSpecOwner("ou-a", null, "ou-b", null));
        // 对齐 TS `===`：双方 openId 均 null → true；一方 null → false
        assertTrue(ProductSpec.isProductSpecOwner(null, null, null, null));
        assertFalse(ProductSpec.isProductSpecOwner(null, null, "ou-a", null));
        assertFalse(ProductSpec.isProductSpecOwner("ou-a", null, null, null));
    }

    // 15. findPendingByDocument：botId + pending + lark-doc + documentToken 四条件齐 → 命中；任一不符 → null
    @Test
    void findPendingByDocumentConditions() throws Exception {
        ProductSpec.ProductSpecFlowStore store = new ProductSpec.ProductSpecFlowStore();
        store.create(new ProductSpec.CreateFlowOptions("task-1", "bot-product", "sess-1",
                "ou-owner", "union-owner", null, ProductSpec.parseRequest(localRequest())));
        store.create(new ProductSpec.CreateFlowOptions("task-1", "bot-product", "sess-1",
                "ou-owner", "union-owner", null, ProductSpec.parseRequest(larkRequest())));

        // 四条件齐 → 命中 lark 流程
        ProductSpec.ProductSpecFlow hit = store.findPendingByDocument("bot-product", "AbC_1-2");
        assertEquals(ProductSpec.SpecStatus.PENDING, hit.status());
        assertInstanceOf(ProductSpec.ProductSpecRequest.Lark.class, hit.request());
        // botId 不符 → null
        assertNull(store.findPendingByDocument("bot-leader", "AbC_1-2"));
        // 状态非 pending → null
        ProductSpec.ProductSpecFlow flow = store.findPendingByDocument("bot-product", "AbC_1-2");
        store.approve(flow.token());
        assertNull(store.findPendingByDocument("bot-product", "AbC_1-2"));
        // local 请求（documentToken 无从谈起）→ null
        ProductSpec.ProductSpecFlowStore localOnly = new ProductSpec.ProductSpecFlowStore();
        localOnly.create(options(ProductSpec.parseRequest(localRequest())));
        assertNull(localOnly.findPendingByDocument("bot-leader", null));
    }

    // 16. snapshot/restore：snapshot 为深拷贝（后续 approve 不影响早期快照）；restore 还原
    @Test
    void snapshotIsDeepCopyAndRestoreReverts() throws Exception {
        ProductSpec.ProductSpecFlowStore store = new ProductSpec.ProductSpecFlowStore();
        ProductSpec.ProductSpecFlow flow = store.create(options(ProductSpec.parseRequest(localRequest())));
        List<ProductSpec.ProductSpecFlow> snap = store.snapshot();

        // approve 原库中的流程 → 早期快照不受影响（structuredClone 对齐）
        assertNotNull(store.approve(flow.token()));
        assertEquals(ProductSpec.SpecStatus.APPROVED, store.get(flow.token()).status());
        ProductSpec.ProductSpecFlow snapFlow = snap.get(0);
        assertEquals(ProductSpec.SpecStatus.PENDING, snapFlow.status());
        assertNull(snapFlow.approvedAt());
        assertNotSame(snapFlow, store.get(flow.token()));

        // restore 后库内该 flow 回到 pending
        store.restore(snap);
        assertEquals(ProductSpec.SpecStatus.PENDING, store.get(flow.token()).status());
        assertNull(store.get(flow.token()).approvedAt());
    }
}

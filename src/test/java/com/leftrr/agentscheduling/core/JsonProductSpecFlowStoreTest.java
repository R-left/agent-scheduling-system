package com.leftrr.agentscheduling.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9：JsonProductSpecFlowStore 测试（brief Step 1 用例清单 1-10）。
 * 对应 TS 版 src/core/product-spec-store.ts：快照回滚、原子写、非法行跳过（safeParse）。
 */
class JsonProductSpecFlowStoreTest {

    @TempDir
    Path tempDir;

    // ---- 测试私有工厂 ----

    private String path(String fileName) {
        return tempDir.resolve(fileName).toString();
    }

    private static ProductSpec.CreateFlowOptions localOptions(String taskId) {
        return new ProductSpec.CreateFlowOptions(taskId, "bot-leader", "sess-" + taskId,
                "ou-owner", null, null,
                new ProductSpec.ProductSpecRequest.Local("local", "产品方案", "摘要内容",
                        "specs/product.md", "tickets/list.md"));
    }

    /** 预置 pending local 流程行（键序无关紧要，加载按名取字段）。 */
    private static String pendingLocalRow(String token, String taskId, String botId) {
        return "{\"token\":\"" + token + "\",\"taskId\":\"" + taskId + "\",\"botId\":\"" + botId
                + "\",\"sessionId\":\"sess-" + token + "\",\"ownerOpenId\":\"ou-" + token + "\""
                + ",\"request\":{\"deliveryMode\":\"local\",\"title\":\"产品方案\",\"summary\":\"摘要内容\","
                + "\"specPath\":\"specs/product.md\",\"ticketsPath\":\"tickets/list.md\"}"
                + ",\"status\":\"pending\"}";
    }

    private void writeRaw(String file, String content) throws IOException {
        Files.writeString(Path.of(file), content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    // ---- 用例 1：文件不存在 → 构造成功、内存为空库；create 后文件与父目录被创建，文件含新 token ----

    @Test
    void missingFileStartsEmptyAndCreateCreatesFileAndParent() throws IOException {
        String file = path("nested/spec-flows.json");
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        assertNull(store.get("anything"));
        assertFalse(Files.exists(Path.of(file)));
        ProductSpec.ProductSpecFlow flow = store.create(localOptions("task-1"));
        assertTrue(Files.exists(Path.of(file)));
        String text = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        assertTrue(text.contains(flow.token()));
    }

    // ---- 用例 2：根节点非数组 → 构造抛 IOException，消息逐字 ----

    @Test
    void constructorRejectsNonArrayRootWithExactMessage() throws IOException {
        String file = path("spec-flows.json");
        writeRaw(file, "{}");
        IOException error = assertThrows(IOException.class,
                () -> new JsonProductSpecFlowStore(file));
        assertEquals("产品方案状态文件格式错误: " + file, error.getMessage());
    }

    // ---- 用例 3：非法行跳过（对齐 flatMap + safeParse），合法行保留 ----

    @Test
    void loadSkipsInvalidRowsAndKeepsValidOnes() throws IOException {
        String file = path("spec-flows.json");
        String requestJson = "{\"deliveryMode\":\"local\",\"title\":\"产品方案\",\"summary\":\"摘要内容\","
                + "\"specPath\":\"specs/product.md\",\"ticketsPath\":\"tickets/list.md\"}";
        writeRaw(file, """
                [
                  {"token":"t1","taskId":"task-1","botId":"bot-leader","sessionId":"sess-1","ownerOpenId":"ou-1","request":%s,"status":"bogus"},
                  {"token":"t2","taskId":"task-2","botId":"bot-leader","sessionId":"sess-2","ownerOpenId":"ou-2","request":{"deliveryMode":"local","title":"产品方案","summary":"摘要内容","specPath":"specs/product.md","ticketsPath":"tickets/list.md","extra":1},"status":"pending"},
                  {"token":"t3","taskId":"task-3","botId":"bot-leader","sessionId":"sess-3","ownerOpenId":"ou-3","collaboration":{"taskId":"task-3","fromBotId":"bot-x","reportToBotId":"bot-leader","round":0,"maxRounds":3},"request":%s,"status":"pending"},
                  {"token":"t4","taskId":"task-4","botId":"bot-leader","sessionId":"sess-4","ownerOpenId":"ou-4","request":%s,"status":"approved","approvedAt":"not-iso"},
                  {"token":"t5","taskId":"task-5","botId":"bot-leader","sessionId":"sess-5","ownerOpenId":"ou-5","ownerUnionId":"","request":%s,"status":"pending"},
                  {"token":"t6","taskId":"task-6","botId":"bot-leader","sessionId":"sess-6","ownerOpenId":"ou-6","request":%s,"status":"pending"}
                ]
                """.formatted(requestJson, requestJson, requestJson, requestJson, requestJson));
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        assertNull(store.get("t1"), "status 非法值应丢弃");
        assertNull(store.get("t2"), "request 未知键（strict 违规）应丢弃");
        assertNull(store.get("t3"), "collaboration.round=0 应丢弃");
        assertNull(store.get("t4"), "approvedAt 非 ISO 应丢弃");
        assertNull(store.get("t5"), "ownerUnionId 空串应丢弃");
        assertNotNull(store.get("t6"), "合法行应保留");
    }

    // ---- 用例 4：合法文件加载往返（local 与 lark-doc 两条流程，含 collaboration、approvedAt）----

    @Test
    void roundTripLocalAndLarkFlowsFieldByField() throws IOException {
        String file = path("spec-flows.json");
        writeRaw(file, """
                [
                  {"token":"tok-local","taskId":"task-1","botId":"bot-leader","sessionId":"sess-1","ownerOpenId":"ou-1","request":{"deliveryMode":"local","title":"产品方案","summary":"摘要内容","specPath":"specs/product.md","ticketsPath":"tickets/list.md"},"status":"pending"},
                  {"token":"tok-lark","taskId":"task-2","botId":"bot-product","sessionId":"sess-2","ownerOpenId":"ou-2","ownerUnionId":"union-2","collaboration":{"taskId":"task-2","fromBotId":"bot-leader","reportToBotId":"bot-product","round":1,"maxRounds":3},"request":{"deliveryMode":"lark-doc","title":"产品方案","summary":"摘要内容","documentUrl":"https://ex.feishu.cn/docx/AbC_1-2"},"status":"approved","approvedAt":"2026-01-02T03:04:05.006Z"}
                ]
                """);
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);

        ProductSpec.ProductSpecFlow local = store.get("tok-local");
        assertNotNull(local);
        assertEquals("tok-local", local.token());
        assertEquals("task-1", local.taskId());
        assertEquals("bot-leader", local.botId());
        assertEquals("sess-1", local.sessionId());
        assertEquals("ou-1", local.ownerOpenId());
        assertNull(local.ownerUnionId());
        assertNull(local.collaboration());
        ProductSpec.ProductSpecRequest.Local localRequest = assertInstanceOf(
                ProductSpec.ProductSpecRequest.Local.class, local.request());
        assertEquals("local", localRequest.deliveryMode());
        assertEquals("产品方案", localRequest.title());
        assertEquals("摘要内容", localRequest.summary());
        assertEquals("specs/product.md", localRequest.specPath());
        assertEquals("tickets/list.md", localRequest.ticketsPath());
        assertEquals(ProductSpec.SpecStatus.PENDING, local.status());
        assertNull(local.approvedAt());

        ProductSpec.ProductSpecFlow lark = store.get("tok-lark");
        assertNotNull(lark);
        assertEquals("tok-lark", lark.token());
        assertEquals("task-2", lark.taskId());
        assertEquals("bot-product", lark.botId());
        assertEquals("sess-2", lark.sessionId());
        assertEquals("ou-2", lark.ownerOpenId());
        assertEquals("union-2", lark.ownerUnionId());
        assertEquals(new Collaboration.CollaborationOrigin(
                "task-2", "bot-leader", "bot-product", 1, 3), lark.collaboration());
        ProductSpec.ProductSpecRequest.Lark larkRequest = assertInstanceOf(
                ProductSpec.ProductSpecRequest.Lark.class, lark.request());
        assertEquals("lark-doc", larkRequest.deliveryMode());
        assertEquals("产品方案", larkRequest.title());
        assertEquals("摘要内容", larkRequest.summary());
        assertEquals("https://ex.feishu.cn/docx/AbC_1-2", larkRequest.documentUrl());
        assertEquals(ProductSpec.SpecStatus.APPROVED, lark.status());
        assertEquals("2026-01-02T03:04:05.006Z", lark.approvedAt());
    }

    // ---- 用例 5：黄金格式往返（create + approve 后重读文件）----

    @Test
    void persistedFileContainsLocalModeApprovedStatusIsoApprovedAt() throws IOException {
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(path("spec-flows.json"));
        ProductSpec.ProductSpecFlow flow = store.create(localOptions("task-1"));
        store.approve(flow.token());
        String text = Files.readString(Path.of(path("spec-flows.json")), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"deliveryMode\": \"local\""));
        assertTrue(text.contains("\"status\": \"approved\""));
        assertTrue(Pattern.compile(
                        "\"approvedAt\": \"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\"")
                .matcher(text).find());
    }

    // ---- 用例 6：create 触发持久化（重开 store 可 get）----

    @Test
    void createPersistsNewTokenForReopenedStore() throws IOException {
        String file = path("spec-flows.json");
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        ProductSpec.ProductSpecFlow flow = store.create(localOptions("task-1"));
        JsonProductSpecFlowStore reopened = new JsonProductSpecFlowStore(file);
        ProductSpec.ProductSpecFlow loaded = reopened.get(flow.token());
        assertNotNull(loaded);
        assertEquals(ProductSpec.SpecStatus.PENDING, loaded.status());
    }

    // ---- 用例 7：create 的过期置位落盘（旧 pending 流程重开为 expired）----

    @Test
    void createExpiresOldPendingFlowAndPersistsIt() throws IOException {
        String file = path("spec-flows.json");
        writeRaw(file, "[" + pendingLocalRow("tok-old", "task-1", "bot-leader") + "]");
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        store.create(localOptions("task-1"));
        JsonProductSpecFlowStore reopened = new JsonProductSpecFlowStore(file);
        assertEquals(ProductSpec.SpecStatus.EXPIRED, reopened.get("tok-old").status());
    }

    // ---- 用例 8：approve 落盘（重开可读 approvedAt 与 approved）----

    @Test
    void approvePersistsApprovedStatusAndApprovedAt() throws IOException {
        String file = path("spec-flows.json");
        writeRaw(file, "[" + pendingLocalRow("tok-1", "task-1", "bot-leader") + "]");
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        store.approve("tok-1");
        JsonProductSpecFlowStore reopened = new JsonProductSpecFlowStore(file);
        ProductSpec.ProductSpecFlow loaded = reopened.get("tok-1");
        assertEquals(ProductSpec.SpecStatus.APPROVED, loaded.status());
        assertNotNull(loaded.approvedAt());
        assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
                .matcher(loaded.approvedAt()).matches());
    }

    // ---- 用例 9：persist 失败 → 内存态回滚，异常透传（TS mutate 的 snapshot/restore 语义）----

    @Test
    void approveRollsBackMemoryWhenPersistFails() throws IOException {
        String file = path("spec-flows.json");
        writeRaw(file, "[" + pendingLocalRow("tok-1", "task-1", "bot-leader") + "]");
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        // 原路径换成同名非空目录 → persist 的 move 必败（Windows 下 Files.move(file, 目录) 抛 IOException）
        Files.delete(Path.of(file));
        Files.createDirectory(Path.of(file));
        Files.writeString(Path.of(file).resolve("keep.txt"), "block", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.approve("tok-1"));
        assertEquals(ProductSpec.SpecStatus.PENDING, store.get("tok-1").status());
        assertNull(store.get("tok-1").approvedAt());
    }

    // ---- 用例 10：回滚后修复文件路径 → 再 approve 成功（库状态可继续演进）----

    @Test
    void approveSucceedsAfterPathFixedFollowingRollback() throws IOException {
        String file = path("spec-flows.json");
        writeRaw(file, "[" + pendingLocalRow("tok-1", "task-1", "bot-leader") + "]");
        JsonProductSpecFlowStore store = new JsonProductSpecFlowStore(file);
        Files.delete(Path.of(file));
        Files.createDirectory(Path.of(file));
        Files.writeString(Path.of(file).resolve("keep.txt"), "block", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.approve("tok-1"));

        // 修复：删目录、恢复合法文件内容
        deleteRecursively(Path.of(file));
        writeRaw(file, "[" + pendingLocalRow("tok-1", "task-1", "bot-leader") + "]");
        ProductSpec.ProductSpecFlow flow = store.approve("tok-1");
        assertNotNull(flow);
        assertEquals(ProductSpec.SpecStatus.APPROVED, flow.status());
        assertNotNull(flow.approvedAt());
        JsonProductSpecFlowStore reopened = new JsonProductSpecFlowStore(file);
        assertEquals(ProductSpec.SpecStatus.APPROVED, reopened.get("tok-1").status());
    }
}

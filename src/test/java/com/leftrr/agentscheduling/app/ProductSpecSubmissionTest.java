package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.app.ProductSpecSubmission.Retry;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec.ProductSpecRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 3：ensureProductSpecSubmission 测试（brief Step 1 ③）。
 * 对齐 TS 版 src/app/product-spec-submission.ts（48 行）：已有 request 直接返回不 retry、
 * 无则 retry 后命中、retry 后仍无 throw 逐字文案；missingSubmissionPrompt 五段文案逐字
 * （含 deliveryMode 字面量插值）。Retry 用 lambda 记录调用，不跑真 CLI。
 */
class ProductSpecSubmissionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法 local 请求的 tool_call input（过 ProductSpec.parseRequest 校验）。 */
    private static JsonNode localRequestInput() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("deliveryMode", "local");
        input.put("title", "标题");
        input.put("summary", "概述");
        input.put("specPath", "spec/product-spec.md");
        input.put("ticketsPath", "spec/tickets");
        return input;
    }

    private static ProductSpecRequest.Local expectedLocalRequest() {
        return new ProductSpecRequest.Local("local", "标题", "概述",
                "spec/product-spec.md", "spec/tickets");
    }

    // ---------- 已有 request：直接返回，不 retry ----------

    @Test
    void 已有request时直接返回且不retry() throws Exception {
        CliRunResult result = new CliRunResult("回答", "sess-1", null,
                List.of(new CliRunResult.ToolCall("t1", "request_spec_approval", localRequestInput())));
        AtomicInteger retryCalls = new AtomicInteger();
        ProductSpecSubmission submission = ProductSpecSubmission.ensureProductSpecSubmission(
                result, ProductDeliveryMode.LARK_DOC,
                (prompt, sessionId) -> {
                    retryCalls.incrementAndGet();
                    throw new AssertionError("已有 request 不应 retry");
                });
        assertEquals(0, retryCalls.get());
        assertSame(result, submission.result());
        assertEquals(expectedLocalRequest(), submission.request());
    }

    // ---------- 无 request：retry 后命中 ----------

    @Test
    void 无request时retry后命中且retry参数逐字转发() throws Exception {
        CliRunResult first = new CliRunResult("回答", "sess-1", null, null);
        CliRunResult retried = new CliRunResult("回答2", "sess-1", null,
                List.of(new CliRunResult.ToolCall("t2", "request_spec_approval", localRequestInput())));
        List<String> seenPrompts = new java.util.ArrayList<>();
        List<String> seenSessionIds = new java.util.ArrayList<>();
        ProductSpecSubmission submission = ProductSpecSubmission.ensureProductSpecSubmission(
                first, ProductDeliveryMode.LOCAL,
                (prompt, sessionId) -> {
                    seenPrompts.add(prompt);
                    seenSessionIds.add(sessionId);
                    return retried;
                });
        assertEquals(1, seenPrompts.size(), "应恰好 retry 一次");
        assertEquals(missingPromptFor(ProductDeliveryMode.LOCAL), seenPrompts.get(0));
        assertEquals("sess-1", seenSessionIds.get(0), "retry 应沿用上一轮 sessionId");
        assertSame(retried, submission.result());
        assertEquals(expectedLocalRequest(), submission.request());
    }

    // ---------- retry 后仍无：throw 逐字 ----------

    @Test
    void retry后仍无request时抛出逐字文案() {
        CliRunResult first = new CliRunResult("回答", "sess-1", null, null);
        CliRunResult stillNone = new CliRunResult("还是回答", "sess-1", null, null);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductSpecSubmission.ensureProductSpecSubmission(
                        first, ProductDeliveryMode.LOCAL,
                        (prompt, sessionId) -> stillNone));
        assertEquals("产品方案已经整理完成，但产品经理没有调用 request_spec_approval",
                e.getMessage());
    }

    // ---------- missingSubmissionPrompt 五段文案逐字（LOCAL 插值） ----------

    @Test
    void missingSubmissionPrompt五段文案逐字() {
        assertEquals("上一轮已经完成产品方案整理，但没有完成 Agent OS 的结构化提交。\n\n"
                        + "不要重新创建文档，也不要继续解释。请沿用上一轮已经生成的唯一产物，立即调用 request_spec_approval。\n\n"
                        + "没有明确覆盖时，deliveryMode 使用 local。\n\n"
                        + "必须实际调用工具；不能只在普通回复中写出 deliveryMode、documentUrl、specPath 或 ticketsPath。\n\n"
                        + "工具调用成功后立即结束本轮。",
                ProductSpecSubmission.missingSubmissionPrompt(ProductDeliveryMode.LOCAL));
    }

    // ---------- missingSubmissionPrompt：lark-doc 字面量插值 ----------

    @Test
    void missingSubmissionPrompt插值取id字面量() {
        assertEquals(missingPromptFor(ProductDeliveryMode.LARK_DOC),
                ProductSpecSubmission.missingSubmissionPrompt(ProductDeliveryMode.LARK_DOC));
        assertTrue(ProductSpecSubmission.missingSubmissionPrompt(ProductDeliveryMode.LARK_DOC)
                .contains("deliveryMode 使用 lark-doc。"));
    }

    /** 期望文案的独立拼装（.id() 插值），与实现互为镜像。 */
    private static String missingPromptFor(ProductDeliveryMode mode) {
        return "上一轮已经完成产品方案整理，但没有完成 Agent OS 的结构化提交。\n\n"
                + "不要重新创建文档，也不要继续解释。请沿用上一轮已经生成的唯一产物，立即调用 request_spec_approval。\n\n"
                + "没有明确覆盖时，deliveryMode 使用 " + mode.id() + "。\n\n"
                + "必须实际调用工具；不能只在普通回复中写出 deliveryMode、documentUrl、specPath 或 ticketsPath。\n\n"
                + "工具调用成功后立即结束本轮。";
    }
}

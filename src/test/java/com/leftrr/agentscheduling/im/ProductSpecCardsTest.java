package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.core.ProductSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts 的产品方案三卡（M4 Task 7 brief Step 1，
 * 用例编号 7-15 与 brief 一一对应）。
 *
 * <p>黄金 JSON 断言以序列化字符串全等比较（Jackson ObjectNode 保持插入序）。
 * flow 夹具直接 new 公开重建构造器（collaboration 等传 null）。</p>
 */
class ProductSpecCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 32 位 token。 */
    private static final String TOKEN = "abcdef1234567890abcdef1234567890";

    private static String jsonOf(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 夹具 ----

    private static ProductSpec.ProductSpecRequest.Lark larkRequest() {
        return new ProductSpec.ProductSpecRequest.Lark("lark-doc", "登录方案",
                "用手机号验证码登录", "https://example.feishu.cn/docx/abc123");
    }

    private static ProductSpec.ProductSpecRequest.Local localRequest() {
        return new ProductSpec.ProductSpecRequest.Local("local", "登录方案",
                "用手机号验证码登录", "docs/spec.md", "docs/tickets.md");
    }

    /** 公开重建构造器直接 new；collaboration / ownerUnionId 传 null。 */
    private static ProductSpec.ProductSpecFlow flow(ProductSpec.ProductSpecRequest request,
            String approvedAt) {
        return new ProductSpec.ProductSpecFlow(TOKEN, "task-1", "bot-1", "session-1",
                "ou_owner", null, null, request, ProductSpec.SpecStatus.PENDING, approvedAt);
    }

    private static JsonNode elements(ObjectNode card) {
        return card.path("body").path("elements");
    }

    private static String content(JsonNode element) {
        return element.path("content").asText();
    }

    // ---- buildProductSpecApprovalCard ----

    @Test
    void case07_goldenApprovalLarkDoc() {
        ObjectNode card = ProductSpecCards.buildProductSpecApprovalCard(flow(larkRequest(), null));
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"登录方案：待确认\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\",\"content\":\"产品文档已生成\"},"
                + "\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"飞书云文档待确认\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**登录方案**\\n\\n用手机号验证码登录\"},"
                + "{\"tag\":\"hr\"},"
                + "{\"tag\":\"markdown\",\"content\":\"**共享产物**\\n☁️ **飞书云文档** · [打开文档](https://example.feishu.cn/docx/abc123)\"},"
                + "{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"确认产品方案\"},"
                + "\"type\":\"primary_filled\",\"width\":\"fill\",\"size\":\"medium\",\"behaviors\":"
                + "[{\"type\":\"callback\",\"value\":{\"action\":\"approve_product_spec\","
                + "\"flowToken\":\"" + TOKEN + "\"}}]},"
                + "{\"tag\":\"markdown\",\"content\":\"_确认后，已确认方案会交回 CEO 助理继续安排后续成员。_\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case08_approvalLocalDocumentList() {
        ObjectNode card = ProductSpecCards.buildProductSpecApprovalCard(flow(localRequest(), null));
        // header subtitle 分支
        assertEquals("本地 Spec · Tickets 待确认", card.path("header").path("subtitle")
                .path("content").asText());
        // documentList 两行 "\n" join，path 经 escape（此处无 <，原样）
        assertEquals("**共享产物**\n📘 **Spec** · `docs/spec.md`\n🎫 **Tickets** · `docs/tickets.md`",
                content(elements(card).get(2)));
    }

    // ---- buildProductSpecApprovedCard ----

    @Test
    void case09_goldenApprovedWithApprovedAt() {
        ObjectNode card = ProductSpecCards.buildProductSpecApprovedCard(
                flow(localRequest(), "2026-10-13 12:00"));
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"登录方案：已确认\"}},"
                + "\"header\":{\"template\":\"green\",\"title\":{\"tag\":\"plain_text\",\"content\":\"产品方案已确认\"},"
                + "\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"产品阶段已就绪\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**登录方案**\\n\\n用手机号验证码登录\\n\\n"
                + "**已确认文档**\\n📘 **Spec** · `docs/spec.md`\\n🎫 **Tickets** · `docs/tickets.md`\\n\\n"
                + "确认时间：2026-10-13 12:00\\n\\n"
                + "_确认结果已交回团队负责人，等待下一步安排。_\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case10_approvedNullApprovedAt() {
        ObjectNode card = ProductSpecCards.buildProductSpecApprovedCard(flow(localRequest(), null));
        JsonNode only = elements(card).get(0);
        // 第四段剔除，join 无空段
        assertFalse(content(only).contains("确认时间"));
        assertFalse(content(only).contains("\n\n\n"));
        assertEquals("**登录方案**\n\n用手机号验证码登录\n\n**已确认文档**\n📘 **Spec** · `docs/spec.md`"
                        + "\n🎫 **Tickets** · `docs/tickets.md`\n\n_确认结果已交回团队负责人，等待下一步安排。_",
                content(only));
        assertEquals(1, elements(card).size());
    }

    // ---- buildProductSpecExpiredCard ----

    @Test
    void case11_expiredGoldenHeaderGreyNoSubtitle() {
        ObjectNode card = ProductSpecCards.buildProductSpecExpiredCard(flow(localRequest(), null));
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"登录方案：已失效\"}},"
                + "\"header\":{\"template\":\"grey\",\"title\":{\"tag\":\"plain_text\",\"content\":\"产品方案确认已失效\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**登录方案**\\n\\n"
                + "同一任务已经提交了更新的产品方案，请查看话题中最新的确认卡。\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
        // 键省略形态：header 无 subtitle 键（不是 put null）
        assertFalse(card.path("header").has("subtitle"));
    }

    @Test
    void case13_deliveryModeBranchByInstanceof() {
        // lark-doc → documentList 走云文档分支（对齐 TS === 'lark-doc'）
        ObjectNode approvedLark = ProductSpecCards.buildProductSpecApprovedCard(flow(larkRequest(), null));
        assertTrue(content(elements(approvedLark).get(0))
                .contains("**已确认文档**\n☁️ **飞书云文档** · [打开文档](https://example.feishu.cn/docx/abc123)"));
        // local → 两行分支
        ObjectNode approvedLocal = ProductSpecCards.buildProductSpecApprovedCard(flow(localRequest(), null));
        assertTrue(content(elements(approvedLocal).get(0))
                .contains("**已确认文档**\n📘 **Spec** · `docs/spec.md`\n🎫 **Tickets** · `docs/tickets.md`"));
        // 确认卡 subtitle 同样按交付模式分支
        assertEquals("飞书云文档待确认", ProductSpecCards.buildProductSpecApprovalCard(flow(larkRequest(), null))
                .path("header").path("subtitle").path("content").asText());
        assertEquals("本地 Spec · Tickets 待确认", ProductSpecCards.buildProductSpecApprovalCard(flow(localRequest(), null))
                .path("header").path("subtitle").path("content").asText());
    }

    @Test
    void case14_escapeFeishuMarkdownApplied() {
        ProductSpec.ProductSpecRequest.Local escapedLocal =
                new ProductSpec.ProductSpecRequest.Local("local", "<b>方案</b>",
                        "<i>摘要</i>", "<s>.md", "<t>.md");
        ProductSpec.ProductSpecRequest.Lark escapedLark =
                new ProductSpec.ProductSpecRequest.Lark("lark-doc", "<b>方案</b>",
                        "<i>摘要</i>", "https://example.feishu.cn/docx/a<b");
        // title / summary / paths escape
        ObjectNode approvalLocal = ProductSpecCards.buildProductSpecApprovalCard(flow(escapedLocal, null));
        assertEquals("**<&zwj;b>方案<&zwj;/b>**\n\n<&zwj;i>摘要<&zwj;/i>",
                content(elements(approvalLocal).get(0)));
        assertEquals("**共享产物**\n📘 **Spec** · `<&zwj;s>.md`\n🎫 **Tickets** · `<&zwj;t>.md`",
                content(elements(approvalLocal).get(2)));
        // config.summary 用原始 title（TS 模板直接插值）
        assertEquals("<b>方案</b>：待确认", approvalLocal.path("config").path("summary")
                .path("content").asText());
        // 已确认卡同样 escape
        ObjectNode approved = ProductSpecCards.buildProductSpecApprovedCard(flow(escapedLocal, null));
        assertTrue(content(elements(approved).get(0))
                .startsWith("**<&zwj;b>方案<&zwj;/b>**\n\n<&zwj;i>摘要<&zwj;/i>"));
        // 失效卡 title escape
        ObjectNode expired = ProductSpecCards.buildProductSpecExpiredCard(flow(escapedLocal, null));
        assertTrue(content(elements(expired).get(0)).startsWith("**<&zwj;b>方案<&zwj;/b>**"));
        // TS 分歧：documentUrl 在 productDocumentList 不经 escape（card.ts:824 原样插值）
        ObjectNode approvalLark = ProductSpecCards.buildProductSpecApprovalCard(flow(escapedLark, null));
        assertEquals("**共享产物**\n☁️ **飞书云文档** · [打开文档](https://example.feishu.cn/docx/a<b)",
                content(elements(approvalLark).get(2)));
    }

    @Test
    void case15_approvedAtVerbatimPassthrough() {
        // approvedAt 透传不做格式化（card.ts:918 直接插值；escape 仍应用）
        String raw = "2026-10-13T12:00:00.123456+08:00";
        ObjectNode card = ProductSpecCards.buildProductSpecApprovedCard(flow(larkRequest(), raw));
        assertTrue(content(elements(card).get(0)).contains("确认时间：" + raw));
        // 含 < 时只替换转义、不做任何格式化
        ObjectNode escaped = ProductSpecCards.buildProductSpecApprovedCard(
                flow(larkRequest(), "<ts>2026-10-13</ts>"));
        assertTrue(content(elements(escaped).get(0))
                .contains("确认时间：<&zwj;ts>2026-10-13<&zwj;/ts>"));
    }
}

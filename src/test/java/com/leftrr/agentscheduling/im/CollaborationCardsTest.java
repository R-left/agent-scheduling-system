package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts 的协作卡（M4 Task 7 brief Step 1，
 * 用例编号 1-7 与 brief 一一对应）。
 *
 * <p>黄金 JSON 断言以序列化字符串全等比较（Jackson ObjectNode 保持插入序）。</p>
 */
class CollaborationCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String jsonOf(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** collapsible_panel 的 header 黄金串（CardText.collapsibleHeader 键序）。 */
    private static final String COLLAPSIBLE_HEADER_GOLDEN =
            "{\"title\":{\"tag\":\"plain_text\",\"content\":\"查看任务说明\"},"
                    + "\"vertical_align\":\"center\","
                    + "\"icon\":{\"tag\":\"standard_icon\",\"token\":\"down-small-ccm_outlined\","
                    + "\"size\":\"16px 16px\"},"
                    + "\"icon_position\":\"right\",\"icon_expanded_angle\":-180}";

    // ---- 夹具 ----

    /** 主夹具：sender Alice → target Bob，结果交给 Carol，工作区 agent-os。 */
    private static CollaborationCards.CollaborationCardOptions options(
            String expectedOutput, int round, int maxRounds) {
        return new CollaborationCards.CollaborationCardOptions(
                "Alice", "Bob", "Carol", "agent-os", "重构登录模块",
                "先阅读模块 README，再动手。", expectedOutput, round, maxRounds);
    }

    private static JsonNode elements(ObjectNode card) {
        return card.path("body").path("elements");
    }

    private static String content(JsonNode element) {
        return element.path("content").asText();
    }

    // ---- buildCollaborationCard ----

    @Test
    void case01_goldenNonLastRound() {
        ObjectNode card = CollaborationCards.buildCollaborationCard(options(null, 1, 3));
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"协作任务已派发：重构登录模块\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\",\"content\":\"协作任务已派发\"},"
                + "\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"Alice → Bob\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**Bob，请接手：重构登录模块**\"},"
                + "{\"tag\":\"column_set\",\"flex_mode\":\"none\",\"horizontal_spacing\":\"16px\",\"columns\":["
                + "{\"tag\":\"column\",\"width\":\"weighted\",\"weight\":3,\"elements\":"
                + "[{\"tag\":\"markdown\",\"content\":\"**项目**\\nagent-os\"}]},"
                + "{\"tag\":\"column\",\"width\":\"weighted\",\"weight\":2,\"elements\":"
                + "[{\"tag\":\"markdown\",\"content\":\"**结果交给**\\nCarol\"}]}]},"
                + "{\"tag\":\"collapsible_panel\",\"expanded\":false,\"header\":" + COLLAPSIBLE_HEADER_GOLDEN + ","
                + "\"vertical_spacing\":\"8px\",\"padding\":\"8px 8px 8px 8px\",\"elements\":"
                + "[{\"tag\":\"markdown\",\"content\":\"先阅读模块 README，再动手。\"}]},"
                + "{\"tag\":\"hr\"},"
                + "{\"tag\":\"markdown\",\"content\":\"_完成后，结果会自动交回 Carol 继续组织后续工作。_\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case02_columnSetStructure() {
        ObjectNode card = CollaborationCards.buildCollaborationCard(options(null, 1, 3));
        JsonNode columnSet = elements(card).get(1);
        assertEquals("column_set", columnSet.path("tag").asText());
        assertEquals("none", columnSet.path("flex_mode").asText());
        assertEquals("16px", columnSet.path("horizontal_spacing").asText());
        JsonNode columns = columnSet.path("columns");
        assertEquals(2, columns.size());
        assertEquals("column", columns.get(0).path("tag").asText());
        assertEquals("weighted", columns.get(0).path("width").asText());
        assertEquals(3, columns.get(0).path("weight").asInt());
        assertEquals("**项目**\nagent-os", content(columns.get(0).path("elements").get(0)));
        assertEquals("weighted", columns.get(1).path("width").asText());
        assertEquals(2, columns.get(1).path("weight").asInt());
        assertEquals("**结果交给**\nCarol", content(columns.get(1).path("elements").get(0)));
    }

    @Test
    void case03_collapsiblePanelWithFenceAndTruncation() {
        // 含行首 ``` 的 instruction：preview 补闭合 fence，再 escape
        CollaborationCards.CollaborationCardOptions fenced = new CollaborationCards.CollaborationCardOptions(
                "Alice", "Bob", "Carol", "agent-os", "重构登录模块",
                "看这里：\n```java\ncode\n", null, 1, 3);
        JsonNode panel = elements(CollaborationCards.buildCollaborationCard(fenced)).get(2);
        assertEquals("collapsible_panel", panel.path("tag").asText());
        assertFalse(panel.path("expanded").asBoolean());
        assertEquals(COLLAPSIBLE_HEADER_GOLDEN, jsonOf(panel.path("header")));
        assertEquals("8px", panel.path("vertical_spacing").asText());
        assertEquals("8px 8px 8px 8px", panel.path("padding").asText());
        assertEquals("看这里：\n```java\ncode\n\n```", content(panel.path("elements").get(0)));

        // 超长 instruction：markdownPreview(instruction, 6000) 截到 6000 code unit
        CollaborationCards.CollaborationCardOptions longInstruction =
                new CollaborationCards.CollaborationCardOptions(
                        "Alice", "Bob", "Carol", "agent-os", "重构登录模块",
                        "a".repeat(6500), null, 1, 3);
        JsonNode longPanel = elements(CollaborationCards.buildCollaborationCard(longInstruction)).get(2);
        assertEquals(6000, content(longPanel.path("elements").get(0)).length());
    }

    @Test
    void case04_expectedOutputBranch() {
        // expectedOutput 非空 → collapsible 之后插入 hr + 期望产出两元素，尾部 hr + footer 恒在
        ObjectNode withOutput = CollaborationCards.buildCollaborationCard(options("接口文档一份", 1, 3));
        JsonNode withElements = elements(withOutput);
        assertEquals(7, withElements.size());
        assertEquals("hr", withElements.get(3).path("tag").asText());
        assertEquals("**期望产出**\n接口文档一份", content(withElements.get(4)));
        assertEquals("hr", withElements.get(5).path("tag").asText());
        assertEquals("_完成后，结果会自动交回 Carol 继续组织后续工作。_", content(withElements.get(6)));

        // expectedOutput null → 无这两元素，尾部 hr + footer 仍在
        ObjectNode withoutOutput = CollaborationCards.buildCollaborationCard(options(null, 1, 3));
        JsonNode withoutElements = elements(withoutOutput);
        assertEquals(5, withoutElements.size());
        assertEquals("collapsible_panel", withoutElements.get(2).path("tag").asText());
        assertEquals("hr", withoutElements.get(3).path("tag").asText());
        assertEquals("_完成后，结果会自动交回 Carol 继续组织后续工作。_", content(withoutElements.get(4)));
        assertFalse(jsonOf(withoutOutput).contains("期望产出"));
    }

    @Test
    void case05_footerTwoBranches() {
        // round < maxRounds → 非末轮文案
        ObjectNode notLast = CollaborationCards.buildCollaborationCard(options(null, 1, 3));
        JsonNode notLastElements = elements(notLast);
        assertEquals("_完成后，结果会自动交回 Carol 继续组织后续工作。_",
                content(notLastElements.get(notLastElements.size() - 1)));
        // round >= maxRounds → 末轮文案
        ObjectNode last = CollaborationCards.buildCollaborationCard(options(null, 3, 3));
        JsonNode lastElements = elements(last);
        assertEquals("_这是当前任务允许的最后一次交接；结果会通知 Carol，由他决定下一步。_",
                content(lastElements.get(lastElements.size() - 1)));
    }

    @Test
    void case06_roundBoundary() {
        // round == maxRounds - 1 → 非末轮文案
        ObjectNode beforeLast = CollaborationCards.buildCollaborationCard(options(null, 2, 3));
        JsonNode beforeLastElements = elements(beforeLast);
        assertEquals("_完成后，结果会自动交回 Carol 继续组织后续工作。_",
                content(beforeLastElements.get(beforeLastElements.size() - 1)));
        // round == maxRounds → 末轮文案
        ObjectNode atLast = CollaborationCards.buildCollaborationCard(options(null, 3, 3));
        JsonNode atLastElements = elements(atLast);
        assertEquals("_这是当前任务允许的最后一次交接；结果会通知 Carol，由他决定下一步。_",
                content(atLastElements.get(atLastElements.size() - 1)));
    }

    @Test
    void case07_escapeFeishuMarkdownApplied() {
        CollaborationCards.CollaborationCardOptions escaped =
                new CollaborationCards.CollaborationCardOptions(
                        "Alice", "Bob", "Carol", "<w>", "<b>任务</b>", "<p>说明</p>",
                        "<i>文档</i>", 1, 3);
        ObjectNode card = CollaborationCards.buildCollaborationCard(escaped);
        JsonNode all = elements(card);
        // 首元素 objective escape
        assertEquals("**Bob，请接手：<&zwj;b>任务<&zwj;/b>**", content(all.get(0)));
        // 列内 workspaceName / reportToName escape
        assertEquals("**项目**\n<&zwj;w>", content(all.get(1).path("columns").get(0)
                .path("elements").get(0)));
        assertEquals("**结果交给**\nCarol", content(all.get(1).path("columns").get(1)
                .path("elements").get(0)));
        // collapsible 内容 instruction escape
        assertEquals("<&zwj;p>说明<&zwj;/p>", content(all.get(2).path("elements").get(0)));
        // 期望产出 escape
        assertEquals("**期望产出**\n<&zwj;i>文档<&zwj;/i>", content(all.get(4)));
        // summary 与 subtitle 用原始值（TS 模板直接插值，不经 escape）
        assertEquals("协作任务已派发：<b>任务</b>", card.path("config").path("summary")
                .path("content").asText());
        assertEquals("Alice → Bob", card.path("header").path("subtitle").path("content").asText());
        assertTrue(jsonOf(card).contains("<b>任务</b>"));
    }
}

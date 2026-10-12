package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.core.Clarification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts 的澄清三卡（M4 Task 5 brief Step 1，
 * 用例编号 1-16 与 brief 一一对应）。
 *
 * <p>黄金 JSON 断言以序列化字符串全等比较（Jackson ObjectNode 保持插入序）。
 * flow 夹具经 {@link Clarification#flowsForTest} 构造（构造器包私有且带可变状态）。</p>
 */
class ClarificationCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 32 位 token；前 8 位 "abcdef12"。 */
    private static final String TOKEN = "abcdef1234567890abcdef1234567890";
    private static final String TOKEN8 = "abcdef12";

    private static String jsonOf(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 夹具 ----

    private static Clarification.Option option(String id, String label) {
        return new Clarification.Option(id, label);
    }

    private static Clarification.Question question(String id, String prompt,
            List<Clarification.Option> options, String recommendedOptionId) {
        return new Clarification.Question(id, prompt, options, recommendedOptionId);
    }

    private static Clarification.ClarificationRequest request(String title, String intro,
            List<Clarification.Question> questions) {
        return new Clarification.ClarificationRequest(title, intro, questions);
    }

    private static Clarification.ClarificationAnswer answer(String questionId, String prompt,
            String answer, Clarification.AnswerSource source) {
        return new Clarification.ClarificationAnswer(questionId, prompt, answer, source);
    }

    private static Clarification.ClarificationFlow flow(String token,
            Clarification.ClarificationRequest request, int currentIndex,
            List<Clarification.ClarificationAnswer> answers) {
        return Clarification.flowsForTest(token, request, currentIndex, answers);
    }

    /** 用例 1 主夹具：两题（当前第 1 题），无 intro、无答案。 */
    private static Clarification.ClarificationRequest defaultRequest() {
        return request("标题", "", List.of(
                question("q1", "部署方式？",
                        List.of(option("opt_a", "自建机房"), option("opt_b", "云服务")), "opt_b"),
                question("q2", "沟通方式？",
                        List.of(option("opt_c", "每日同步"), option("opt_d", "按需沟通")), null)));
    }

    // ---- 黄金串辅助（键序对齐 TS 对象字面量） ----

    /** 选项按钮黄金串：tag/text/type/width/size/behaviors。 */
    private static String optionBtn(String content, String optionId) {
        return "{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"" + content + "\"},"
                + "\"type\":\"default\",\"width\":\"fill\",\"size\":\"medium\",\"behaviors\":"
                + "[{\"type\":\"callback\",\"value\":{\"action\":\"answer_clarification\","
                + "\"flowToken\":\"" + TOKEN + "\",\"questionId\":\"q1\",\"optionId\":\"" + optionId + "\"}}]}";
    }

    /** 决策按钮黄金串。 */
    private static String decisionBtn(String content, String type, String mode) {
        return "{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"" + content + "\"},"
                + "\"type\":\"" + type + "\",\"width\":\"fill\",\"size\":\"medium\",\"behaviors\":"
                + "[{\"type\":\"callback\",\"value\":{\"action\":\"answer_clarification\","
                + "\"flowToken\":\"" + TOKEN + "\",\"questionId\":\"q1\",\"decisionMode\":\"" + mode + "\"}}]}";
    }

    /** form 黄金串（name = clarify_ + token 前 8 位）。 */
    private static String formGolden(String token, String questionId) {
        return "{\"tag\":\"form\",\"name\":\"clarify_" + token.substring(0, Math.min(8, token.length()))
                + "\",\"vertical_spacing\":\"8px\",\"elements\":["
                + "{\"tag\":\"input\",\"name\":\"custom_answer\",\"placeholder\":"
                + "{\"tag\":\"plain_text\",\"content\":\"都不合适？在这里写下你的答案\"},\"max_length\":500},"
                + "{\"tag\":\"button\",\"name\":\"submit_custom\",\"action_type\":\"form_submit\","
                + "\"text\":{\"tag\":\"plain_text\",\"content\":\"提交自定义答案\"},\"type\":\"primary\","
                + "\"width\":\"default\",\"size\":\"medium\",\"value\":{\"action\":\"answer_clarification\","
                + "\"flowToken\":\"" + token + "\",\"questionId\":\"" + questionId + "\",\"custom\":true}}]}";
    }

    private static JsonNode elements(ObjectNode card) {
        return card.path("body").path("elements");
    }

    private static String content(JsonNode element) {
        return element.path("content").asText();
    }

    /** 按钮文案（path text.content）。 */
    private static String buttonContent(JsonNode button) {
        return button.path("text").path("content").asText();
    }

    /** 按 behaviors[0].value.decisionMode 找决策按钮。 */
    private static JsonNode decisionButton(ObjectNode card, String mode) {
        for (JsonNode element : elements(card)) {
            JsonNode value = element.path("behaviors").path(0).path("value");
            if (value.has("decisionMode") && mode.equals(value.path("decisionMode").asText())) {
                return element;
            }
        }
        return null;
    }

    // ---- buildClarificationCard ----

    @Test
    void case01_goldenSingleQuestionNoIntroNoAnswers() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"标题（1/2）\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\",\"content\":\"标题\"},"
                + "\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"1 / 2\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**部署方式？**\\n\\n选择最符合预期的一项：\"},"
                + optionBtn("自建机房", "opt_a") + ","
                + optionBtn("云服务（推荐）", "opt_b") + ","
                + decisionBtn("这一题交给 Agent 决定", "default", "current") + ","
                + "{\"tag\":\"hr\"},"
                + formGolden(TOKEN, "q1") + ","
                + "{\"tag\":\"hr\"},"
                + "{\"tag\":\"markdown\",\"content\":\"不想逐项选择？Agent 会保留你已经确认的答案，并为剩余问题采用推荐方案。\"},"
                + decisionBtn("按推荐方案继续", "primary", "remaining")
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case02_introOnlyOnFirstQuestion() {
        Clarification.ClarificationRequest withIntro = request("标题", "请先阅读以下背景",
                List.of(
                        question("q1", "问题一", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null),
                        question("q2", "问题二", List.of(option("opt_c", "丙"), option("opt_d", "丁")), null)));
        // currentIndex == 0 → 首元素为 intro markdown
        ObjectNode first = ClarificationCards.buildClarificationCard(flow(TOKEN, withIntro, 0, List.of()));
        assertEquals("{\"tag\":\"markdown\",\"content\":\"请先阅读以下背景\"}",
                jsonOf(elements(first).get(0)));
        assertEquals("**问题一**\n\n选择最符合预期的一项：", content(elements(first).get(1)));
        // currentIndex == 1 → intro 不出现
        ObjectNode second = ClarificationCards.buildClarificationCard(flow(TOKEN, withIntro, 1, List.of()));
        assertFalse(jsonOf(second).contains("请先阅读以下背景"));
        assertEquals("**问题二**\n\n选择最符合预期的一项：", content(elements(second).get(0)));
    }

    @Test
    void case03_confirmedBlockTwoAnswers() {
        Clarification.ClarificationRequest threeQuestions = request("标题", "", List.of(
                question("q1", "问题一", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null),
                question("q2", "问题二", List.of(option("opt_c", "丙"), option("opt_d", "丁")), null),
                question("q3", "问题三", List.of(option("opt_e", "戊"), option("opt_f", "己")), null)));
        ObjectNode card = ClarificationCards.buildClarificationCard(flow(TOKEN, threeQuestions, 2,
                List.of(answer("q1", "问题一", "答案甲", Clarification.AnswerSource.AGENT),
                        answer("q2", "问题二", "答案乙", Clarification.AnswerSource.USER))));
        JsonNode all = elements(card);
        // 已确认块在前，随后是 hr
        assertEquals("**已确认 2 项**\n\n1. **问题一**\nAgent 推荐：答案甲\n\n2. **问题二**\n你的选择：答案乙",
                content(all.get(0)));
        assertEquals("hr", all.get(1).path("tag").asText());
        // 第三个元素才是当前题
        assertEquals("**问题三**\n\n选择最符合预期的一项：", content(all.get(2)));
    }

    @Test
    void case04_recommendedLabelSuffix() {
        Clarification.ClarificationRequest req = request("标题", "", List.of(
                question("q1", "问题一", List.of(
                        option("opt_a", "方案一"),
                        option("opt_b", "方案二（推荐）"),
                        option("opt_c", "方案三")), "opt_a")));
        ObjectNode card = ClarificationCards.buildClarificationCard(flow(TOKEN, req, 0, List.of()));
        JsonNode all = elements(card);
        // 推荐项且 label 未含「推荐」→ 追加（推荐）
        assertEquals("方案一（推荐）", buttonContent(all.get(1)));
        // label 已含「推荐」→ 原样（不重复追加）
        assertEquals("方案二（推荐）", buttonContent(all.get(2)));
        // 非推荐项 → 原样
        assertEquals("方案三", buttonContent(all.get(3)));
    }

    @Test
    void case05_recommendedFallbackToFirstOption() {
        Clarification.ClarificationRequest req = request("标题", "", List.of(
                question("q1", "问题一", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null)));
        ObjectNode card = ClarificationCards.buildClarificationCard(flow(TOKEN, req, 0, List.of()));
        JsonNode all = elements(card);
        assertEquals("甲（推荐）", buttonContent(all.get(1)));
        assertEquals("乙", buttonContent(all.get(2)));
    }

    @Test
    void case06_optionButtonValueShape() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        JsonNode button = elements(card).get(1);
        assertEquals("button", button.path("tag").asText());
        assertEquals("plain_text", button.path("text").path("tag").asText());
        assertEquals("default", button.path("type").asText());
        assertEquals("fill", button.path("width").asText());
        assertEquals("medium", button.path("size").asText());
        assertEquals("callback", button.path("behaviors").get(0).path("type").asText());
        JsonNode value = button.path("behaviors").get(0).path("value");
        assertEquals("answer_clarification", value.path("action").asText());
        assertEquals(TOKEN, value.path("flowToken").asText());
        assertEquals("q1", value.path("questionId").asText());
        assertEquals("opt_a", value.path("optionId").asText());
        assertFalse(value.has("decisionMode"));
        assertFalse(value.has("custom"));
    }

    @Test
    void case07_decisionCurrentButton() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        JsonNode button = decisionButton(card, "current");
        assertNotNull(button);
        assertEquals("这一题交给 Agent 决定", button.path("text").path("content").asText());
        assertEquals("default", button.path("type").asText());
        assertEquals("fill", button.path("width").asText());
        assertEquals("medium", button.path("size").asText());
        JsonNode value = button.path("behaviors").get(0).path("value");
        assertEquals("answer_clarification", value.path("action").asText());
        assertEquals(TOKEN, value.path("flowToken").asText());
        assertEquals("q1", value.path("questionId").asText());
        assertEquals("current", value.path("decisionMode").asText());
        assertFalse(value.has("optionId"));
    }

    @Test
    void case08_formStructure() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        JsonNode form = elements(card).get(5);
        assertEquals("form", form.path("tag").asText());
        assertEquals("clarify_" + TOKEN8, form.path("name").asText());
        assertEquals("8px", form.path("vertical_spacing").asText());

        JsonNode input = form.path("elements").get(0);
        assertEquals("input", input.path("tag").asText());
        assertEquals("custom_answer", input.path("name").asText());
        assertEquals("plain_text", input.path("placeholder").path("tag").asText());
        assertEquals("都不合适？在这里写下你的答案", input.path("placeholder").path("content").asText());
        assertEquals(500, input.path("max_length").asInt());

        JsonNode submit = form.path("elements").get(1);
        assertEquals("button", submit.path("tag").asText());
        assertEquals("submit_custom", submit.path("name").asText());
        assertEquals("form_submit", submit.path("action_type").asText());
        assertEquals("提交自定义答案", submit.path("text").path("content").asText());
        assertEquals("primary", submit.path("type").asText());
        JsonNode value = submit.path("value");
        assertEquals("answer_clarification", value.path("action").asText());
        assertEquals(TOKEN, value.path("flowToken").asText());
        assertEquals("q1", value.path("questionId").asText());
        assertTrue(value.path("custom").asBoolean());
    }

    @Test
    void case09_tailElements() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        JsonNode all = elements(card);
        assertEquals("hr", all.get(6).path("tag").asText());
        assertEquals("不想逐项选择？Agent 会保留你已经确认的答案，并为剩余问题采用推荐方案。",
                content(all.get(7)));
        JsonNode remaining = all.get(8);
        assertEquals("按推荐方案继续", remaining.path("text").path("content").asText());
        assertEquals("primary", remaining.path("type").asText());
        assertEquals("remaining", remaining.path("behaviors").get(0).path("value")
                .path("decisionMode").asText());
    }

    @Test
    void case10_optionOrderMatchesRequest() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        JsonNode all = elements(card);
        // 元素 1、2 为 q1 的两个按钮，顺序 = request.options 顺序
        assertEquals("opt_a", all.get(1).path("behaviors").get(0).path("value").path("optionId").asText());
        assertEquals("自建机房", buttonContent(all.get(1)));
        assertEquals("opt_b", all.get(2).path("behaviors").get(0).path("value").path("optionId").asText());
        assertEquals("云服务（推荐）", buttonContent(all.get(2)));
    }

    // ---- buildClarificationContinuingCard ----

    @Test
    void case11_continuingCardGolden() {
        Clarification.ClarificationRequest req = request("标题", "", List.of(
                question("q1", "问题一", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null),
                question("q2", "问题二", List.of(option("opt_c", "丙"), option("opt_d", "丁")), null)));
        ObjectNode card = ClarificationCards.buildClarificationContinuingCard(
                flow(TOKEN, req, 1, List.of(answer("q1", "问题一", "答案甲", Clarification.AnswerSource.USER))));
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"标题：正在整理\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"标题 · 正在整理\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**答案已收到**\\n\\n正在基于这些选择整理产品说明，无需重复点击。"
                + "\\n\\n**已确认 1 项**\\n\\n1. **问题一**\\n你的选择：答案甲\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    // ---- buildClarificationSupersededCard ----

    @Test
    void case12_supersededCardGoldenWithAndWithoutAnswers() {
        Clarification.ClarificationRequest req = request("标题", "", List.of(
                question("q1", "问题一", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null)));
        // 有答案 → 三段
        ObjectNode withAnswers = ClarificationCards.buildClarificationSupersededCard(
                flow(TOKEN, req, 0, List.of(answer("q1", "问题一", "答案甲", Clarification.AnswerSource.USER))));
        String goldenWith = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"标题：已收到新的补充\"}},"
                + "\"header\":{\"template\":\"grey\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"标题 · 已更新\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**已收到你在话题里的新消息**\\n\\n"
                + "这张卡片已经失效，Agent OS 正在沿用同一个任务上下文处理新的补充。\\n\\n"
                + "此前已确认 1 项，相关答案会一并带入。\"}"
                + "]}}";
        assertEquals(goldenWith, jsonOf(withAnswers));

        // 无答案 → 只有前两段，join 后无多余空段
        ObjectNode noAnswers = ClarificationCards.buildClarificationSupersededCard(
                flow(TOKEN, req, 0, List.of()));
        String goldenWithout = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"标题：已收到新的补充\"}},"
                + "\"header\":{\"template\":\"grey\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"标题 · 已更新\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**已收到你在话题里的新消息**\\n\\n"
                + "这张卡片已经失效，Agent OS 正在沿用同一个任务上下文处理新的补充。\"}"
                + "]}}";
        assertEquals(goldenWithout, jsonOf(noAnswers));
        assertEquals(1, elements(noAnswers).size());
        assertFalse(content(elements(noAnswers).get(0)).contains("\n\n\n"));
    }

    // ---- escape ----

    @Test
    void case13_escapeFeishuMarkdownApplied() {
        Clarification.ClarificationRequest req = request("标题", "<i>背景</i>", List.of(
                question("q1", "<b>问题</b>", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null)));
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, req, 0, List.of(answer("q1", "<b>问题</b>", "答案<x>", Clarification.AnswerSource.USER))));
        JsonNode all = elements(card);
        assertEquals("<&zwj;i>背景<&zwj;/i>", content(all.get(0)));
        assertEquals("**已确认 1 项**\n\n1. **<&zwj;b>问题<&zwj;/b>**\n你的选择：答案<&zwj;x>",
                content(all.get(1)));
        assertEquals("**<&zwj;b>问题<&zwj;/b>**\n\n选择最符合预期的一项：", content(all.get(3)));
    }

    // ---- 组合 ----

    @Test
    void case14_multiQuestionComposite() {
        Clarification.ClarificationRequest req = request("标题", "背景说明", List.of(
                question("q1", "问题一", List.of(option("opt_a", "甲"), option("opt_b", "乙")), null),
                question("q2", "问题二", List.of(option("opt_c", "丙"), option("opt_d", "丁")), null)));
        ObjectNode card = ClarificationCards.buildClarificationCard(flow(TOKEN, req, 1,
                List.of(answer("q1", "问题一", "推荐答案", Clarification.AnswerSource.AGENT))));
        JsonNode all = elements(card);
        // 已确认块在前
        assertEquals("**已确认 1 项**\n\n1. **问题一**\nAgent 推荐：推荐答案", content(all.get(0)));
        assertEquals("hr", all.get(1).path("tag").asText());
        // intro 不出现
        assertFalse(jsonOf(card).contains("背景说明"));
        // 当前题是 q2
        assertEquals("**问题二**\n\n选择最符合预期的一项：", content(all.get(2)));
        JsonNode current = decisionButton(card, "current");
        assertNotNull(current);
        assertEquals("q2", current.path("behaviors").get(0).path("value").path("questionId").asText());
    }

    @Test
    void case15_formNameTokenPrefix() {
        // 32 位 token → 截前 8 位
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        assertEquals("clarify_" + TOKEN8, elements(card).get(5).path("name").asText());
        // 恰 8 位 → 原样
        ObjectNode shortToken = ClarificationCards.buildClarificationCard(
                flow("abcd1234", defaultRequest(), 0, List.of()));
        assertEquals("clarify_abcd1234", elements(shortToken).get(5).path("name").asText());
    }

    @Test
    void case16_decisionButtonVerbatimNoRecommendedSuffix() {
        ObjectNode card = ClarificationCards.buildClarificationCard(
                flow(TOKEN, defaultRequest(), 0, List.of()));
        JsonNode current = decisionButton(card, "current");
        assertNotNull(current);
        assertEquals("这一题交给 Agent 决定", current.path("text").path("content").asText());
        JsonNode remaining = decisionButton(card, "remaining");
        assertNotNull(remaining);
        assertEquals("按推荐方案继续", remaining.path("text").path("content").asText());
        // （推荐）后缀只应用于推荐选项按钮，决策按钮不受影响
        assertFalse(current.path("text").path("content").asText().contains("（推荐）"));
        assertFalse(remaining.path("text").path("content").asText().contains("（推荐）"));
    }
}

package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliRunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7：Clarification 测试（对齐 TS 版 src/core/clarification.ts 语义）。
 * request JsonNode 用 Jackson readTree 构造。
 */
class ClarificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** 合法澄清请求模板：两题，q1 带推荐项。 */
    private static JsonNode validRequest() {
        return json("""
                {"title":"登录方式","intro":"请选择","questions":[
                  {"id":"q1","prompt":"选择登录方式？","options":[{"id":"pwd","label":"账号密码"},{"id":"sms","label":"短信验证码"}],"recommendedOptionId":"sms"},
                  {"id":"q2","prompt":"是否记住状态？","options":[{"id":"yes","label":"记住"},{"id":"no","label":"不记住"}]}
                ]}
                """);
    }

    private static String questionsJson(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"id\":\"q").append(i).append("\",\"prompt\":\"问题").append(i)
                    .append("\",\"options\":[{\"id\":\"a\",\"label\":\"选项一\"},{\"id\":\"b\",\"label\":\"选项二\"}]}");
        }
        return sb.toString();
    }

    private static JsonNode requestWithQuestions(int count) {
        return json("{\"title\":\"标题\",\"intro\":\"说明\",\"questions\":[" + questionsJson(count) + "]}");
    }

    /** 仅一道题、无推荐项的请求。 */
    private static JsonNode singleQuestionNoRecommendedRequest() {
        return json("""
                {"questions":[{"id":"q1","prompt":"选择登录方式？","options":[{"id":"pwd","label":"账号密码"},{"id":"sms","label":"短信验证码"}]}]}
                """);
    }

    private static Clarification.CreateFlowOptions flowOptions(Clarification.ClarificationRequest request) {
        return new Clarification.CreateFlowOptions("task-1", "bot-leader", "sess-1",
                "ou-owner", "union-owner", null, "om-1", "cm-1", true, request);
    }

    // ===== schema 解析 =====

    // 1. 无 title/intro → 默认 "需求澄清"/""；显式 title/intro → trim 后保留
    @Test
    void parseRequestDefaultsTitleAndIntro() {
        Clarification.ClarificationRequest bare = Clarification.parseRequest(
                json("{\"questions\":[" + questionsJson(1) + "]}"));
        assertEquals("需求澄清", bare.title());
        assertEquals("", bare.intro());

        JsonNode explicit = json("""
                {"title":"  标题  ","intro":"  说明文字 ","questions":["""
                + questionsJson(1) + "]}");
        Clarification.ClarificationRequest parsed = Clarification.parseRequest(explicit);
        assertEquals("标题", parsed.title());
        assertEquals("说明文字", parsed.intro());
    }

    // 2. title 81 字符 → null；title 全空白 → null；intro 301 字符 → null；intro 空串 → 通过
    @Test
    void parseRequestLengthLimits() {
        String long81 = "a".repeat(81);
        assertNull(Clarification.parseRequest(json(
                "{\"title\":\"" + long81 + "\",\"questions\":[" + questionsJson(1) + "]}")));
        assertNull(Clarification.parseRequest(json(
                "{\"title\":\"   \",\"questions\":[" + questionsJson(1) + "]}")));
        String long301 = "b".repeat(301);
        assertNull(Clarification.parseRequest(json(
                "{\"intro\":\"" + long301 + "\",\"questions\":[" + questionsJson(1) + "]}")));
        Clarification.ClarificationRequest emptyIntro = Clarification.parseRequest(json(
                "{\"intro\":\"\",\"questions\":[" + questionsJson(1) + "]}"));
        assertEquals("", emptyIntro.intro());
    }

    // 3. questions 0 个 / 6 个 → null；恰 1 个 / 5 个 → 通过
    @Test
    void parseRequestQuestionsCountBounds() {
        assertNull(Clarification.parseRequest(json("{\"questions\":[]}")));
        assertNull(Clarification.parseRequest(requestWithQuestions(6)));
        assertEquals(1, Clarification.parseRequest(requestWithQuestions(1)).questions().size());
        assertEquals(5, Clarification.parseRequest(requestWithQuestions(5)).questions().size());
    }

    // 4. option id 正则违规（大写 / 33 位）→ null；question id / recommendedOptionId 同理
    @Test
    void parseRequestIdRegex() {
        // option id 大写
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"PWD\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // option id 33 位
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"" + "a".repeat(33) + "\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // question id 大写
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"Q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // question id 33 位
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"" + "q".repeat(33) + "\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // recommendedOptionId 大写
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}],\"recommendedOptionId\":\"PWD\"}]}")));
        // recommendedOptionId 33 位
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}],\"recommendedOptionId\":\"" + "a".repeat(33) + "\"}]}")));
    }

    // 5. options 1 个 / 5 个 → null；2–4 个 → 通过；label 全空白 / 101 字符 → null；prompt 全空白 / 301 字符 → null
    @Test
    void parseRequestOptionsCountAndTextLimits() {
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"}]}]}")));
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":["
                        + "{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"},{\"id\":\"c\",\"label\":\"三\"},"
                        + "{\"id\":\"d\",\"label\":\"四\"},{\"id\":\"e\",\"label\":\"五\"}]}]}")));
        assertEquals(2, Clarification.parseRequest(requestWithQuestions(1)).questions().get(0).options().size());
        Clarification.ClarificationRequest fourOptions = Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":["
                        + "{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"},{\"id\":\"c\",\"label\":\"三\"},"
                        + "{\"id\":\"d\",\"label\":\"四\"}]}]}"));
        assertEquals(4, fourOptions.questions().get(0).options().size());
        // label 全空白
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"  \"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // label 101 字符
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"" + "标".repeat(101) + "\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // prompt 全空白
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"   \",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
        // prompt 301 字符
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"" + "问".repeat(301) + "\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}]}]}")));
    }

    // 6. 同一问题 option id 重复 → null（TS superRefine 消息仅注释备查）
    @Test
    void parseRequestRejectsDuplicateOptionIds() {
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"a\",\"label\":\"二\"}]}]}")));
    }

    // 7. recommendedOptionId 不在 options 中 → null；指向某 option → 通过；缺省 → null 字段
    @Test
    void parseRequestRecommendedOptionIdMembership() {
        assertNull(Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}],\"recommendedOptionId\":\"zz\"}]}")));
        Clarification.ClarificationRequest hit = Clarification.parseRequest(json(
                "{\"questions\":[{\"id\":\"q1\",\"prompt\":\"问题\",\"options\":[{\"id\":\"a\",\"label\":\"一\"},{\"id\":\"b\",\"label\":\"二\"}],\"recommendedOptionId\":\"b\"}]}"));
        assertEquals("b", hit.questions().get(0).recommendedOptionId());
        Clarification.ClarificationRequest absent = Clarification.parseRequest(requestWithQuestions(1));
        assertNull(absent.questions().get(0).recommendedOptionId());
    }

    // 8. 同一请求 question id 重复 → null
    @Test
    void parseRequestRejectsDuplicateQuestionIds() {
        assertNull(Clarification.parseRequest(json("""
                {"questions":[
                  {"id":"q1","prompt":"问题一","options":[{"id":"a","label":"一"},{"id":"b","label":"二"}]},
                  {"id":"q1","prompt":"问题二","options":[{"id":"a","label":"一"},{"id":"b","label":"二"}]}
                ]}
                """)));
    }

    // ===== 查找与归属 =====

    // 9. 两个合法调用 → 靠后者；后非法前合法 → 靠前者；全非法 / 无该工具名 / null 列表 → null
    @Test
    void findClarificationRequestScansBackwards() {
        JsonNode first = json("{\"title\":\"第一\",\"questions\":[" + questionsJson(1) + "]}");
        JsonNode second = json("{\"title\":\"第二\",\"questions\":[" + questionsJson(1) + "]}");
        assertEquals("第二", Clarification.findClarificationRequest(List.of(
                call("request_clarification", first), call("other_tool", json("{}")),
                call("request_clarification", second))).title());
        assertEquals("第一", Clarification.findClarificationRequest(List.of(
                call("request_clarification", first), call("request_clarification", json("{}")))).title());
        assertNull(Clarification.findClarificationRequest(List.of(
                call("request_clarification", json("{}")),
                call("request_clarification", json("{\"questions\":[]}")))));
        assertNull(Clarification.findClarificationRequest(List.of(
                call("dispatch_task", validRequest()))));
        assertNull(Clarification.findClarificationRequest(null));
    }

    // 10. isClarificationOwner 四分支：双方 union 均非空 → 比 union（相等 true / 不等 false）；任一 union 空 → 比 openId
    @Test
    void isClarificationOwnerFourBranches() {
        // 双方 union 均非空、相等 → true
        assertTrue(Clarification.isClarificationOwner("ou-a", "union-x", "ou-b", "union-x"));
        // 双方 union 均非空、不等 → false
        assertFalse(Clarification.isClarificationOwner("ou-a", "union-x", "ou-a", "union-y"));
        // owner union 空 → 比 openId（相等 true）
        assertTrue(Clarification.isClarificationOwner("ou-a", null, "ou-a", "union-y"));
        // operator union 空 → 比 openId（不等 false）
        assertFalse(Clarification.isClarificationOwner("ou-a", "union-x", "ou-b", null));
    }

    // ===== 流程状态机 =====

    // 11. create → token 32 位无连字符十六进制；同 (taskId, botId) 再 create → 旧 token get 为 null（全部删除）；不同 task/bot 不受影响
    @Test
    void createReplacesAllFlowsOfSameTaskAndBot() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        Clarification.ClarificationFlow first = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        assertTrue(first.token().matches("[0-9a-f]{32}"));

        Clarification.ClarificationFlow otherTask = store.create(new Clarification.CreateFlowOptions(
                "task-2", "bot-leader", "sess-2", "ou-owner", "union-owner",
                null, "om-2", null, false, Clarification.parseRequest(requestWithQuestions(1))));
        Clarification.ClarificationFlow otherBot = store.create(new Clarification.CreateFlowOptions(
                "task-1", "bot-writer", "sess-3", "ou-owner", "union-owner",
                null, "om-3", null, false, Clarification.parseRequest(requestWithQuestions(1))));

        // 同 (taskId, botId) 再 create（旧流程已作答完也不影响清理）
        store.answer(first.token(), "q1", "短信");
        store.answer(first.token(), "q2", "记住");
        Clarification.ClarificationFlow second = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        assertNull(store.get(first.token()));
        assertEquals(second.token(), store.get(second.token()).token());
        // 不同 task/bot 的旧流程不受影响
        assertEquals(otherTask.token(), store.get(otherTask.token()).token());
        assertEquals(otherBot.token(), store.get(otherBot.token()).token());
    }

    // 12. answer 正常作答：answers 追加、currentIndex +1；最后一题作答 → complete=true
    @Test
    void answerAppendsAndAdvances() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));

        Clarification.AnswerResult first = store.answer(flow.token(), "q1", "  用短信登录  ");
        assertFalse(first.complete());
        assertEquals(1, first.flow().answers().size());
        assertEquals(1, first.flow().currentIndex());
        Clarification.ClarificationAnswer answer = first.flow().answers().get(0);
        assertEquals("q1", answer.questionId());
        assertEquals("选择登录方式？", answer.prompt());
        assertEquals("用短信登录", answer.answer());
        assertEquals(Clarification.AnswerSource.USER, answer.source());

        Clarification.AnswerResult second = store.answer(flow.token(), "q2", "记住");
        assertTrue(second.complete());
        assertEquals(2, second.flow().currentIndex());
        assertEquals(2, second.flow().answers().size());
    }

    // 13. answer 拒绝路径：token 不存在 / questionId 不符 / 答案全空白 / currentIndex 越界 → null 且状态不变
    @Test
    void answerRejectionPathsLeaveStateUnchanged() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));

        assertNull(store.answer("no-such-token", "q1", "答案"));
        assertNull(store.answer(flow.token(), "q2", "答案"));
        assertNull(store.answer(flow.token(), "q1", "   "));
        assertEquals(0, store.get(flow.token()).answers().size());
        assertEquals(0, store.get(flow.token()).currentIndex());

        // 答完两题后 currentIndex 越界 → 再答返回 null 且状态不变
        store.answer(flow.token(), "q1", "短信");
        store.answer(flow.token(), "q2", "记住");
        assertNull(store.answer(flow.token(), "q2", "再答"));
        assertEquals(2, store.get(flow.token()).answers().size());
        assertEquals(2, store.get(flow.token()).currentIndex());
    }

    // 14. answerWithRecommendation(token, false)：只答当前一题，答案 = 推荐项 label（有）或首个 option（无），source=agent
    @Test
    void answerWithRecommendationSingleQuestion() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        // 有 recommendedOptionId → 取推荐项 label
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        Clarification.AnswerResult result = store.answerWithRecommendation(flow.token(), false);
        assertFalse(result.complete());
        assertEquals(1, result.flow().answers().size());
        assertEquals("短信验证码", result.flow().answers().get(0).answer());
        assertEquals("q1", result.flow().answers().get(0).questionId());
        assertEquals(Clarification.AnswerSource.AGENT, result.flow().answers().get(0).source());

        // 无 recommendedOptionId → 取首个 option label；单题 → complete=true
        Clarification.ClarificationFlow single = store.create(flowOptions(
                Clarification.parseRequest(singleQuestionNoRecommendedRequest())));
        Clarification.AnswerResult singleResult = store.answerWithRecommendation(single.token(), false);
        assertTrue(singleResult.complete());
        assertEquals(1, singleResult.flow().answers().size());
        assertEquals("账号密码", singleResult.flow().answers().get(0).answer());
    }

    // 15. answerWithRecommendation(token, true)：从当前题起连续答完 → complete=true 且 answers 数量补齐
    @Test
    void answerWithRecommendationAllRemainingCompletes() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        Clarification.AnswerResult result = store.answerWithRecommendation(flow.token(), true);
        assertTrue(result.complete());
        assertEquals(2, result.flow().answers().size());
        assertEquals("短信验证码", result.flow().answers().get(0).answer());
        assertEquals("记住", result.flow().answers().get(1).answer());
        assertEquals(2, result.flow().currentIndex());
    }

    // 16. answerWithRecommendation 未知 token → null；findForTask 命中 / 未命中；delete 后 get 为 null
    @Test
    void storeLookupAndDelete() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));

        assertNull(store.answerWithRecommendation("no-such-token", true));
        assertEquals(flow.token(), store.findForTask("task-1", "bot-leader").token());
        assertNull(store.findForTask("task-1", "bot-writer"));
        assertNull(store.findForTask("task-9", "bot-leader"));

        store.delete(flow.token());
        assertNull(store.get(flow.token()));
        assertNull(store.findForTask("task-1", "bot-leader"));
    }

    // ===== 格式化 =====

    // 17. formatClarificationAnswers：两题作答（一 user 一 agent）→ 逐字断言
    @Test
    void formatClarificationAnswersVerbatim() {
        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        store.answer(flow.token(), "q1", "短信");
        store.answerWithRecommendation(flow.token(), false);

        String expected = String.join("\n\n",
                "用户已经通过飞书卡片回答了上一轮需求澄清问题。",
                "1. 选择登录方式？\n用户回答：短信",
                "2. 是否记住状态？\nAgent 采用推荐方案：记住",
                "请基于这些答案继续工作。如果仍有会实质影响方案的未决问题，再次调用 request_clarification；否则直接整理清晰、可验收的产品结论。");
        assertEquals(expected, Clarification.formatClarificationAnswers(flow));
    }

    // 18. formatClarificationMessage：confirmed 段两种形态、当前题段有无、用户消息与首尾固定句逐字
    @Test
    void formatClarificationMessageVerbatim() {
        String header = "用户没有继续点击上一张需求澄清卡片，而是在同一个飞书话题里补充了新的信息。旧卡片已经失效，这条消息仍属于同一个任务。";
        String footer = "请优先理解这条新消息对既有需求的修正。如果仍有关键歧义，重新调用 request_clarification；信息已经足够时，直接整理可验收的需求结论。";

        Clarification.ClarificationFlowStore store = new Clarification.ClarificationFlowStore();
        // 无作答、有当前题
        Clarification.ClarificationFlow fresh = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        String expectedFresh = String.join("\n\n",
                header,
                "此前还没有确认任何选项。",
                "上一张卡片正在询问：选择登录方式？",
                "用户的新消息：我想改用微信登录",
                footer);
        assertEquals(expectedFresh, Clarification.formatClarificationMessage(fresh, "我想改用微信登录"));

        // 有作答历史（已答完 → currentIndex 越界 → 当前题段被剔除）
        Clarification.ClarificationFlow flow = store.create(flowOptions(Clarification.parseRequest(validRequest())));
        store.answer(flow.token(), "q1", "短信");
        store.answer(flow.token(), "q2", "记住");
        String expectedAnswered = String.join("\n\n",
                header,
                Clarification.formatClarificationAnswers(flow),
                "用户的新消息：我想改用微信登录",
                footer);
        assertEquals(expectedAnswered, Clarification.formatClarificationMessage(flow, "我想改用微信登录"));
        assertFalse(Clarification.formatClarificationMessage(flow, "我想改用微信登录").contains("上一张卡片正在询问"));
    }
}

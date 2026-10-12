package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliSessionSummary;
import com.leftrr.agentscheduling.im.SessionCards.ResumeCardOptions;
import com.leftrr.agentscheduling.im.SessionCards.SessionNoticeCardOptions;
import com.leftrr.agentscheduling.im.SessionCards.TeamCardMember;
import com.leftrr.agentscheduling.im.SessionCards.TeamCardOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts 的 resume 卡 / 会话通知卡 / 团队卡（M4 Task 6 brief Step 1，
 * 用例编号 1-15 与 brief 一一对应）。
 *
 * <p>黄金 JSON 断言以序列化字符串全等比较（Jackson ObjectNode 保持插入序）。
 * 涉及会话行的用例走时区注入重载 buildResumeCard(options, UTC)（T6②，不切全局默认时区），
 * 保证黄金串逐字可断言。</p>
 */
class SessionCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String jsonOf(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 夹具 ----

    private static final String AGENT_SESSION_ID = "sess-agent-0001";
    private static final String CLI_NAME = "Claude Code";

    /** 32 位 token；前 8 位 "abcdef12"。 */
    private static final String SESSION_A_ID = "abcdef1234567890abcdef1234567890";
    private static final String SESSION_B_ID = "1234567890abcdef1234567890abcdef";

    private static CliSessionSummary sessionA() {
        return new CliSessionSummary(SESSION_A_ID, "修复登录页", "2026-10-13T06:30:00.000Z");
    }

    private static CliSessionSummary sessionB() {
        return new CliSessionSummary(SESSION_B_ID, "重构配置加载", "2026-10-12T22:10:00.000Z");
    }

    private static ResumeCardOptions resumeOptions(String currentCliSessionId,
            List<CliSessionSummary> sessions) {
        return new ResumeCardOptions(AGENT_SESSION_ID, CLI_NAME, currentCliSessionId, sessions);
    }

    private static TeamCardMember member(String id, String displayName, String role,
            String cliName, List<String> skills, boolean isLeader, boolean ready) {
        return new TeamCardMember(id, displayName, role, cliName, skills, isLeader, ready);
    }

    /** 默认团队夹具：leader 在首位 + 一名已连接普通成员。 */
    private static TeamCardOptions defaultTeam() {
        return new TeamCardOptions(List.of(
                member("bot-leader", "领队", "团队负责人", "Claude Code",
                        List.of("spec", "review"), true, true),
                member("bot-dev", "开发君", "实现工程师", "Codex",
                        List.of(), false, true)));
    }

    private static JsonNode elements(ObjectNode card) {
        return card.path("body").path("elements");
    }

    private static String content(JsonNode element) {
        return element.path("content").asText();
    }

    // ---- formatSessionTime（card.ts:586-596）----

    @Test
    void case01_formatSessionTimeUtc() {
        assertEquals("10/13 06:30",
                SessionCards.formatSessionTime("2026-10-13T06:30:00.000Z", java.time.ZoneOffset.UTC));
    }

    @Test
    void case02_formatSessionTimeZoneIdApplied() {
        assertEquals("10/13 14:30",
                SessionCards.formatSessionTime("2026-10-13T06:30:00.000Z",
                        java.time.ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void case03_formatSessionTimeNoZeroPaddingMonthDay() {
        // M/d 单数不补零、HH:mm 2-digit 补零
        assertEquals("10/5 09:05",
                SessionCards.formatSessionTime("2026-10-05T09:05:00.000Z", java.time.ZoneOffset.UTC));
    }

    @Test
    void case04_formatSessionTimeInvalidReturnsOriginal() {
        assertEquals("not-a-date",
                SessionCards.formatSessionTime("not-a-date", java.time.ZoneOffset.UTC));
        // 分歧钉住：TS new Date("2026/10/13") 解析成功，Java Instant.parse 只认 ISO → 按非法原样返回
        assertEquals("2026/10/13",
                SessionCards.formatSessionTime("2026/10/13", java.time.ZoneOffset.UTC));
    }

    // ---- buildResumeCard（card.ts:598-666）----

    @Test
    void case05_resumeCardEmptySessions() {
        ObjectNode card = SessionCards.buildResumeCard(
                resumeOptions(null, List.of()), java.time.ZoneOffset.UTC);
        JsonNode all = elements(card);
        assertEquals(2, all.size());
        assertEquals("选择后，当前话题会继续使用对应的 CLI 上下文。", content(all.get(0)));
        assertEquals("当前工作目录里还没有可以恢复的 CLI 会话。先完成一次任务，再用 `/new` 开启新会话。",
                content(all.get(1)));
        assertFalse(jsonOf(card).contains("column_set"));
        assertFalse(jsonOf(card).contains("hr"));
    }

    @Test
    void case06_resumeCardGoldenTwoSessionsFirstCurrent() {
        ObjectNode card = SessionCards.buildResumeCard(
                resumeOptions(SESSION_A_ID, List.of(sessionA(), sessionB())),
                java.time.ZoneOffset.UTC);
        String currentSession = "{\"tag\":\"markdown\",\"content\":\"**当前会话**\"}";
        String rowA = "{\"tag\":\"column_set\",\"flex_mode\":\"none\",\"horizontal_spacing\":\"12px\","
                + "\"columns\":["
                + "{\"tag\":\"column\",\"width\":\"weighted\",\"weight\":4,\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**修复登录页**\\n_10/13 06:30 · abcdef12_\"}]},"
                + "{\"tag\":\"column\",\"width\":\"auto\",\"vertical_align\":\"center\","
                + "\"elements\":[" + currentSession + "]}]}";
        String rowB = "{\"tag\":\"column_set\",\"flex_mode\":\"none\",\"horizontal_spacing\":\"12px\","
                + "\"columns\":["
                + "{\"tag\":\"column\",\"width\":\"weighted\",\"weight\":4,\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**重构配置加载**\\n_10/12 22:10 · 12345678_\"}]},"
                + "{\"tag\":\"column\",\"width\":\"auto\",\"vertical_align\":\"center\",\"elements\":["
                + "{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"恢复\"},"
                + "\"type\":\"primary_filled\",\"size\":\"medium\",\"behaviors\":["
                + "{\"type\":\"callback\",\"value\":{\"action\":\"resume_cli_session\","
                + "\"agentSessionId\":\"" + AGENT_SESSION_ID + "\","
                + "\"cliSessionId\":\"" + SESSION_B_ID + "\"}}]}]}]}";
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"Claude Code：选择历史会话\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"恢复历史会话\"},"
                + "\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"Claude Code\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"选择后，当前话题会继续使用对应的 CLI 上下文。\"},"
                + rowA + ",{\"tag\":\"hr\"}," + rowB
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case07_resumeCardSessionRowContentEscaped() {
        CliSessionSummary session = new CliSessionSummary(
                SESSION_A_ID, "<b>修复登录</b>", "2026-10-13T06:30:00.000Z");
        ObjectNode card = SessionCards.buildResumeCard(
                resumeOptions(null, List.of(session)), java.time.ZoneOffset.UTC);
        JsonNode row = elements(card).get(1);
        JsonNode titleColumn = row.path("columns").get(0);
        assertEquals(4, titleColumn.path("weight").asInt());
        assertEquals("**<&zwj;b>修复登录<&zwj;/b>**\n_10/13 06:30 · abcdef12_",
                content(titleColumn.path("elements").get(0)));
    }

    @Test
    void case08_resumeCardCurrentMarkdownVsResumeButton() {
        // current 命中 → 第二列为「当前会话」markdown
        ObjectNode card = SessionCards.buildResumeCard(
                resumeOptions(SESSION_A_ID, List.of(sessionA(), sessionB())),
                java.time.ZoneOffset.UTC);
        JsonNode columnA = elements(card).get(1).path("columns").get(1);
        assertEquals("auto", columnA.path("width").asText());
        assertEquals("center", columnA.path("vertical_align").asText());
        assertEquals("{\"tag\":\"markdown\",\"content\":\"**当前会话**\"}",
                jsonOf((ObjectNode) columnA.path("elements").get(0)));

        // 非 current → 恢复按钮（primary_filled / medium / resume_cli_session value）
        JsonNode buttonB = elements(card).get(3).path("columns").get(1)
                .path("elements").get(0);
        assertEquals("button", buttonB.path("tag").asText());
        assertEquals("恢复", buttonB.path("text").path("content").asText());
        assertEquals("primary_filled", buttonB.path("type").asText());
        assertEquals("medium", buttonB.path("size").asText());
        JsonNode value = buttonB.path("behaviors").get(0).path("value");
        assertEquals("resume_cli_session", value.path("action").asText());
        assertEquals(AGENT_SESSION_ID, value.path("agentSessionId").asText());
        assertEquals(SESSION_B_ID, value.path("cliSessionId").asText());
    }

    @Test
    void case09_resumeCardHrOnlyBetweenRows() {
        ObjectNode card = SessionCards.buildResumeCard(
                resumeOptions(SESSION_A_ID, List.of(sessionA(), sessionB())),
                java.time.ZoneOffset.UTC);
        JsonNode all = elements(card);
        // 首行说明 + 2 行 + 行间 1 个 hr = 4 个元素；末行后无 hr
        assertEquals(4, all.size());
        assertEquals("column_set", all.get(1).path("tag").asText());
        assertEquals("hr", all.get(2).path("tag").asText());
        assertEquals("column_set", all.get(3).path("tag").asText());
    }

    @Test
    void case10_resumeCardNoCurrentMatchAllButtons() {
        // currentCliSessionId 为 null → 全部为「恢复」按钮
        ObjectNode noneCard = SessionCards.buildResumeCard(
                resumeOptions(null, List.of(sessionA(), sessionB())),
                java.time.ZoneOffset.UTC);
        assertEquals("button", elements(noneCard).get(1).path("columns").get(1)
                .path("elements").get(0).path("tag").asText());
        assertEquals("button", elements(noneCard).get(3).path("columns").get(1)
                .path("elements").get(0).path("tag").asText());

        // currentCliSessionId 无命中 → 同样全部为按钮
        ObjectNode missCard = SessionCards.buildResumeCard(
                resumeOptions("ffffffffffffffffffffffffffffffff",
                        List.of(sessionA(), sessionB())),
                java.time.ZoneOffset.UTC);
        assertEquals("button", elements(missCard).get(1).path("columns").get(1)
                .path("elements").get(0).path("tag").asText());
        assertEquals("button", elements(missCard).get(3).path("columns").get(1)
                .path("elements").get(0).path("tag").asText());
    }

    // ---- buildSessionNoticeCard（card.ts:668-684）----

    @Test
    void case11_sessionNoticeCardGoldenDefaultTemplate() {
        ObjectNode card = SessionCards.buildSessionNoticeCard(
                new SessionNoticeCardOptions("会话已续接", "当前话题已恢复使用原有 CLI 上下文。", null));
        // config 只有 summary（无 update_multi，Review Focus 2）；template 缺省 → blue
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"summary\":{\"content\":\"会话已续接\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"会话已续接\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"当前话题已恢复使用原有 CLI 上下文。\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case12_sessionNoticeCardTemplatePassedThrough() {
        ObjectNode green = SessionCards.buildSessionNoticeCard(
                new SessionNoticeCardOptions("标题", "说明", "green"));
        assertEquals("green", green.path("header").path("template").asText());
        ObjectNode grey = SessionCards.buildSessionNoticeCard(
                new SessionNoticeCardOptions("标题", "说明", "grey"));
        assertEquals("grey", grey.path("header").path("template").asText());
        assertFalse(jsonOf(green).contains("update_multi"));
    }

    // ---- buildTeamCard（card.ts:771-819）----

    @Test
    void case13_teamCardGolden() {
        ObjectNode card = SessionCards.buildTeamCard(defaultTeam());
        String golden = "{\"schema\":\"2.0\","
                + "\"config\":{\"summary\":{\"content\":\"Agent 团队：2 位成员\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"Agent 团队\"},"
                + "\"subtitle\":{\"tag\":\"plain_text\",\"content\":\"2 位成员 · 领队 负责统筹\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"每位成员使用自己的飞书身份、执行引擎和项目 Skill，"
                + "工作目录与会话彼此独立。\"},"
                + "{\"tag\":\"hr\"},"
                + "{\"tag\":\"markdown\",\"content\":\"**领队**  _Team Leader · 已连接_\\n"
                + "团队负责人\\n引擎：Claude Code　Skill：$spec、$review\"},"
                + "{\"tag\":\"markdown\",\"content\":\"**开发君**  _已连接_\\n"
                + "实现工程师\\n引擎：Codex　Skill：无\"}"
                + "]}}";
        assertEquals(golden, jsonOf(card));
    }

    @Test
    void case14_teamCardMemberRowsBadgesAndSkills() {
        // leader + ready → "Team Leader · 已连接"；非 leader 未连接 → "未连接"（filter(Boolean) 剔空 leader 位）
        ObjectNode leaderReady = SessionCards.buildTeamCard(new TeamCardOptions(List.of(
                member("m1", "甲", "角色甲", "Claude Code", List.of("a", "b"), true, true))));
        assertEquals("**甲**  _Team Leader · 已连接_\n角色甲\n引擎：Claude Code　Skill：$a、$b",
                content(elements(leaderReady).get(2)));

        ObjectNode leaderNotReady = SessionCards.buildTeamCard(new TeamCardOptions(List.of(
                member("m1", "甲", "角色甲", "Claude Code", List.of(), true, false))));
        assertEquals("**甲**  _Team Leader · 未连接_\n角色甲\n引擎：Claude Code　Skill：无",
                content(elements(leaderNotReady).get(2)));

        ObjectNode memberNotReady = SessionCards.buildTeamCard(new TeamCardOptions(List.of(
                member("m1", "乙", "角色乙", "Codex", List.of(), false, false))));
        assertEquals("**乙**  _未连接_\n角色乙\n引擎：Codex　Skill：无",
                content(elements(memberNotReady).get(2)));

        ObjectNode memberReady = SessionCards.buildTeamCard(new TeamCardOptions(List.of(
                member("m1", "乙", "角色乙", "Codex", List.of("a", "b"), false, true))));
        assertEquals("**乙**  _已连接_\n角色乙\n引擎：Codex　Skill：$a、$b",
                content(elements(memberReady).get(2)));

        // displayName / role / cliName / skills 串均过 escapeFeishuMarkdown
        ObjectNode escaped = SessionCards.buildTeamCard(new TeamCardOptions(List.of(
                member("m1", "<b>甲</b>", "<i>角色</i>", "Claude <x>", List.of("<s>"), true, true))));
        assertEquals("**<&zwj;b>甲<&zwj;/b>**  _Team Leader · 已连接_\n<&zwj;i>角色<&zwj;/i>"
                        + "\n引擎：Claude <&zwj;x>　Skill：$<&zwj;s>",
                content(elements(escaped).get(2)));
    }

    @Test
    void case15_teamCardNoLeaderSubtitle() {
        // isLeader 全 false → subtitle 恒 "{n} 位成员"（card.ts:800-804 分支）
        ObjectNode card = SessionCards.buildTeamCard(new TeamCardOptions(List.of(
                member("m1", "甲", "角色甲", "Claude Code", List.of(), false, true),
                member("m2", "乙", "角色乙", "Codex", List.of(), false, false))));
        assertEquals("2 位成员", card.path("header").path("subtitle").path("content").asText());
        assertEquals("Agent 团队：2 位成员",
                card.path("config").path("summary").path("content").asText());
        // summary / header 无 update_multi
        assertFalse(card.path("config").has("update_multi"));
        // 成员行仍逐个渲染，hr 只有一条
        JsonNode all = elements(card);
        assertEquals(4, all.size());
        assertEquals("hr", all.get(1).path("tag").asText());
        assertTrue(jsonOf(card).contains("**甲**"));
        assertTrue(jsonOf(card).contains("**乙**"));
    }
}

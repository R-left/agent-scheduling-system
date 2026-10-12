package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliRunStats;
import com.leftrr.agentscheduling.core.TaskActivity;
import com.leftrr.agentscheduling.core.TaskProgressSnapshot;
import com.leftrr.agentscheduling.im.TaskCards.TaskCardOptions;
import com.leftrr.agentscheduling.im.TaskCards.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts 的任务卡 buildTaskCard（M4 Task 4 brief Step 1，
 * 用例编号 1-20 与 brief 一一对应）。
 *
 * <p>黄金 JSON 断言以序列化字符串全等比较（Jackson ObjectNode 保持插入序），
 * 含折叠头的用例黄金串完整展开 collapsibleHeader 全部六键（控制器备忘）。</p>
 */
class TaskCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String jsonOf(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** collapsibleHeader 六键完整展开（键序对齐 CardText.collapsibleHeader 的 put 序）。 */
    private static String header(String label) {
        return "{\"title\":{\"tag\":\"plain_text\",\"content\":\"" + label + "\"},"
                + "\"vertical_align\":\"center\","
                + "\"icon\":{\"tag\":\"standard_icon\",\"token\":\"down-small-ccm_outlined\","
                + "\"size\":\"16px 16px\"},"
                + "\"icon_position\":\"right\",\"icon_expanded_angle\":-180}";
    }

    private static TaskCardOptions base(String title, TaskStatus status, String detail) {
        return new TaskCardOptions(title, status, detail, null, null, null, null, null);
    }

    private static TaskProgressSnapshot progress(String current, String toolName, String toolDetail,
            long elapsedMs, int toolCount, int completedCount, List<TaskActivity> activities,
            Long usedTokens, Long startTokens, Long windowTokens) {
        return new TaskProgressSnapshot(current, toolName, toolDetail, elapsedMs, toolCount,
                completedCount, activities, usedTokens, startTokens, windowTokens, null);
    }

    private static JsonNode elements(ObjectNode card) {
        return card.path("body").path("elements");
    }

    private static String content(JsonNode element) {
        return element.path("content").asText();
    }

    /** collapsible_panel 内层 markdown 的 content。 */
    private static String panelContent(JsonNode panel) {
        return content(panel.path("elements").get(0));
    }

    // ---- 结构与状态样式 ----

    @Test
    void case01_goldenMinimalRunning() {
        ObjectNode card = TaskCards.buildTaskCard(base("处理需求", TaskStatus.RUNNING, "正在执行"));
        assertEquals("{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"处理需求：执行中\"}},"
                + "\"header\":{\"template\":\"blue\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"处理需求 · 执行中\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\","
                + "\"elements\":[{\"tag\":\"markdown\",\"content\":\"**正在执行**\\n\\n刚刚开始\"}]}}",
                jsonOf(card));
    }

    @Test
    void case02_fourStatusStyles() {
        ObjectNode success = TaskCards.buildTaskCard(base("任务", TaskStatus.SUCCESS, "说明"));
        assertEquals("任务：已完成", success.path("config").path("summary").path("content").asText());
        assertEquals("green", success.path("header").path("template").asText());
        assertEquals("任务 · 已完成", success.path("header").path("title").path("content").asText());

        ObjectNode failed = TaskCards.buildTaskCard(base("任务", TaskStatus.FAILED, "说明"));
        assertEquals("任务：执行失败", failed.path("config").path("summary").path("content").asText());
        assertEquals("red", failed.path("header").path("template").asText());
        assertEquals("任务 · 执行失败", failed.path("header").path("title").path("content").asText());

        ObjectNode cancelled = TaskCards.buildTaskCard(base("任务", TaskStatus.CANCELLED, "说明"));
        assertEquals("任务：已取消", cancelled.path("config").path("summary").path("content").asText());
        assertEquals("grey", cancelled.path("header").path("template").asText());
        assertEquals("任务 · 已取消", cancelled.path("header").path("title").path("content").asText());
    }

    // ---- running 分支（buildRunningElements）----

    @Test
    void case03_runningNoProgressJustStarted() {
        ObjectNode card = TaskCards.buildTaskCard(base("处理需求", TaskStatus.RUNNING, "正在执行"));
        JsonNode all = elements(card);
        assertEquals(1, all.size());
        // title 部分为 detail（progress?.current ?? options.detail），meta 为「刚刚开始」
        assertEquals("**正在执行**\n\n刚刚开始", content(all.get(0)));
    }

    @Test
    void case04_runningProgressMetaShape() {
        TaskProgressSnapshot snapshot = progress("运行中", null, null, 125_000, 12, 0,
                List.of(), null, null, null);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("处理需求", TaskStatus.RUNNING, "正在执行", snapshot, null, null, null, null));
        assertEquals("**运行中**\n\n2 分 5 秒 · 12 次工具调用", content(elements(card).get(0)));
    }

    @Test
    void case05_runningCurrentToolIconAndDetail() {
        TaskProgressSnapshot snapshot = progress("执行命令", "Bash", "npm `test`", 5_000, 1, 0,
                List.of(), null, null, null);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("处理需求", TaskStatus.RUNNING, "正在执行", snapshot, null, null, null, null));
        // 前缀 icon「⌘ 」含尾空格；currentDetail 换行 + 反引号包裹（escapeInlineCode 后）
        assertEquals("**⌘ 执行命令**\n`npm ˋtestˋ`\n\n5 秒 · 1 次工具调用",
                content(elements(card).get(0)));
    }

    @Test
    void case06_runningContextAndGrowthItalicLines() {
        TaskProgressSnapshot snapshot = progress("运行中", null, null, 5_000, 1, 0, List.of(),
                12_000L, 10_000L, 200_000L);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("处理需求", TaskStatus.RUNNING, "正在执行", snapshot, null, null, null, null));
        assertEquals("**运行中**\n\n5 秒 · 1 次工具调用"
                        + "\n_当前上下文 12k / 200k（6%）_"
                        + "\n_本轮开始 10k · 新增 2k_",
                content(elements(card).get(0)));
    }

    @Test
    void case07_runningActivitiesHeaderWithCounts() {
        List<TaskActivity> activities = List.of(
                new TaskActivity("Glob", "步骤一", null, 0, false),
                new TaskActivity("Bash", "步骤二", null, 0, false),
                new TaskActivity("Read", "步骤三", null, 0, false));
        TaskProgressSnapshot snapshot = progress("运行中", null, null, 5_000, 3, 5,
                activities, null, null, null);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("处理需求", TaskStatus.RUNNING, "正在执行", snapshot, null, null, null, null));
        JsonNode all = elements(card);
        assertEquals(2, all.size());
        assertEquals("**最近完成（3 / 5）**\n📁 步骤一\n⌘ 步骤二\n📄 步骤三", content(all.get(1)));
    }

    @Test
    void case08_runningActivitiesTruncatedToLimit() {
        List<TaskActivity> activities = List.of(
                new TaskActivity("Glob", "步骤一", null, 0, false),
                new TaskActivity("Bash", "步骤二", null, 0, false),
                new TaskActivity("Read", "步骤三", null, 0, false),
                new TaskActivity("Grep", "步骤四", null, 0, false),
                new TaskActivity("Write", "步骤五", null, 0, false));
        TaskProgressSnapshot snapshot = progress("运行中", null, null, 5_000, 5, 9,
                activities, null, null, null);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("处理需求", TaskStatus.RUNNING, "正在执行", snapshot, null, null, null, null));
        String shown = content(elements(card).get(1));
        assertEquals("**最近完成（3 / 9）**\n📁 步骤一\n⌘ 步骤二\n📄 步骤三", shown);
        assertFalse(shown.contains("🔎"));
        assertFalse(shown.contains("📝"));
    }

    @Test
    void case09_runningAbortButtonPresence() {
        // abortSessionId 非空 → 停止按钮
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("处理需求", TaskStatus.RUNNING, "正在执行", null, null, null, null, "sess-1"));
        JsonNode all = elements(card);
        assertEquals(2, all.size());
        JsonNode button = all.get(1);
        assertEquals("button", button.path("tag").asText());
        assertEquals("plain_text", button.path("text").path("tag").asText());
        assertEquals("停止任务", button.path("text").path("content").asText());
        assertEquals("danger", button.path("type").asText());
        assertEquals("default", button.path("width").asText());
        assertEquals("medium", button.path("size").asText());
        assertEquals("callback", button.path("behaviors").get(0).path("type").asText());
        assertEquals("abort_task", button.path("behaviors").get(0).path("value").path("action").asText());
        assertEquals("sess-1", button.path("behaviors").get(0).path("value").path("sessionId").asText());

        // abortSessionId 为 null → 无按钮元素
        ObjectNode plain = TaskCards.buildTaskCard(base("处理需求", TaskStatus.RUNNING, "正在执行"));
        assertEquals(1, elements(plain).size());
    }

    // ---- finished success 分支（buildFinishedElements）----

    @Test
    void case10_successShortAnswerSingleElement() {
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.SUCCESS, "默认说明", null, "结果正文", null, null, null));
        JsonNode all = elements(card);
        assertEquals(1, all.size());
        assertEquals("结果正文", content(all.get(0)));

        // answer 空 → 用 detail
        ObjectNode emptyAnswer = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.SUCCESS, "默认说明", null, "", null, null, null));
        assertEquals(1, elements(emptyAnswer).size());
        assertEquals("默认说明", content(elements(emptyAnswer).get(0)));
        ObjectNode nullAnswer = TaskCards.buildTaskCard(base("任务", TaskStatus.SUCCESS, "默认说明"));
        assertEquals("默认说明", content(elements(nullAnswer).get(0)));
    }

    @Test
    void case11_successLongAnswerGoldenWithPanel() {
        String answer = "结论：" + "x".repeat(900);   // 903 code units > 900
        String preview = "结论：" + "x".repeat(897);   // compactAnswerPreview 截到 900 code units
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.SUCCESS, "说明", null, answer, null, null, null));
        JsonNode all = elements(card);
        assertEquals(2, all.size());
        // 折叠头六键完整展开的黄金全等断言
        assertEquals("{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"任务：已完成\"}},"
                + "\"header\":{\"template\":\"green\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"任务 · 已完成\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"" + preview + "\\n\\n_完整回答已收起_\"},"
                + "{\"tag\":\"collapsible_panel\",\"expanded\":false,\"header\":" + header("查看完整回答")
                + ",\"vertical_spacing\":\"8px\",\"padding\":\"8px 8px 8px 8px\","
                + "\"elements\":[{\"tag\":\"markdown\",\"content\":\"" + answer + "\"}]}"
                + "]}}",
                jsonOf(card));
    }

    @Test
    void case12_successVeryLongAnswerContinuationMark() {
        // answer > 6000 → 追加续发提示元素
        String longAnswer = "y".repeat(6001);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.SUCCESS, "说明", null, longAnswer, null, null, null));
        JsonNode all = elements(card);
        assertEquals(3, all.size());
        assertEquals("y".repeat(900) + "\n\n_完整回答已收起_", content(all.get(0)));
        assertEquals("y".repeat(6000), panelContent(all.get(1)));
        assertEquals("_回答较长，剩余内容已继续发送。_", content(all.get(2)));

        // answer 恰 6000 → 无续发提示
        String edgeAnswer = "y".repeat(6000);
        ObjectNode edgeCard = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.SUCCESS, "说明", null, edgeAnswer, null, null, null));
        assertEquals(2, elements(edgeCard).size());
        assertFalse(jsonOf(edgeCard).contains("回答较长"));
    }

    // ---- finished failed 分支 ----

    @Test
    void case13_failedBoldDetailNoTechnical() {
        ObjectNode card = TaskCards.buildTaskCard(base("任务", TaskStatus.FAILED, "构建失败"));
        JsonNode all = elements(card);
        assertEquals(1, all.size());
        assertEquals("**构建失败**", content(all.get(0)));
    }

    @Test
    void case14_failedTechnicalDetailGoldenPanel() {
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.FAILED, "构建失败", null, null, null, "Error: `x`", null));
        JsonNode all = elements(card);
        assertEquals(2, all.size());
        assertEquals("**构建失败**", content(all.get(0)));
        // 折叠头六键完整展开的黄金全等断言；内容为 escapeInlineCode 后的反引号包裹
        assertEquals("{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"任务：执行失败\"}},"
                + "\"header\":{\"template\":\"red\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"任务 · 执行失败\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"**构建失败**\"},"
                + "{\"tag\":\"collapsible_panel\",\"expanded\":false,\"header\":" + header("查看错误详情")
                + ",\"vertical_spacing\":\"8px\",\"padding\":\"8px 8px 8px 8px\","
                + "\"elements\":[{\"tag\":\"markdown\",\"content\":\"`Error: ˋxˋ`\"}]}"
                + "]}}",
                jsonOf(card));
    }

    // ---- 执行详情面板 ----

    @Test
    void case15_executionMetaDurationAndToolCalls() {
        // stats.durationMs 优先于 progress.elapsedMs；join 「 · 」
        TaskProgressSnapshot snapshot = progress(null, null, null, 999_000, 12, 0, List.of(),
                null, null, null);
        ObjectNode withProgress = TaskCards.buildTaskCard(new TaskCardOptions(
                "任务", TaskStatus.FAILED, "执行中断", snapshot, null,
                new CliRunStats(125_000, null, null, null, null, null, null, null, null), null, null));
        JsonNode all = elements(withProgress);
        assertEquals(2, all.size());
        assertEquals("**耗时** 2 分 5 秒 · **工具调用** 12 次", panelContent(all.get(1)));

        // progress null → 只含耗时段
        ObjectNode statsOnly = TaskCards.buildTaskCard(new TaskCardOptions(
                "任务", TaskStatus.FAILED, "执行中断", null, null,
                new CliRunStats(125_000, null, null, null, null, null, null, null, null), null, null));
        assertEquals("**耗时** 2 分 5 秒", panelContent(elements(statsOnly).get(1)));
    }

    @Test
    void case16_usageMetaTokensContextGrowth() {
        TaskProgressSnapshot snapshot = progress(null, null, null, 999_000, 12, 0, List.of(),
                12_000L, 10_000L, 200_000L);
        ObjectNode card = TaskCards.buildTaskCard(new TaskCardOptions(
                "任务", TaskStatus.FAILED, "执行中断", snapshot, null,
                new CliRunStats(125_000, null, 1_500L, null, null, null, null, null, null), null, null));
        // usageMeta 三段 join \n；与 executionMeta 之间 join \n\n；context 剥「当前上下文 」前缀
        assertEquals("**耗时** 2 分 5 秒 · **工具调用** 12 次"
                        + "\n\n**累计消耗** 1.5k tokens"
                        + "\n**当前上下文** 12k / 200k（6%）"
                        + "\n**本轮变化** 本轮开始 10k · 新增 2k",
                panelContent(elements(card).get(1)));
    }

    @Test
    void case17_executionPanelPresenceAndActivityLimit() {
        // meta 空 + activities 空 → 无「执行详情」面板
        ObjectNode bare = TaskCards.buildTaskCard(base("任务", TaskStatus.FAILED, "构建失败"));
        assertEquals(1, elements(bare).size());

        // 9 条 activities → FINISHED_ACTIVITY_LIMIT=8 截断，前缀 \n\n**最近执行轨迹**
        List<TaskActivity> activities = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            activities.add(new TaskActivity("Task", "a" + i, null, 0, false));
        }
        TaskProgressSnapshot snapshot = progress(null, null, null, 5_000, 9, 9,
                activities, null, null, null);
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.FAILED, "构建失败", snapshot, null, null, null, null));
        JsonNode all = elements(card);
        assertEquals(2, all.size());
        assertEquals("collapsible_panel", all.get(1).path("tag").asText());
        String panelText = panelContent(all.get(1));
        assertEquals("**耗时** 5 秒 · **工具调用** 9 次"
                        + "\n\n**最近执行轨迹**\n🧩 a1\n🧩 a2\n🧩 a3\n🧩 a4\n🧩 a5\n🧩 a6\n🧩 a7\n🧩 a8",
                panelText);
        assertFalse(panelText.contains("a9"));

        // 无活动时 activityText 空串：meta 兜底后不含轨迹段
        TaskProgressSnapshot idle = progress(null, null, null, 4_000, 3, 0, List.of(),
                null, null, null);
        ObjectNode noActivities = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.FAILED, "构建失败", idle, null, null, null, null));
        assertEquals("**耗时** 4 秒 · **工具调用** 3 次", panelContent(elements(noActivities).get(1)));
    }

    // ---- 组合 ----

    @Test
    void case18_goldenFullSuccessComposite() {
        // success + stats + progress + 长文 answer（>6000 → 正文 → 面板 → 续发提示 → 执行详情面板）
        String answer = "结论：" + "x".repeat(6100);
        String previewCompact = "结论：" + "x".repeat(897);   // markdownPreview(answer, 900)
        String previewPanel = "结论：" + "x".repeat(5997);    // markdownPreview(answer, 6000)
        TaskProgressSnapshot snapshot = progress(null, null, null, 0, 12, 2,
                List.of(new TaskActivity("Glob", "步骤一", null, 0, false),
                        new TaskActivity("Bash", "步骤二", null, 0, false)),
                null, null, null);
        ObjectNode card = TaskCards.buildTaskCard(new TaskCardOptions(
                "交付", TaskStatus.SUCCESS, "已完成", snapshot, answer,
                new CliRunStats(125_000, null, 1_500L, null, null, null, null, null, null), null, null));
        JsonNode all = elements(card);
        assertEquals(4, all.size());
        assertEquals("{\"schema\":\"2.0\","
                + "\"config\":{\"update_multi\":true,\"summary\":{\"content\":\"交付：已完成\"}},"
                + "\"header\":{\"template\":\"green\",\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"交付 · 已完成\"}},"
                + "\"body\":{\"direction\":\"vertical\",\"vertical_spacing\":\"12px\",\"elements\":["
                + "{\"tag\":\"markdown\",\"content\":\"" + previewCompact + "\\n\\n_完整回答已收起_\"},"
                + "{\"tag\":\"collapsible_panel\",\"expanded\":false,\"header\":" + header("查看完整回答")
                + ",\"vertical_spacing\":\"8px\",\"padding\":\"8px 8px 8px 8px\","
                + "\"elements\":[{\"tag\":\"markdown\",\"content\":\"" + previewPanel + "\"}]},"
                + "{\"tag\":\"markdown\",\"content\":\"_回答较长，剩余内容已继续发送。_\"},"
                + "{\"tag\":\"collapsible_panel\",\"expanded\":false,\"header\":" + header("执行详情")
                + ",\"vertical_spacing\":\"8px\",\"padding\":\"8px 8px 8px 8px\","
                + "\"elements\":[{\"tag\":\"markdown\",\"content\":"
                + "\"**耗时** 2 分 5 秒 · **工具调用** 12 次"
                + "\\n\\n**累计消耗** 1.5k tokens"
                + "\\n\\n**最近执行轨迹**\\n📁 步骤一\\n⌘ 步骤二\"}]}"
                + "]}}",
                jsonOf(card));
    }

    @Test
    void case19_usageTotalSumBranch() {
        // totalTokens null 但 input+output 有值 → 求和显示
        ObjectNode card = TaskCards.buildTaskCard(new TaskCardOptions(
                "任务", TaskStatus.FAILED, "失败说明", null, null,
                new CliRunStats(null, null, null, 1_000L, 500L, null, null, null, null), null, null));
        // executionMeta 空（无 durationMs 无 progress）→ meta 仅剩 usageMeta
        assertEquals("**累计消耗** 1.5k tokens", panelContent(elements(card).get(1)));
    }

    @Test
    void case20_escapeFeishuMarkdownAppliedToBody() {
        ObjectNode card = TaskCards.buildTaskCard(
                new TaskCardOptions("任务", TaskStatus.SUCCESS, "说明", null, "<b>加粗</b>", null, null, null));
        assertEquals("<&zwj;b>加粗<&zwj;/b>", content(elements(card).get(0)));
    }
}

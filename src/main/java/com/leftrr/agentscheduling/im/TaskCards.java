package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliRunStats;
import com.leftrr.agentscheduling.core.TaskActivity;
import com.leftrr.agentscheduling.core.TaskProgressSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对齐 TS 版 src/im/card.ts 的任务卡 buildTaskCard（M4 Task 4）。
 *
 * <p>卡片 JSON 用 ObjectNode 手工构造，put 顺序 = TS 对象字面量键序（设计裁决 1）。
 * 可空分量 null 表示 TS undefined；TS 真值检查（{@code ? :} / {@code ||}）落为
 * {@code != null && !isEmpty()}，TS nullish 检查（{@code ??}）落为 {@code != null}。
 * 长度阈值沿用 CardText 的 code unit 口径（R7）。</p>
 */
public final class TaskCards {

    /** card.ts:263 usageMeta 剥前缀 {@code /^当前上下文(?:约)?\s+/}。 */
    private static final Pattern CONTEXT_PREFIX = Pattern.compile("^当前上下文(?:约)?\\s+");

    /**
     * card.ts:78-90 TOOL_ICONS。差异注释：CardText 内的同名表为私有，Task 4 约束
     * 不改既有文件，故在此复制；两处须同步维护（仅 buildRunningElements 的
     * currentToolName 图标与本表相关，activityLine 走 CardText 自带副本）。
     */
    private static final Map<String, String> TOOL_ICONS = Map.ofEntries(
            Map.entry("Agent", "🧩"),
            Map.entry("Bash", "⌘"),
            Map.entry("Edit", "✏️"),
            Map.entry("Glob", "📁"),
            Map.entry("Grep", "🔎"),
            Map.entry("Read", "📄"),
            Map.entry("Task", "🧩"),
            Map.entry("TaskOutput", "⏳"),
            Map.entry("WebFetch", "🌐"),
            Map.entry("WebSearch", "🔍"),
            Map.entry("Write", "📝"));

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private TaskCards() {}

    /** card.ts:10 TaskStatus；id 对齐 TS 字符串字面量。 */
    public enum TaskStatus {
        RUNNING("running"),
        SUCCESS("success"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String id;

        TaskStatus(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** card.ts:12-21 TaskCardOptions；可空分量 null = TS undefined。 */
    public record TaskCardOptions(
            String title,
            TaskStatus status,
            String detail,
            TaskProgressSnapshot progress,
            String answer,
            CliRunStats stats,
            String technicalDetail,
            String abortSessionId) {}

    /** card.ts:66-71 STATUS_STYLE。 */
    private record StatusStyle(String template, String label) {}

    private static StatusStyle styleOf(TaskStatus status) {
        return switch (status) {
            case RUNNING -> new StatusStyle("blue", "执行中");
            case SUCCESS -> new StatusStyle("green", "已完成");
            case FAILED -> new StatusStyle("red", "执行失败");
            case CANCELLED -> new StatusStyle("grey", "已取消");
        };
    }

    // ---- card.ts:348-368 buildTaskCard ----

    public static ObjectNode buildTaskCard(TaskCardOptions options) {
        StatusStyle style = styleOf(options.status());
        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", options.title() + "：" + style.label());
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode headerTitle = FACTORY.objectNode();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", options.title() + " · " + style.label());
        ObjectNode header = FACTORY.objectNode();
        header.put("template", style.template());
        header.set("title", headerTitle);
        ArrayNode elements = FACTORY.arrayNode();
        for (ObjectNode element : options.status() == TaskStatus.RUNNING
                ? buildRunningElements(options)
                : buildFinishedElements(options)) {
            elements.add(element);
        }
        ObjectNode body = FACTORY.objectNode();
        body.put("direction", "vertical");
        body.put("vertical_spacing", "12px");
        body.set("elements", elements);
        ObjectNode card = FACTORY.objectNode();
        card.put("schema", "2.0");
        card.set("config", config);
        card.set("header", header);
        card.set("body", body);
        return card;
    }

    // ---- card.ts:191-239 buildRunningElements ----

    private static List<ObjectNode> buildRunningElements(TaskCardOptions options) {
        TaskProgressSnapshot progress = options.progress();
        String currentIcon = (progress != null && progress.currentToolName() != null
                && !progress.currentToolName().isEmpty())
                ? toolIcon(progress.currentToolName()) + " "
                : "";
        String currentDetail = (progress != null && progress.currentDetail() != null
                && !progress.currentDetail().isEmpty())
                ? "\n`" + CardText.escapeInlineCode(progress.currentDetail()) + "`"
                : "";
        String meta = progress != null
                ? CardText.formatDuration(progress.elapsedMs()) + " · " + progress.toolCount() + " 次工具调用"
                : "刚刚开始";
        String context = CardText.formatContextUsage(
                progress != null ? progress.contextUsedTokens() : null,
                progress != null ? progress.contextWindowTokens() : null);
        String contextGrowth = CardText.formatContextGrowth(
                progress != null ? progress.contextUsedTokens() : null,
                progress != null ? progress.contextStartTokens() : null,
                progress != null && Boolean.TRUE.equals(progress.startedNewSession()));
        String head = progress != null && progress.current() != null
                ? progress.current()
                : options.detail();
        StringBuilder line = new StringBuilder();
        line.append("**").append(currentIcon).append(head).append("**").append(currentDetail)
                .append("\n\n").append(meta);
        if (context != null) {
            line.append("\n_").append(context).append("_");
        }
        if (contextGrowth != null) {
            line.append("\n_").append(contextGrowth).append("_");
        }

        List<ObjectNode> elements = new ArrayList<>();
        elements.add(markdown(line.toString()));
        if (progress != null && progress.activities() != null && !progress.activities().isEmpty()) {
            List<TaskActivity> visible = progress.activities().subList(0,
                    Math.min(CardText.RUNNING_ACTIVITY_LIMIT, progress.activities().size()));
            StringBuilder activities = new StringBuilder();
            activities.append("**最近完成（").append(visible.size())
                    .append(" / ").append(progress.completedCount()).append("）**\n");
            for (int i = 0; i < visible.size(); i++) {
                if (i > 0) {
                    activities.append('\n');
                }
                activities.append(CardText.activityLine(visible.get(i)));
            }
            elements.add(markdown(activities.toString()));
        }
        if (options.abortSessionId() != null && !options.abortSessionId().isEmpty()) {
            elements.add(abortButton(options.abortSessionId()));
        }
        return elements;
    }

    // ---- card.ts:241-332 buildFinishedElements ----

    private static List<ObjectNode> buildFinishedElements(TaskCardOptions options) {
        TaskProgressSnapshot progress = options.progress();
        CliRunStats stats = options.stats();
        // card.ts:243 stats?.durationMs ?? progress?.elapsedMs（nullish：stats 存在但 durationMs 缺 → 取 progress）。
        // 差异注释：勿合并为嵌套三元——JLS 15.25 条件表达式在 long 与 Long 混臂时按数值提升
        // 整体视为 long，null 臂会被自动拆箱（null.longValue() NPE），必须 if/else 显式赋值。
        Long durationMs;
        if (stats != null && stats.durationMs() != null) {
            durationMs = stats.durationMs().longValue();
        } else {
            durationMs = progress != null ? progress.elapsedMs() : null;
        }
        Long totalTokens = CardText.usageTotal(stats);
        // card.ts:245-248：used 取 progress 优先、window 取 stats 优先（TS 源码原样，勿"修正"）
        String context = CardText.formatContextUsage(
                progress != null && progress.contextUsedTokens() != null
                        ? progress.contextUsedTokens()
                        : (stats != null ? stats.contextUsedTokens() : null),
                stats != null && stats.contextWindowTokens() != null
                        ? stats.contextWindowTokens()
                        : (progress != null ? progress.contextWindowTokens() : null));
        String contextGrowth = CardText.formatContextGrowth(
                progress != null && progress.contextUsedTokens() != null
                        ? progress.contextUsedTokens()
                        : (stats != null ? stats.contextUsedTokens() : null),
                progress != null ? progress.contextStartTokens() : null,
                progress != null && Boolean.TRUE.equals(progress.startedNewSession()));
        List<String> executionParts = new ArrayList<>();
        if (durationMs != null) {
            executionParts.add("**耗时** " + CardText.formatDuration(durationMs));
        }
        if (progress != null) {
            executionParts.add("**工具调用** " + progress.toolCount() + " 次");
        }
        String executionMeta = String.join(" · ", executionParts);
        List<String> usageParts = new ArrayList<>();
        if (totalTokens != null) {
            usageParts.add("**累计消耗** " + CardText.formatCount(totalTokens) + " tokens");
        }
        if (context != null) {
            usageParts.add("**当前上下文** " + CONTEXT_PREFIX.matcher(context).replaceFirst(""));
        }
        if (contextGrowth != null) {
            usageParts.add("**本轮变化** " + contextGrowth);
        }
        String usageMeta = String.join("\n", usageParts);
        List<String> metaParts = new ArrayList<>();
        if (!executionMeta.isEmpty()) {
            metaParts.add(executionMeta);
        }
        if (!usageMeta.isEmpty()) {
            metaParts.add(usageMeta);
        }
        String meta = String.join("\n\n", metaParts);
        List<ObjectNode> elements = new ArrayList<>();

        if (options.status() == TaskStatus.SUCCESS) {
            String answer = (options.answer() != null && !options.answer().isEmpty())
                    ? options.answer()
                    : options.detail();
            if (answer.length() <= CardText.COMPACT_ANSWER_LENGTH) {
                elements.add(markdown(CardText.escapeFeishuMarkdown(answer)));
            } else {
                elements.add(markdown(CardText.escapeFeishuMarkdown(CardText.compactAnswerPreview(answer))
                        + "\n\n_完整回答已收起_"));
                elements.add(collapsiblePanel("查看完整回答", CardText.escapeFeishuMarkdown(
                        CardText.markdownPreview(answer, CardText.MAX_CARD_ANSWER_LENGTH))));
            }
            if (CardText.answerNeedsContinuation(answer)) {
                elements.add(markdown("_回答较长，剩余内容已继续发送。_"));
            }
        } else {
            elements.add(markdown("**" + options.detail() + "**"));
            if (options.technicalDetail() != null && !options.technicalDetail().isEmpty()) {
                elements.add(collapsiblePanel("查看错误详情",
                        "`" + CardText.escapeInlineCode(options.technicalDetail()) + "`"));
            }
        }

        boolean hasActivities = progress != null && progress.activities() != null
                && !progress.activities().isEmpty();
        if (!meta.isEmpty() || hasActivities) {
            List<TaskActivity> visible = hasActivities
                    ? progress.activities().subList(0,
                            Math.min(CardText.FINISHED_ACTIVITY_LIMIT, progress.activities().size()))
                    : List.of();
            String activityText = "";
            if (!visible.isEmpty()) {
                StringBuilder activities = new StringBuilder("\n\n**最近执行轨迹**\n");
                for (int i = 0; i < visible.size(); i++) {
                    if (i > 0) {
                        activities.append('\n');
                    }
                    activities.append(CardText.activityLine(visible.get(i)));
                }
                activityText = activities.toString();
            }
            elements.add(collapsiblePanel("执行详情",
                    (!meta.isEmpty() ? meta : options.detail()) + activityText));
        }
        return elements;
    }

    // ---- 元素构造辅助 ----

    /** {tag:'markdown', content}（card.ts 键序）。 */
    private static ObjectNode markdown(String content) {
        ObjectNode node = FACTORY.objectNode();
        node.put("tag", "markdown");
        node.put("content", content);
        return node;
    }

    /** card.ts:222-237 停止按钮（键序 tag/text/type/width/size/behaviors）。 */
    private static ObjectNode abortButton(String sessionId) {
        ObjectNode text = FACTORY.objectNode();
        text.put("tag", "plain_text");
        text.put("content", "停止任务");
        ObjectNode value = FACTORY.objectNode();
        value.put("action", "abort_task");
        value.put("sessionId", sessionId);
        ObjectNode behavior = FACTORY.objectNode();
        behavior.put("type", "callback");
        behavior.set("value", value);
        ArrayNode behaviors = FACTORY.arrayNode();
        behaviors.add(behavior);
        ObjectNode button = FACTORY.objectNode();
        button.put("tag", "button");
        button.set("text", text);
        button.put("type", "danger");
        button.put("width", "default");
        button.put("size", "medium");
        button.set("behaviors", behaviors);
        return button;
    }

    /** card.ts:279-289 / 300-310 / 319-329 collapsible_panel（键序 tag/expanded/header/vertical_spacing/padding/elements）。 */
    private static ObjectNode collapsiblePanel(String headerText, String content) {
        ArrayNode inner = FACTORY.arrayNode();
        inner.add(markdown(content));
        ObjectNode panel = FACTORY.objectNode();
        panel.put("tag", "collapsible_panel");
        panel.put("expanded", false);
        panel.set("header", CardText.collapsibleHeader(headerText));
        panel.put("vertical_spacing", "8px");
        panel.put("padding", "8px 8px 8px 8px");
        panel.set("elements", inner);
        return panel;
    }

    private static String toolIcon(String toolName) {
        return TOOL_ICONS.getOrDefault(toolName, "⚙️");
    }
}

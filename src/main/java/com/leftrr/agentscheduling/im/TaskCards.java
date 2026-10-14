package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.dto.cli.CliRunStats;
import com.leftrr.agentscheduling.dto.core.TaskActivity;
import com.leftrr.agentscheduling.dto.core.TaskProgressSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 任务卡。卡片 JSON 手工构造，键序固定，别调。
 * 长度阈值统一走 CardText 的 code unit 口径。
 */
public final class TaskCards {

    /** 剥掉 formatContextUsage 产出的「当前上下文」前缀。 */
    private static final Pattern CONTEXT_PREFIX = Pattern.compile("^当前上下文(?:约)?\\s+");

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private TaskCards() {}

    /** id 是写进卡片的字符串字面量。 */
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

    public record TaskCardOptions(
            String title,
            TaskStatus status,
            String detail,
            TaskProgressSnapshot progress,
            String answer,
            CliRunStats stats,
            String technicalDetail,
            String abortSessionId) {}

    /** 状态 → 卡片模板色与标签。 */
    private record StatusStyle(String template, String label) {}

    private static StatusStyle styleOf(TaskStatus status) {
        return switch (status) {
            case RUNNING -> new StatusStyle("blue", "执行中");
            case SUCCESS -> new StatusStyle("green", "已完成");
            case FAILED -> new StatusStyle("red", "执行失败");
            case CANCELLED -> new StatusStyle("grey", "已取消");
        };
    }

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
        elements.addAll(options.status() == TaskStatus.RUNNING
                ? buildRunningElements(options)
                : buildFinishedElements(options));
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

    private static List<ObjectNode> buildFinishedElements(TaskCardOptions options) {
        TaskProgressSnapshot progress = options.progress();
        CliRunStats stats = options.stats();
        // durationMs 缺失时回退 elapsedMs；必须 if/else——嵌套三元在 long/Long 混臂时会自动拆箱，null 直接 NPE
        Long durationMs;
        if (stats != null && stats.durationMs() != null) {
            durationMs = stats.durationMs().longValue();
        } else {
            durationMs = progress != null ? progress.elapsedMs() : null;
        }
        Long totalTokens = CardText.usageTotal(stats);
        // used 看 progress、window 看 stats，来源不对称是有意的
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

    private static ObjectNode markdown(String content) {
        ObjectNode node = FACTORY.objectNode();
        node.put("tag", "markdown");
        node.put("content", content);
        return node;
    }

    /** 停止按钮；键序固定。 */
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

    /** 键序固定。 */
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
        return CardText.TOOL_ICONS.getOrDefault(toolName, "⚙️");
    }
}

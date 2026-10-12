package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 对齐 TS 版 src/im/card.ts 的协作卡（M4 Task 7）：buildCollaborationCard
 * （card.ts:686-769），及 card.ts:50-60 CollaborationCardOptions。
 *
 * <p>卡片 JSON 用 ObjectNode 手工构造，put 顺序 = TS 对象字面量键序（设计裁决 1）。
 * expectedOutput null = TS undefined；TS 真值检查（expectedOutput ?）落为
 * 非 null 且非空串。config.summary 与 header subtitle 的 objective / 成员名
 * 为 TS 模板直接插值，不经 escapeFeishuMarkdown（card.ts:699/706）。</p>
 */
public final class CollaborationCards {

    /** card.ts:50-60 CollaborationCardOptions；expectedOutput null = TS undefined。 */
    public record CollaborationCardOptions(
            String senderName,
            String targetName,
            String reportToName,
            String workspaceName,
            String objective,
            String instruction,
            String expectedOutput,
            int round,
            int maxRounds) {}

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private CollaborationCards() {}

    // ---- card.ts:686-769 buildCollaborationCard ----

    public static ObjectNode buildCollaborationCard(CollaborationCardOptions options) {
        boolean isLastRound = options.round() >= options.maxRounds();
        String title = "协作任务已派发";
        String footer = isLastRound
                ? "这是当前任务允许的最后一次交接；结果会通知 " + options.reportToName()
                        + "，由他决定下一步。"
                : "完成后，结果会自动交回 " + options.reportToName() + " 继续组织后续工作。";

        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", title + "：" + options.objective());
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode titleNode = FACTORY.objectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title);
        ObjectNode subtitle = FACTORY.objectNode();
        subtitle.put("tag", "plain_text");
        subtitle.put("content", options.senderName() + " → " + options.targetName());
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "blue");
        header.set("title", titleNode);
        header.set("subtitle", subtitle);

        ArrayNode elements = FACTORY.arrayNode();
        elements.add(markdown("**" + options.targetName() + "，请接手："
                + CardText.escapeFeishuMarkdown(options.objective()) + "**"));
        elements.add(columnSet(options));
        elements.add(collapsiblePanel(options.instruction()));
        // TS: ...(options.expectedOutput ? [hr, markdown] : [])（真值检查）
        if (options.expectedOutput() != null && !options.expectedOutput().isEmpty()) {
            elements.add(hr());
            elements.add(markdown("**期望产出**\n"
                    + CardText.escapeFeishuMarkdown(options.expectedOutput())));
        }
        elements.add(hr());
        elements.add(markdown("_" + footer + "_"));

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

    /** card.ts:717-741 column_set（项目 / 结果交给 两列，weight 3:2）。 */
    private static ObjectNode columnSet(CollaborationCardOptions options) {
        ObjectNode project = markdown("**项目**\n"
                + CardText.escapeFeishuMarkdown(options.workspaceName()));
        ObjectNode projectColumn = FACTORY.objectNode();
        projectColumn.put("tag", "column");
        projectColumn.put("width", "weighted");
        projectColumn.put("weight", 3);
        projectColumn.set("elements", singleElementArray(project));

        ObjectNode reportTo = markdown("**结果交给**\n"
                + CardText.escapeFeishuMarkdown(options.reportToName()));
        ObjectNode reportColumn = FACTORY.objectNode();
        reportColumn.put("tag", "column");
        reportColumn.put("width", "weighted");
        reportColumn.put("weight", 2);
        reportColumn.set("elements", singleElementArray(reportTo));

        ArrayNode columns = FACTORY.arrayNode();
        columns.add(projectColumn);
        columns.add(reportColumn);
        ObjectNode columnSet = FACTORY.objectNode();
        columnSet.put("tag", "column_set");
        columnSet.put("flex_mode", "none");
        columnSet.put("horizontal_spacing", "16px");
        columnSet.set("columns", columns);
        return columnSet;
    }

    /** card.ts:742-754 collapsible_panel（查看任务说明，内容 = escape(markdownPreview(instruction, 6000))）。 */
    private static ObjectNode collapsiblePanel(String instruction) {
        ObjectNode content = markdown(CardText.escapeFeishuMarkdown(
                CardText.markdownPreview(instruction, CardText.MAX_CARD_ANSWER_LENGTH)));
        ObjectNode panel = FACTORY.objectNode();
        panel.put("tag", "collapsible_panel");
        panel.put("expanded", false);
        panel.set("header", CardText.collapsibleHeader("查看任务说明"));
        panel.put("vertical_spacing", "8px");
        panel.put("padding", "8px 8px 8px 8px");
        panel.set("elements", singleElementArray(content));
        return panel;
    }

    // ---- 元素构造辅助 ----

    private static ArrayNode singleElementArray(ObjectNode element) {
        ArrayNode array = FACTORY.arrayNode();
        array.add(element);
        return array;
    }

    /** {tag:'markdown', content}（card.ts 键序）。 */
    private static ObjectNode markdown(String content) {
        ObjectNode node = FACTORY.objectNode();
        node.put("tag", "markdown");
        node.put("content", content);
        return node;
    }

    private static ObjectNode hr() {
        ObjectNode node = FACTORY.objectNode();
        node.put("tag", "hr");
        return node;
    }
}

package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.core.ProductSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品方案三卡（待确认 / 已确认 / 已失效）。卡片 JSON 手工构造，键序固定。
 * approvedAt 原样透传不做格式化；documentUrl 不过 escape。
 */
public final class ProductSpecCards {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private ProductSpecCards() {}

    private static String productDocumentList(ProductSpec.ProductSpecFlow flow) {
        ProductSpec.ProductSpecRequest request = flow.request();
        if (request instanceof ProductSpec.ProductSpecRequest.Lark lark) {
            return "☁️ **飞书云文档** · [打开文档](" + lark.documentUrl() + ")";
        }
        // sealed 接口只有 Local/Lark 两个实现，走到这必是 Local；
        // 将来加第三个实现这里强转会炸，记得回来改
        ProductSpec.ProductSpecRequest.Local local = (ProductSpec.ProductSpecRequest.Local) request;
        return "📘 **Spec** · `" + CardText.escapeFeishuMarkdown(local.specPath()) + "`\n"
                + "🎫 **Tickets** · `" + CardText.escapeFeishuMarkdown(local.ticketsPath()) + "`";
    }

    public static ObjectNode buildProductSpecApprovalCard(ProductSpec.ProductSpecFlow flow) {
        ProductSpec.ProductSpecRequest request = flow.request();

        ArrayNode elements = FACTORY.arrayNode();
        elements.add(markdown("**" + CardText.escapeFeishuMarkdown(request.title()) + "**\n\n"
                + CardText.escapeFeishuMarkdown(request.summary())));
        elements.add(hr());
        elements.add(markdown("**共享产物**\n" + productDocumentList(flow)));

        ObjectNode text = FACTORY.objectNode();
        text.put("tag", "plain_text");
        text.put("content", "确认产品方案");
        ObjectNode value = FACTORY.objectNode();
        value.put("action", "approve_product_spec");
        value.put("flowToken", flow.token());
        ObjectNode behavior = FACTORY.objectNode();
        behavior.put("type", "callback");
        behavior.set("value", value);
        ArrayNode behaviors = FACTORY.arrayNode();
        behaviors.add(behavior);
        ObjectNode button = FACTORY.objectNode();
        button.put("tag", "button");
        button.set("text", text);
        button.put("type", "primary_filled");
        button.put("width", "fill");
        button.put("size", "medium");
        button.set("behaviors", behaviors);
        elements.add(button);

        elements.add(markdown("_确认后，已确认方案会交回 CEO 助理继续安排后续成员。_"));

        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", request.title() + "：待确认");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode titleNode = FACTORY.objectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "产品文档已生成");
        ObjectNode subtitle = FACTORY.objectNode();
        subtitle.put("tag", "plain_text");
        subtitle.put("content", request instanceof ProductSpec.ProductSpecRequest.Lark
                ? "飞书云文档待确认"
                : "本地 Spec · Tickets 待确认");
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "blue");
        header.set("title", titleNode);
        header.set("subtitle", subtitle);

        return card(config, header, elements);
    }

    public static ObjectNode buildProductSpecApprovedCard(ProductSpec.ProductSpecFlow flow) {
        List<String> parts = new ArrayList<>();
        parts.add("**" + CardText.escapeFeishuMarkdown(flow.request().title()) + "**");
        String summary = CardText.escapeFeishuMarkdown(flow.request().summary());
        if (!summary.isEmpty()) {
            parts.add(summary);
        }
        parts.add("**已确认文档**\n" + productDocumentList(flow));
        String approvedAt = flow.approvedAt();
        if (approvedAt != null && !approvedAt.isEmpty()) {
            parts.add("确认时间：" + CardText.escapeFeishuMarkdown(approvedAt));
        }
        parts.add("_确认结果已交回团队负责人，等待下一步安排。_");

        ObjectNode summaryNode = FACTORY.objectNode();
        summaryNode.put("content", flow.request().title() + "：已确认");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summaryNode);
        ObjectNode titleNode = FACTORY.objectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "产品方案已确认");
        ObjectNode subtitle = FACTORY.objectNode();
        subtitle.put("tag", "plain_text");
        subtitle.put("content", "产品阶段已就绪");
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "green");
        header.set("title", titleNode);
        header.set("subtitle", subtitle);

        return card(config, header, singleElementArray(markdown(String.join("\n\n", parts))));
    }

    public static ObjectNode buildProductSpecExpiredCard(ProductSpec.ProductSpecFlow flow) {
        String title = flow.request().title();
        ObjectNode summaryNode = FACTORY.objectNode();
        summaryNode.put("content", title + "：已失效");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summaryNode);
        // 失效卡没有 subtitle——键整个省略，别 put null（会输出 "subtitle": null）
        ObjectNode titleNode = FACTORY.objectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "产品方案确认已失效");
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "grey");
        header.set("title", titleNode);

        String content = String.join("\n\n",
                "**" + CardText.escapeFeishuMarkdown(title) + "**",
                "同一任务已经提交了更新的产品方案，请查看话题中最新的确认卡。");
        return card(config, header, singleElementArray(markdown(content)));
    }

    // ---- 元素构造辅助 ----

    private static ArrayNode singleElementArray(ObjectNode element) {
        ArrayNode array = FACTORY.arrayNode();
        array.add(element);
        return array;
    }

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

    /** 键序固定。 */
    private static ObjectNode card(ObjectNode config, ObjectNode header, ArrayNode elements) {
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
}

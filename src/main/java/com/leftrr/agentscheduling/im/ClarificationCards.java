package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.core.Clarification;

import java.util.ArrayList;
import java.util.List;

/**
 * 对齐 TS 版 src/im/card.ts 的澄清三卡（M4 Task 5）：buildClarificationCard（card.ts:435-520）、
 * buildClarificationContinuingCard（card.ts:522-551）、buildClarificationSupersededCard
 * （card.ts:553-584），及私有辅助 clarificationButton（370-398）、clarificationAnswerSummary
 * （400-405）、clarificationDecisionButton（407-433）。
 *
 * <p>卡片 JSON 用 ObjectNode 手工构造，put 顺序 = TS 对象字面量键序（设计裁决 1）。
 * decisionMode 采用私有 enum（brief Step 2 裁决采纳推荐），写 value 时用 id 字符串。
 * {@code question.recommendedOptionId ?? question.options[0]?.id} 的 nullish +
 * 可选链落为显式判空。TS 真值检查（intro/answers）落为非 null 且非空串。</p>
 */
public final class ClarificationCards {

    /** card.ts:409 decisionMode 参数；id 对齐 TS 字符串字面量。 */
    private enum DecisionMode {
        CURRENT("current"),
        REMAINING("remaining");

        private final String id;

        DecisionMode(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private ClarificationCards() {}

    // ---- card.ts:370-398 clarificationButton ----

    private static ObjectNode clarificationButton(Clarification.ClarificationFlow flow,
            Clarification.Option option) {
        Clarification.Question question = currentQuestion(flow);
        // TS: question.recommendedOptionId ?? question.options[0]?.id（nullish + 可选链 → 显式判空）
        String recommendedOptionId = question.recommendedOptionId() != null
                ? question.recommendedOptionId()
                : (!question.options().isEmpty() ? question.options().get(0).id() : null);
        String content = option.id().equals(recommendedOptionId)
                ? (option.label().contains("推荐") ? option.label() : option.label() + "（推荐）")
                : option.label();
        ObjectNode text = FACTORY.objectNode();
        text.put("tag", "plain_text");
        text.put("content", content);
        ObjectNode value = FACTORY.objectNode();
        value.put("action", "answer_clarification");
        value.put("flowToken", flow.token());
        value.put("questionId", question.id());
        value.put("optionId", option.id());
        return fillButton(text, "default", value);
    }

    // ---- card.ts:400-405 clarificationAnswerSummary ----

    private static String clarificationAnswerSummary(Clarification.ClarificationFlow flow) {
        List<String> sections = new ArrayList<>();
        List<Clarification.ClarificationAnswer> answers = flow.answers();
        for (int index = 0; index < answers.size(); index++) {
            Clarification.ClarificationAnswer answer = answers.get(index);
            sections.add((index + 1) + ". **" + CardText.escapeFeishuMarkdown(answer.prompt()) + "**\n"
                    + (answer.source() == Clarification.AnswerSource.AGENT
                            ? "Agent 推荐" : "你的选择")
                    + "：" + CardText.escapeFeishuMarkdown(answer.answer()));
        }
        return String.join("\n\n", sections);
    }

    // ---- card.ts:407-433 clarificationDecisionButton ----

    private static ObjectNode clarificationDecisionButton(Clarification.ClarificationFlow flow,
            DecisionMode decisionMode) {
        Clarification.Question question = currentQuestion(flow);
        ObjectNode text = FACTORY.objectNode();
        text.put("tag", "plain_text");
        text.put("content", decisionMode == DecisionMode.CURRENT
                ? "这一题交给 Agent 决定"
                : "按推荐方案继续");
        ObjectNode value = FACTORY.objectNode();
        value.put("action", "answer_clarification");
        value.put("flowToken", flow.token());
        value.put("questionId", question.id());
        value.put("decisionMode", decisionMode.id());
        return fillButton(text,
                decisionMode == DecisionMode.REMAINING ? "primary" : "default", value);
    }

    /** 两种按钮共有的外壳（键序 tag/text/type/width/size/behaviors）。 */
    private static ObjectNode fillButton(ObjectNode text, String type, ObjectNode value) {
        ObjectNode behavior = FACTORY.objectNode();
        behavior.put("type", "callback");
        behavior.set("value", value);
        ArrayNode behaviors = FACTORY.arrayNode();
        behaviors.add(behavior);
        ObjectNode button = FACTORY.objectNode();
        button.put("tag", "button");
        button.set("text", text);
        button.put("type", type);
        button.put("width", "fill");
        button.put("size", "medium");
        button.set("behaviors", behaviors);
        return button;
    }

    private static Clarification.Question currentQuestion(Clarification.ClarificationFlow flow) {
        return flow.request().questions().get(flow.currentIndex());
    }

    // ---- card.ts:435-520 buildClarificationCard ----

    public static ObjectNode buildClarificationCard(Clarification.ClarificationFlow flow) {
        Clarification.ClarificationRequest request = flow.request();
        Clarification.Question question = currentQuestion(flow);
        int current = flow.currentIndex() + 1;
        int total = request.questions().size();

        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", request.title() + "（" + current + "/" + total + "）");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode title = FACTORY.objectNode();
        title.put("tag", "plain_text");
        title.put("content", request.title());
        ObjectNode subtitle = FACTORY.objectNode();
        subtitle.put("tag", "plain_text");
        subtitle.put("content", current + " / " + total);
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "blue");
        header.set("title", title);
        header.set("subtitle", subtitle);

        List<ObjectNode> elements = new ArrayList<>();
        // TS: flow.request.intro && flow.currentIndex === 0（真值检查）
        if (request.intro() != null && !request.intro().isEmpty() && flow.currentIndex() == 0) {
            elements.add(markdown(CardText.escapeFeishuMarkdown(request.intro())));
        }
        if (!flow.answers().isEmpty()) {
            elements.add(markdown("**已确认 " + flow.answers().size() + " 项**\n\n"
                    + clarificationAnswerSummary(flow)));
            elements.add(hr());
        }
        elements.add(markdown("**" + CardText.escapeFeishuMarkdown(question.prompt())
                + "**\n\n选择最符合预期的一项："));
        for (Clarification.Option option : question.options()) {
            elements.add(clarificationButton(flow, option));
        }
        elements.add(clarificationDecisionButton(flow, DecisionMode.CURRENT));
        elements.add(hr());
        elements.add(clarifyForm(flow, question));
        elements.add(hr());
        elements.add(markdown("不想逐项选择？Agent 会保留你已经确认的答案，并为剩余问题采用推荐方案。"));
        elements.add(clarificationDecisionButton(flow, DecisionMode.REMAINING));

        return card(config, header, elements);
    }

    /** card.ts:481-510 form 元素（自定义答案输入 + 提交按钮）。 */
    private static ObjectNode clarifyForm(Clarification.ClarificationFlow flow,
            Clarification.Question question) {
        ObjectNode placeholder = FACTORY.objectNode();
        placeholder.put("tag", "plain_text");
        placeholder.put("content", "都不合适？在这里写下你的答案");
        ObjectNode input = FACTORY.objectNode();
        input.put("tag", "input");
        input.put("name", "custom_answer");
        input.set("placeholder", placeholder);
        input.put("max_length", 500);

        ObjectNode submitText = FACTORY.objectNode();
        submitText.put("tag", "plain_text");
        submitText.put("content", "提交自定义答案");
        ObjectNode submitValue = FACTORY.objectNode();
        submitValue.put("action", "answer_clarification");
        submitValue.put("flowToken", flow.token());
        submitValue.put("questionId", question.id());
        submitValue.put("custom", true);
        ObjectNode submit = FACTORY.objectNode();
        submit.put("tag", "button");
        submit.put("name", "submit_custom");
        submit.put("action_type", "form_submit");
        submit.set("text", submitText);
        submit.put("type", "primary");
        submit.put("width", "default");
        submit.put("size", "medium");
        submit.set("value", submitValue);

        ArrayNode formElements = FACTORY.arrayNode();
        formElements.add(input);
        formElements.add(submit);
        ObjectNode form = FACTORY.objectNode();
        form.put("tag", "form");
        // TS: flow.token.slice(0, 8)——slice 越界安全，Java substring 须显式钳制
        form.put("name", "clarify_" + flow.token().substring(0, Math.min(8, flow.token().length())));
        form.put("vertical_spacing", "8px");
        form.set("elements", formElements);
        return form;
    }

    // ---- card.ts:522-551 buildClarificationContinuingCard ----

    public static ObjectNode buildClarificationContinuingCard(Clarification.ClarificationFlow flow) {
        String title = flow.request().title();
        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", title + "：正在整理");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode titleNode = FACTORY.objectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title + " · 正在整理");
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "blue");
        header.set("title", titleNode);

        String content = String.join("\n\n",
                "**答案已收到**",
                "正在基于这些选择整理产品说明，无需重复点击。",
                "**已确认 " + flow.answers().size() + " 项**\n\n" + clarificationAnswerSummary(flow));
        return card(config, header, List.of(markdown(content)));
    }

    // ---- card.ts:553-584 buildClarificationSupersededCard ----

    public static ObjectNode buildClarificationSupersededCard(Clarification.ClarificationFlow flow) {
        String title = flow.request().title();
        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", title + "：已收到新的补充");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode titleNode = FACTORY.objectNode();
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title + " · 已更新");
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "grey");
        header.set("title", titleNode);

        // TS: [...].filter(Boolean).join('\n\n')——前两段恒真值，第三段按答案有无加入
        List<String> parts = new ArrayList<>();
        parts.add("**已收到你在话题里的新消息**");
        parts.add("这张卡片已经失效，Agent OS 正在沿用同一个任务上下文处理新的补充。");
        if (!flow.answers().isEmpty()) {
            parts.add("此前已确认 " + flow.answers().size() + " 项，相关答案会一并带入。");
        }
        return card(config, header, List.of(markdown(String.join("\n\n", parts))));
    }

    // ---- 元素构造辅助 ----

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

    /** 卡片外壳（键序 schema/config/header/body；body 键序 direction/vertical_spacing/elements）。 */
    private static ObjectNode card(ObjectNode config, ObjectNode header, List<ObjectNode> elements) {
        ArrayNode elementsNode = FACTORY.arrayNode();
        for (ObjectNode element : elements) {
            elementsNode.add(element);
        }
        ObjectNode body = FACTORY.objectNode();
        body.put("direction", "vertical");
        body.put("vertical_spacing", "12px");
        body.set("elements", elementsNode);
        ObjectNode card = FACTORY.objectNode();
        card.put("schema", "2.0");
        card.set("config", config);
        card.set("header", header);
        card.set("body", body);
        return card;
    }
}

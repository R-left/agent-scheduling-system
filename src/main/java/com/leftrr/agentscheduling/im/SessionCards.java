package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.dto.cli.CliSessionSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 会话三卡（恢复 / 通知 / 团队）。卡片 JSON 手工构造，键序固定，别调。
 */
public final class SessionCards {

    /** "10/13 06:30" 这种格式；Locale 钉死，别依赖宿主默认值。 */
    static final DateTimeFormatter SESSION_TIME =
            DateTimeFormatter.ofPattern("M/d HH:mm", Locale.CHINA);

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private SessionCards() {}

    // ---- 选项接口 ----

    /** 恢复卡选项；currentCliSessionId 为 null 表示无当前会话。 */
    public record ResumeCardOptions(
            String agentSessionId,
            String cliName,
            String currentCliSessionId,
            List<CliSessionSummary> sessions) {}

    /** 会话通知卡选项；template null 时回退默认色。 */
    public record SessionNoticeCardOptions(String title, String detail, String template) {}

    /** 团队成员条目。 */
    public record TeamCardMember(
            String id,
            String displayName,
            String role,
            String cliName,
            List<String> skills,
            boolean isLeader,
            boolean ready) {}

    /** 团队卡选项。 */
    public record TeamCardOptions(List<TeamCardMember> members) {}

    /** 非 ISO 串原样返回；updatedAt 正常都是 ISO 串，走不到兜底。 */
    public static String formatSessionTime(String value, ZoneId zone) {
        try {
            return SESSION_TIME.format(Instant.parse(value).atZone(zone));
        // multi-catch 需为不相关类型（DateTimeParseException 是 RuntimeException 子类，不能并列）
        } catch (RuntimeException e) {
            return value;
        }
    }

    public static ObjectNode buildResumeCard(ResumeCardOptions options) {
        ZoneId zone = ZoneId.systemDefault();
        List<ObjectNode> elements = new ArrayList<>();
        if (!options.sessions().isEmpty()) {
            List<CliSessionSummary> sessions = options.sessions();
            for (int index = 0; index < sessions.size(); index++) {
                if (index > 0) {
                    elements.add(hr());
                }
                elements.add(sessionRow(options, sessions.get(index),
                        sessions.get(index).id().equals(options.currentCliSessionId()), zone));
            }
        } else {
            // 空会话文案（产品文案，勿改动）
            elements.add(markdown("当前工作目录里还没有可以恢复的 CLI 会话。先完成一次任务，"
                    + "再用 `/new` 开启新会话。"));
        }

        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", options.cliName() + "：选择历史会话");
        ObjectNode config = FACTORY.objectNode();
        config.put("update_multi", true);
        config.set("summary", summary);
        ObjectNode headerTitle = FACTORY.objectNode();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", "恢复历史会话");
        ObjectNode headerSubtitle = FACTORY.objectNode();
        headerSubtitle.put("tag", "plain_text");
        headerSubtitle.put("content", options.cliName());
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "blue");
        header.set("title", headerTitle);
        header.set("subtitle", headerSubtitle);
        ArrayNode bodyElements = FACTORY.arrayNode();
        bodyElements.add(markdown("选择后，当前话题会继续使用对应的 CLI 上下文。"));
        for (ObjectNode element : elements) {
            bodyElements.add(element);
        }
        ObjectNode body = FACTORY.objectNode();
        body.put("direction", "vertical");
        body.put("vertical_spacing", "12px");
        body.set("elements", bodyElements);
        ObjectNode card = FACTORY.objectNode();
        card.put("schema", "2.0");
        card.set("config", config);
        card.set("header", header);
        card.set("body", body);
        return card;
    }

    /** 键序固定。 */
    private static ObjectNode sessionRow(ResumeCardOptions options, CliSessionSummary session,
            boolean current, ZoneId zone) {
        // 行格式：**标题** + _时间 · id 前 8 位_；id 不 escape
        String idPrefix = session.id().substring(0, Math.min(8, session.id().length()));
        String rowContent = "**" + CardText.escapeFeishuMarkdown(session.title()) + "**\n_"
                + formatSessionTime(session.updatedAt(), zone) + " · " + idPrefix + "_";

        ObjectNode titleMarkdown = markdown(rowContent);
        ObjectNode titleColumn = FACTORY.objectNode();
        titleColumn.put("tag", "column");
        titleColumn.put("width", "weighted");
        titleColumn.put("weight", 4);
        ArrayNode titleElements = FACTORY.arrayNode();
        titleElements.add(titleMarkdown);
        titleColumn.set("elements", titleElements);

        ObjectNode actionColumn = FACTORY.objectNode();
        actionColumn.put("tag", "column");
        actionColumn.put("width", "auto");
        actionColumn.put("vertical_align", "center");
        ArrayNode actionElements = FACTORY.arrayNode();
        if (current) {
            actionElements.add(markdown("**当前会话**"));
        } else {
            // 键序固定，且不带 width 键
            ObjectNode text = FACTORY.objectNode();
            text.put("tag", "plain_text");
            text.put("content", "恢复");
            ObjectNode value = FACTORY.objectNode();
            value.put("action", "resume_cli_session");
            value.put("agentSessionId", options.agentSessionId());
            value.put("cliSessionId", session.id());
            ObjectNode behavior = FACTORY.objectNode();
            behavior.put("type", "callback");
            behavior.set("value", value);
            ArrayNode behaviors = FACTORY.arrayNode();
            behaviors.add(behavior);
            ObjectNode button = FACTORY.objectNode();
            button.put("tag", "button");
            button.set("text", text);
            button.put("type", "primary_filled");
            button.put("size", "medium");
            button.set("behaviors", behaviors);
            actionElements.add(button);
        }
        actionColumn.set("elements", actionElements);

        ArrayNode columns = FACTORY.arrayNode();
        columns.add(titleColumn);
        columns.add(actionColumn);
        ObjectNode row = FACTORY.objectNode();
        row.put("tag", "column_set");
        row.put("flex_mode", "none");
        row.put("horizontal_spacing", "12px");
        row.set("columns", columns);
        return row;
    }

    public static ObjectNode buildSessionNoticeCard(SessionNoticeCardOptions options) {
        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", options.title());
        // 这张卡故意没有 update_multi，别补
        ObjectNode config = FACTORY.objectNode();
        config.set("summary", summary);
        ObjectNode headerTitle = FACTORY.objectNode();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", options.title());
        ObjectNode header = FACTORY.objectNode();
        header.put("template", options.template() != null ? options.template() : "blue");
        header.set("title", headerTitle);
        ArrayNode bodyElements = FACTORY.arrayNode();
        bodyElements.add(markdown(options.detail()));
        ObjectNode body = FACTORY.objectNode();
        body.put("direction", "vertical");
        body.put("vertical_spacing", "12px");
        body.set("elements", bodyElements);
        ObjectNode card = FACTORY.objectNode();
        card.put("schema", "2.0");
        card.set("config", config);
        card.set("header", header);
        card.set("body", body);
        return card;
    }

    public static ObjectNode buildTeamCard(TeamCardOptions options) {
        // 没有 leader 时 subtitle 不带「负责统筹」
        TeamCardMember leader = null;
        for (TeamCardMember member : options.members()) {
            if (member.isLeader()) {
                leader = member;
                break;
            }
        }

        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", "Agent 团队：" + options.members().size() + " 位成员");
        ObjectNode config = FACTORY.objectNode();
        config.set("summary", summary);
        ObjectNode headerTitle = FACTORY.objectNode();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", "Agent 团队");
        String subtitle = leader != null
                ? options.members().size() + " 位成员 · " + leader.displayName() + " 负责统筹"
                : options.members().size() + " 位成员";
        ObjectNode headerSubtitle = FACTORY.objectNode();
        headerSubtitle.put("tag", "plain_text");
        headerSubtitle.put("content", subtitle);
        ObjectNode header = FACTORY.objectNode();
        header.put("template", "blue");
        header.set("title", headerTitle);
        header.set("subtitle", headerSubtitle);
        ArrayNode bodyElements = FACTORY.arrayNode();
        bodyElements.add(markdown("每位成员使用自己的飞书身份、执行引擎和项目 Skill，工作目录与会话彼此独立。"));
        bodyElements.add(hr());
        for (TeamCardMember member : options.members()) {
            bodyElements.add(markdown(memberRow(member)));
        }
        ObjectNode body = FACTORY.objectNode();
        body.put("direction", "vertical");
        body.put("vertical_spacing", "12px");
        body.set("elements", bodyElements);
        ObjectNode card = FACTORY.objectNode();
        card.put("schema", "2.0");
        card.set("config", config);
        card.set("header", header);
        card.set("body", body);
        return card;
    }

    /** 名字后两个空格再接斜体徽章；「引擎」和「Skill」之间是全角空格。 */
    private static String memberRow(TeamCardMember member) {
        List<String> badgeParts = new ArrayList<>();
        if (member.isLeader()) {
            badgeParts.add("Team Leader");
        }
        badgeParts.add(member.ready() ? "已连接" : "未连接");
        String badges = String.join(" · ", badgeParts);
        // skill 带 $ 前缀，空列表显示「无」
        String skills = !member.skills().isEmpty()
                ? String.join("、", member.skills().stream().map(skill -> "$" + skill).toList())
                : "无";
        return "**" + CardText.escapeFeishuMarkdown(member.displayName()) + "**  _" + badges + "_\n"
                + CardText.escapeFeishuMarkdown(member.role()) + "\n"
                + "引擎：" + CardText.escapeFeishuMarkdown(member.cliName())
                + "　Skill：" + CardText.escapeFeishuMarkdown(skills);
    }

    // ---- 元素构造辅助 ----

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

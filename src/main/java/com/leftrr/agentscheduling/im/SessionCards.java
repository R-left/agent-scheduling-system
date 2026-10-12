package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliSessionSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对齐 TS 版 src/im/card.ts 的三张会话卡：buildResumeCard（card.ts:598-666）、
 * buildSessionNoticeCard（card.ts:668-684）、buildTeamCard（card.ts:771-819），
 * 以及 formatSessionTime（card.ts:586-596）（M4 Task 6）。
 *
 * <p>卡片 JSON 用 ObjectNode 手工构造，put 顺序 = TS 对象字面量键序（设计裁决 1）。
 * 可空分量 null 表示 TS undefined；TS 空值合并（{@code ??}）落为 {@code != null}。
 * 三卡均无 collapsible（与 TS 一致，已核对 card.ts 598-819 无遗漏构造分支）。</p>
 */
public final class SessionCards {

    /**
     * card.ts:589-595 Intl zh-CN month/day numeric + hour/minute 2-digit + hour12:false 的形态
     * （"10/13 06:30"；M/d 单数不补零、HH:mm 恒两位）。Locale 显式钉 CHINA，
     * 对齐 TS Intl 显式 'zh-CN'，避免宿主默认 Locale 漂移（T6①）。
     */
    static final DateTimeFormatter SESSION_TIME =
            DateTimeFormatter.ofPattern("M/d HH:mm", Locale.CHINA);

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private SessionCards() {}

    // ---- card.ts:23-28 TS 选项接口 ----

    /** card.ts:23-28 ResumeCardOptions；currentCliSessionId null = TS undefined。 */
    public record ResumeCardOptions(
            String agentSessionId,
            String cliName,
            String currentCliSessionId,
            List<CliSessionSummary> sessions) {}

    /** card.ts:30-34 SessionNoticeCardOptions；template null = TS undefined。 */
    public record SessionNoticeCardOptions(String title, String detail, String template) {}

    /** card.ts:36-44 TeamCardMember。 */
    public record TeamCardMember(
            String id,
            String displayName,
            String role,
            String cliName,
            List<String> skills,
            boolean isLeader,
            boolean ready) {}

    /** card.ts:46-48 TeamCardOptions。 */
    public record TeamCardOptions(List<TeamCardMember> members) {}

    // ---- card.ts:586-596 formatSessionTime ----

    /**
     * 分歧注释：TS {@code new Date(value)} 对 "2026/10/13" 等非 ISO 串也解析成功，
     * Java {@code Instant.parse} 只认 ISO-8601——取舍：非 ISO 串按非法返回原样。
     * M6 调用点的值来自 M2 {@code CliSessionSummary.updatedAt}（设计裁决 8，恒为 ISO 毫秒精度 UTC 串），无实际差异。
     */
    public static String formatSessionTime(String value, ZoneId zone) {
        try {
            return SESSION_TIME.format(Instant.parse(value).atZone(zone));
        // multi-catch 需为不相关类型（DateTimeParseException 是 RuntimeException 子类，不能并列）
        } catch (RuntimeException e) {
            return value;  // TS Number.isNaN(getTime()) 分支
        }
    }

    // ---- card.ts:598-666 buildResumeCard ----

    public static ObjectNode buildResumeCard(ResumeCardOptions options) {
        // TS Intl.DateTimeFormat 不带 timeZone → 运行时本地时区（对齐 systemDefault）
        return buildResumeCard(options, ZoneId.systemDefault());
    }

    /** 包内可见时区注入重载（T6②）：公开签名不变，测试钉 UTC 用。 */
    static ObjectNode buildResumeCard(ResumeCardOptions options, ZoneId zone) {
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
            // card.ts:641-644 空会话文案（逐字，含 /new 反引号）
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
        // card.ts:661 首行说明
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

    /** card.ts:602-638 单行 column_set（键序 tag/flex_mode/horizontal_spacing/columns）。 */
    private static ObjectNode sessionRow(ResumeCardOptions options, CliSessionSummary session,
            boolean current, ZoneId zone) {
        // card.ts:613 会话行内容：**标题**\n_时间 · id 前 8 位_（id 不 escape，对齐 TS slice(0,8)）
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
            // card.ts:621
            actionElements.add(markdown("**当前会话**"));
        } else {
            // card.ts:622-635 恢复按钮（键序 tag/text/type/size/behaviors，无 width）
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

    // ---- card.ts:668-684 buildSessionNoticeCard ----

    public static ObjectNode buildSessionNoticeCard(SessionNoticeCardOptions options) {
        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", options.title());
        // card.ts:673 config 只有 summary，无 update_multi（Review Focus 2）
        ObjectNode config = FACTORY.objectNode();
        config.set("summary", summary);
        ObjectNode headerTitle = FACTORY.objectNode();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", options.title());
        ObjectNode header = FACTORY.objectNode();
        // card.ts:675 template ?? 'blue'
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

    // ---- card.ts:771-819 buildTeamCard ----

    public static ObjectNode buildTeamCard(TeamCardOptions options) {
        // card.ts:772 find 首个 isLeader（无则 null → subtitle 无「负责统筹」后缀）
        TeamCardMember leader = null;
        for (TeamCardMember member : options.members()) {
            if (member.isLeader()) {
                leader = member;
                break;
            }
        }

        ObjectNode summary = FACTORY.objectNode();
        summary.put("content", "Agent 团队：" + options.members().size() + " 位成员");
        // card.ts:793-795 config 只有 summary，无 update_multi
        ObjectNode config = FACTORY.objectNode();
        config.set("summary", summary);
        ObjectNode headerTitle = FACTORY.objectNode();
        headerTitle.put("tag", "plain_text");
        headerTitle.put("content", "Agent 团队");
        // card.ts:800-804 subtitle 分支
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
        // card.ts:810-813 首段说明（逐字）
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

    /** card.ts:774-788 成员行三行 join "\n"（名字与斜体间两个空格；「引擎」与「Skill」间全角空格 U+3000）。 */
    private static String memberRow(TeamCardMember member) {
        // card.ts:774-777 badges：filter(Boolean) 剔除非 leader 的空位，join " · "
        List<String> badgeParts = new ArrayList<>();
        if (member.isLeader()) {
            badgeParts.add("Team Leader");
        }
        badgeParts.add(member.ready() ? "已连接" : "未连接");
        String badges = String.join(" · ", badgeParts);
        // card.ts:778-780 skills：$前缀 、连接，空 → "无"
        String skills = !member.skills().isEmpty()
                ? String.join("、", member.skills().stream().map(skill -> "$" + skill).toList())
                : "无";
        return "**" + CardText.escapeFeishuMarkdown(member.displayName()) + "**  _" + badges + "_\n"
                + CardText.escapeFeishuMarkdown(member.role()) + "\n"
                + "引擎：" + CardText.escapeFeishuMarkdown(member.cliName())
                + "　Skill：" + CardText.escapeFeishuMarkdown(skills);
    }

    // ---- 元素构造辅助 ----

    /** {tag:'markdown', content}（card.ts 键序）。 */
    private static ObjectNode markdown(String content) {
        ObjectNode node = FACTORY.objectNode();
        node.put("tag", "markdown");
        node.put("content", content);
        return node;
    }

    /** {tag:'hr'}。 */
    private static ObjectNode hr() {
        ObjectNode node = FACTORY.objectNode();
        node.put("tag", "hr");
        return node;
    }
}

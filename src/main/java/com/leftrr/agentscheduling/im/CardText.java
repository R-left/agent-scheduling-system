package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliRunStats;
import com.leftrr.agentscheduling.cli.JsText;
import com.leftrr.agentscheduling.core.TaskActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对齐 TS 版 src/im/card.ts 的文本工具层（M4 Task 3）。
 *
 * <p>长度语义双轨（计划 Review Focus 1/6/7/8/9/10）：</p>
 * <ul>
 *   <li>切片族（markdownSplitIndex / splitLongText / answerContinuation 的切点）按
 *       <b>code unit</b>——对齐 TS String.length / slice / substring；</li>
 *   <li>compactAnswerPreview 兜底阈值（40）与 answerNeedsContinuation 阈值（6000）按
 *       <b>code point</b>——计划裁决，借助 {@link FeishuText#codePointLength}。</li>
 * </ul>
 */
public final class CardText {
    // card.ts:73-76
    public static final int COMPACT_ANSWER_LENGTH = 900;
    public static final int MAX_CARD_ANSWER_LENGTH = 6_000;
    public static final int RUNNING_ACTIVITY_LIMIT = 3;
    public static final int FINISHED_ACTIVITY_LIMIT = 8;
    // card.ts:962 splitLongText 默认参
    public static final int SPLIT_DEFAULT_MAX = 4_000;

    /** card.ts:78-90 TOOL_ICONS。 */
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

    /** card.ts:110 {@code /<(?=\/?[A-Za-z][^>]*>)/g}。 */
    private static final Pattern FEISHU_LT_ESCAPE = Pattern.compile("<(?=/?[A-Za-z][^>]*>)");

    /** card.ts:133 {@code /^```/gm}（仅行首计数）。
     * 差异注释：Java MULTILINE ^ 另在 \r、\u0085 后成立，JS m 旗标不含 \u0085（NEL）；
     * 正文由 CLI 事件流产出，实际不出现 NEL，忽略。 */
    private static final Pattern LINE_START_FENCE = Pattern.compile("^```", Pattern.MULTILINE);

    /** card.ts:145 {@code /\n(?:---|#{1,6}\s+[^\n]+)\s*$/}（无 MULTILINE，$ 即串尾）。
     * 差异注释：JS \s 额外匹配 U+FEFF（BOM），Java UNICODE_CHARACTER_CLASS 的 \s 不匹配；
     * 预览正文不含前导 BOM 场景，忽略。 */
    private static final Pattern TAIL_DECORATION =
            Pattern.compile("\\n(?:---|#{1,6}\\s+[^\\n]+)\\s*$", Pattern.UNICODE_CHARACTER_CLASS);

    /** card.ts:149 兜底文案（逐字，含全角标点）。 */
    private static final String COMPACT_ANSWER_FALLBACK = "回答包含较多代码与细节，展开后可以查看完整内容。";

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private CardText() {}

    // ---- card.ts:92-99 ----

    /** 先 round 后判 &lt;60：59_999ms → round=60 → "1 分钟"（不是"60 秒"）。 */
    public static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(1, Math.round(durationMs / 1_000.0));
        if (totalSeconds < 60) return totalSeconds + " 秒";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (seconds == 0) return minutes + " 分钟";
        return minutes + " 分 " + seconds + " 秒";
    }

    // ---- card.ts:101-103 ----

    /** JS 数字串化：Math.round(value/100)/10 在 JS 中 2.0 → "2"，Java 需整值去 .0。 */
    public static String formatCount(long value) {
        if (value < 1_000) return String.valueOf(value);
        double k = Math.round(value / 100.0) / 10.0;
        return (k == Math.rint(k) ? String.valueOf((long) k) : String.valueOf(k)) + "k";
    }

    // ---- card.ts:105-107 ----

    public static String escapeInlineCode(String value) {
        return value.replace("`", "ˋ");
    }

    // ---- card.ts:109-111 ----

    /** 仅替换 {@code <} 为 {@code <&zwj;}：{@code <div>}{@code </div>} 替换；{@code <123>}、无 {@code >} 的 {@code a<b} 不替换。 */
    public static String escapeFeishuMarkdown(String value) {
        return FEISHU_LT_ESCAPE.matcher(value).replaceAll("<&zwj;");
    }

    // ---- card.ts:113-122 ----

    public static String activityLine(TaskActivity activity) {
        String icon = activity.failed() ? "⚠️" : TOOL_ICONS.getOrDefault(activity.toolName(), "⚙️");
        String detail = (activity.detail() != null && !activity.detail().isEmpty())
                ? " · `" + escapeInlineCode(activity.detail()) + "`"
                : "";
        String duration = activity.durationMs() >= 1_000
                ? " · " + formatDuration(activity.durationMs())
                : "";
        return icon + " " + activity.label() + detail + duration;
    }

    // ---- card.ts:124-130（code unit 语义：String.length / lastIndexOf / substring）----

    public static int markdownSplitIndex(String text, int maxLength) {
        if (text.length() <= maxLength) return text.length();   // 注意：code unit（Review Focus 1）
        int paragraph = text.lastIndexOf("\n\n", maxLength);
        if (paragraph >= maxLength * 0.55) return paragraph;    // 浮点乘法
        int line = text.lastIndexOf("\n", maxLength);
        return line >= maxLength * 0.55 ? line : maxLength;
    }

    // ---- card.ts:132-135 ----

    /** 只数行首 ```（MULTILINE ^），奇数补 {@code \n\n```}。 */
    public static String closeOpenFence(String markdown) {
        Matcher matcher = LINE_START_FENCE.matcher(markdown);
        int fences = 0;
        while (matcher.find()) {
            fences++;
        }
        return fences % 2 == 1 ? markdown + "\n\n```" : markdown;
    }

    // ---- card.ts:137-139 ----

    public static String markdownPreview(String text, int maxLength) {
        return closeOpenFence(JsText.jsTrim(text.substring(0, markdownSplitIndex(text, maxLength))));
    }

    // ---- card.ts:141-150 ----

    public static String compactAnswerPreview(String answer) {
        int fenceIndex = answer.indexOf("\n```");               // TS answer.search(/\n```/)
        String prose = fenceIndex > 0 ? answer.substring(0, fenceIndex) : answer;
        String preview = JsText.jsTrim(TAIL_DECORATION.matcher(
                markdownPreview(prose, COMPACT_ANSWER_LENGTH)).replaceAll(""));
        // 计划裁决：阈值按 code point（TS preview.length 为 code unit，见类注释）
        return FeishuText.codePointLength(preview) >= 40 ? preview : COMPACT_ANSWER_FALLBACK;
    }

    // ---- card.ts:954-960 ----

    /** 计划裁决：阈值按 code point（TS answer.length 为 code unit，见类注释）。 */
    public static boolean answerNeedsContinuation(String answer) {
        return FeishuText.codePointLength(answer) > MAX_CARD_ANSWER_LENGTH;
    }

    /** brief 指定：codePointSlice(answer, markdownSplitIndex(answer, 6000), end)。 */
    public static String answerContinuation(String answer) {
        return FeishuText.codePointSlice(answer,
                markdownSplitIndex(answer, MAX_CARD_ANSWER_LENGTH),
                FeishuText.codePointLength(answer));
    }

    // ---- card.ts:152-162 ----

    /** 返回 null 表示 TS undefined。 */
    public static Long usageTotal(CliRunStats stats) {
        if (stats == null) return null;
        if (stats.totalTokens() != null) return stats.totalTokens();
        long sum = 0;
        boolean any = false;
        for (Long value : new Long[]{
                stats.inputTokens(), stats.outputTokens(),
                stats.cacheReadTokens(), stats.cacheCreationTokens()}) {
            if (value != null) {
                sum += value;
                any = true;
            }
        }
        return any ? sum : null;
    }

    // ---- card.ts:164-175 ----

    public static String formatContextUsage(Long usedTokens, Long windowTokens) {
        if (usedTokens == null) return null;
        if (windowTokens == null || windowTokens <= 0) {
            return "当前上下文约 " + formatCount(usedTokens) + " tokens";
        }
        if (usedTokens > windowTokens) return null;
        long percentage = Math.round((double) usedTokens / windowTokens * 100);
        return "当前上下文 " + formatCount(usedTokens) + " / " + formatCount(windowTokens)
                + "（" + percentage + "%）";
    }

    // ---- card.ts:177-189 ----

    public static String formatContextGrowth(Long usedTokens, Long startTokens, boolean startedNewSession) {
        if (usedTokens == null || startTokens == null) return null;
        long delta = usedTokens - startTokens;
        String change = delta >= 0
                ? "新增 " + formatCount(delta)
                : "减少 " + formatCount(Math.abs(delta));
        String startLabel = startedNewSession ? "新会话基础" : "本轮开始";
        return startLabel + " " + formatCount(startTokens) + " · " + change;
    }

    // ---- card.ts:962-973（code unit 语义）----

    public static List<String> splitLongText(String text) {
        return splitLongText(text, SPLIT_DEFAULT_MAX);
    }

    public static List<String> splitLongText(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxLength) {
            int newline = remaining.lastIndexOf("\n", maxLength);
            // JS maxLength/2 为浮点除法，用 2.0 对齐（奇数 maxLength 时与整除不同）
            int splitAt = newline > maxLength / 2.0 ? newline : maxLength;
            chunks.add(JsText.jsTrim(remaining.substring(0, splitAt)));
            remaining = JsText.jsTrim(remaining.substring(splitAt));
        }
        if (!remaining.isEmpty()) chunks.add(remaining);
        return chunks;
    }

    // ---- card.ts:334-346 ----

    /** collapsible_panel 的 header 结构，逐键 put 对齐 TS 字面量。 */
    public static ObjectNode collapsibleHeader(String content) {
        ObjectNode title = FACTORY.objectNode();
        title.put("tag", "plain_text");
        title.put("content", content);
        ObjectNode icon = FACTORY.objectNode();
        icon.put("tag", "standard_icon");
        icon.put("token", "down-small-ccm_outlined");
        icon.put("size", "16px 16px");
        ObjectNode header = FACTORY.objectNode();
        header.set("title", title);
        header.put("vertical_align", "center");
        header.set("icon", icon);
        header.put("icon_position", "right");
        header.put("icon_expanded_angle", -180);
        return header;
    }
}

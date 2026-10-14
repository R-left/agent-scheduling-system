package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.dto.cli.CliRunStats;
import com.leftrr.agentscheduling.util.StringUtil;
import com.leftrr.agentscheduling.dto.core.TaskActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 卡片文本工具。
 * 注意口径：本类长度一律按 UTF-16 code unit（String.length / substring），
 * FeishuText 那边才是 code point，别混。
 */
public final class CardText {
    public static final int COMPACT_ANSWER_LENGTH = 900;
    public static final int MAX_CARD_ANSWER_LENGTH = 6_000;
    public static final int RUNNING_ACTIVITY_LIMIT = 3;
    public static final int FINISHED_ACTIVITY_LIMIT = 8;
    public static final int SPLIT_DEFAULT_MAX = 4_000;

    /** 工具名 → 图标；TaskCards 共用这一份。 */
    public static final Map<String, String> TOOL_ICONS = Map.ofEntries(
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

    /** {@code /<(?=\/?[A-Za-z][^>]*>)/g}。 */
    private static final Pattern FEISHU_LT_ESCAPE = Pattern.compile("<(?=/?[A-Za-z][^>]*>)");

    /** 行首代码围栏：只数每行行首的三反引号。
     * 口径说明：MULTILINE 下 ^ 在 \u0085 后也成立（比严格行首更宽）；
     * 正文来自 CLI 事件流，实际碰不到，忽略。 */
    private static final Pattern LINE_START_FENCE = Pattern.compile("^```", Pattern.MULTILINE);

    /** 串尾装饰：换行后的 --- 或标题行，允许尾随空白。
     * 口径说明：\s 开 UNICODE_CHARACTER_CLASS，按 Unicode White_Space 属性
     * 匹配（不含 U+FEFF）；预览正文不含该类字符，影响可忽略。 */
    private static final Pattern TAIL_DECORATION =
            Pattern.compile("\\n(?:---|#{1,6}\\s+[^\\n]+)\\s*$", Pattern.UNICODE_CHARACTER_CLASS);

    /** 展开摘要的兜底文案。 */
    private static final String COMPACT_ANSWER_FALLBACK = "回答包含较多代码与细节，展开后可以查看完整内容。";

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private CardText() {}

    /** 先 round 后判 &lt;60：59_999ms → round=60 → "1 分钟"（不是"60 秒"）。 */
    public static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(1, Math.round(durationMs / 1_000.0));
        if (totalSeconds < 60) return totalSeconds + " 秒";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (seconds == 0) return minutes + " 分钟";
        return minutes + " 分 " + seconds + " 秒";
    }

    /** 整值时去掉 ".0"：2.0 → "2"。 */
    public static String formatCount(long value) {
        if (value < 1_000) return String.valueOf(value);
        double k = Math.round(value / 100.0) / 10.0;
        return (k == Math.rint(k) ? String.valueOf((long) k) : String.valueOf(k)) + "k";
    }

    public static String escapeInlineCode(String value) {
        return value.replace("`", "ˋ");
    }

    /** 仅替换 {@code <} 为 {@code <&zwj;}：{@code <div>}{@code </div>} 替换；{@code <123>}、无 {@code >} 的 {@code a<b} 不替换。 */
    public static String escapeFeishuMarkdown(String value) {
        return FEISHU_LT_ESCAPE.matcher(value).replaceAll("<&zwj;");
    }

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

    /** 优先按空行分段、其次按行、再退回 maxLength 硬切；分点位置不得早于 0.55*maxLength。 */
    public static int markdownSplitIndex(String text, int maxLength) {
        if (text.length() <= maxLength) return text.length();
        int paragraph = text.lastIndexOf("\n\n", maxLength);
        if (paragraph >= maxLength * 0.55) return paragraph;
        int line = text.lastIndexOf("\n", maxLength);
        return line >= maxLength * 0.55 ? line : maxLength;
    }

    /** 只数行首 ```（MULTILINE ^），奇数补 {@code \n\n```}。 */
    public static String closeOpenFence(String markdown) {
        Matcher matcher = LINE_START_FENCE.matcher(markdown);
        int fences = 0;
        while (matcher.find()) {
            fences++;
        }
        return fences % 2 == 1 ? markdown + "\n\n```" : markdown;
    }

    public static String markdownPreview(String text, int maxLength) {
        return closeOpenFence(StringUtil.trim(text.substring(0, markdownSplitIndex(text, maxLength))));
    }

    /** 展开摘要预览：截断到 COMPACT_ANSWER_LENGTH、闭合代码围栏、去掉尾部装饰；过短则用兜底文案。 */
    public static String compactAnswerPreview(String answer) {
        int fenceIndex = answer.indexOf("\n```");
        String prose = fenceIndex > 0 ? answer.substring(0, fenceIndex) : answer;
        String preview = StringUtil.trim(TAIL_DECORATION.matcher(
                markdownPreview(prose, COMPACT_ANSWER_LENGTH)).replaceAll(""));
        return preview.length() >= 40 ? preview : COMPACT_ANSWER_FALLBACK;
    }

    /** 回答超长需要续文（code unit 语义）。 */
    public static boolean answerNeedsContinuation(String answer) {
        return answer.length() > MAX_CARD_ANSWER_LENGTH;
    }

    /** 取超出部分作为续文。 */
    public static String answerContinuation(String answer) {
        return answer.substring(markdownSplitIndex(answer, MAX_CARD_ANSWER_LENGTH));
    }

    /** 返回 null 表示无 token 统计。 */
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

    public static String formatContextGrowth(Long usedTokens, Long startTokens, boolean startedNewSession) {
        if (usedTokens == null || startTokens == null) return null;
        long delta = usedTokens - startTokens;
        String change = delta >= 0
                ? "新增 " + formatCount(delta)
                : "减少 " + formatCount(Math.abs(delta));
        String startLabel = startedNewSession ? "新会话基础" : "本轮开始";
        return startLabel + " " + formatCount(startTokens) + " · " + change;
    }

    public static List<String> splitLongText(String text) {
        return splitLongText(text, SPLIT_DEFAULT_MAX);
    }

    /** 优先按行、退回 maxLength 硬切，逐段 trim。 */
    public static List<String> splitLongText(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxLength) {
            int newline = remaining.lastIndexOf("\n", maxLength);
            // 浮点除法：奇数 maxLength 时和整除结果不一样
            int splitAt = newline > maxLength / 2.0 ? newline : maxLength;
            chunks.add(StringUtil.trim(remaining.substring(0, splitAt)));
            remaining = StringUtil.trim(remaining.substring(splitAt));
        }
        if (!remaining.isEmpty()) chunks.add(remaining);
        return chunks;
    }

    /** collapsible_panel 的 header 结构。 */
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

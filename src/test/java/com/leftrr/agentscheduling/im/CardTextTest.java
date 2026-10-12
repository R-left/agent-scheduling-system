package com.leftrr.agentscheduling.im;

import com.leftrr.agentscheduling.cli.CliRunStats;
import com.leftrr.agentscheduling.core.TaskActivity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts 的文本工具层（Task 3 测试用例清单 36 条 + R7 修复轮补充 1 条）。
 * 语义口径（R7）：card.ts 的 .length 恒为 code unit，全部阈值与切片按 String.length()/substring()。
 */
class CardTextTest {

    // ---- formatDuration（card.ts:92-99）----

    @Test
    void formatDuration_01_roundZeroClampsToOneSecond() {
        assertEquals("1 秒", CardText.formatDuration(400));
    }

    @Test
    void formatDuration_02_plainSeconds() {
        assertEquals("45 秒", CardText.formatDuration(45_000));
    }

    @Test
    void formatDuration_03_roundsBeforeMinuteCheck() {
        // round(59.999)=60 → 先 round 后判 <60 → 走分钟分支
        assertEquals("1 分钟", CardText.formatDuration(59_999));
    }

    @Test
    void formatDuration_04_exactMinutes() {
        assertEquals("1 分钟", CardText.formatDuration(60_000));
        assertEquals("2 分钟", CardText.formatDuration(120_000));
    }

    @Test
    void formatDuration_05_minutesAndSeconds() {
        assertEquals("2 分 5 秒", CardText.formatDuration(125_000));
    }

    // ---- formatCount（card.ts:101-103）----

    @Test
    void formatCount_06_belowThresholdAndRoundK() {
        assertEquals("999", CardText.formatCount(999));
        assertEquals("1k", CardText.formatCount(1000));
    }

    @Test
    void formatCount_07_fractionalKAndRoundUp() {
        assertEquals("1.5k", CardText.formatCount(1500));
        // round(19.99)/10 = 2 → 整值去 .0
        assertEquals("2k", CardText.formatCount(1999));
    }

    // ---- escapeInlineCode（card.ts:105-107）----

    @Test
    void escapeInlineCode_08_replacesAllBackticks() {
        assertEquals("ˋxˋ 和 ˋyˋ", CardText.escapeInlineCode("`x` 和 `y`"));
    }

    // ---- escapeFeishuMarkdown（card.ts:109-111）----

    @Test
    void escapeFeishuMarkdown_09_escapesTagsOnly() {
        assertEquals("<&zwj;div>x<&zwj;/div>", CardText.escapeFeishuMarkdown("<div>x</div>"));
    }

    @Test
    void escapeFeishuMarkdown_10_lookaheadRequiresLetterThenAngle() {
        assertEquals("<123>", CardText.escapeFeishuMarkdown("<123>"));   // 数字开头不替换
        assertEquals("a<b", CardText.escapeFeishuMarkdown("a<b"));       // 无 > 不替换
        assertEquals("1<2>3", CardText.escapeFeishuMarkdown("1<2>3"));   // <2 数字开头不替换
        assertEquals("x<&zwj;y>z", CardText.escapeFeishuMarkdown("x<y>z"));
    }

    // ---- markdownSplitIndex（card.ts:124-130，code unit 语义）----

    @Test
    void markdownSplitIndex_11_shortTextReturnsItsLength() {
        assertEquals(5, CardText.markdownSplitIndex("hello", 100));
        assertEquals(5, CardText.markdownSplitIndex("hello", 5));
    }

    @Test
    void markdownSplitIndex_12_paragraphBelowThresholdFallsToMaxLength() {
        // \n\n 位于 20（< 0.55*100=55）→ 段落切点不达标；其后 \n 亦 <55 → maxLength
        String text = "a".repeat(20) + "\n\n" + "b".repeat(80);
        assertEquals(100, CardText.markdownSplitIndex(text, 100));
    }

    @Test
    void markdownSplitIndex_13_paragraphAtOrAboveThreshold() {
        // \n\n 位于 60（≥55）→ 60
        String text = "a".repeat(60) + "\n\n" + "b".repeat(41);
        assertEquals(60, CardText.markdownSplitIndex(text, 100));
    }

    @Test
    void markdownSplitIndex_14_lineSplitThreshold() {
        // 无 \n\n，\n 位于 60（≥55）→ 60
        String withLine = "a".repeat(60) + "\n" + "b".repeat(41);
        assertEquals(60, CardText.markdownSplitIndex(withLine, 100));
        // \n 位于 30（<55）→ maxLength
        String lowLine = "a".repeat(30) + "\n" + "b".repeat(71);
        assertEquals(100, CardText.markdownSplitIndex(lowLine, 100));
    }

    // ---- closeOpenFence（card.ts:132-135）----

    @Test
    void closeOpenFence_15_evenOrNoFenceUnchanged() {
        assertEquals("普通文本，无代码围栏", CardText.closeOpenFence("普通文本，无代码围栏"));
        assertEquals("```java\nA\n```\nB", CardText.closeOpenFence("```java\nA\n```\nB"));
    }

    @Test
    void closeOpenFence_16_oddLineStartFenceAppendsClosing() {
        assertEquals("```java\nA\n\n```", CardText.closeOpenFence("```java\nA"));
    }

    @Test
    void closeOpenFence_17_inlineFenceNotCounted() {
        // 行中 ```（非行首）不计数（MULTILINE ^ 语义）
        assertEquals("code ```x``` tail", CardText.closeOpenFence("code ```x``` tail"));
        // 行内围栏不影响行首奇偶判定：唯一行首围栏 → 奇数 → 补全
        assertEquals("```\ncode ```x```\n\n```", CardText.closeOpenFence("```\ncode ```x```"));
    }

    // ---- markdownPreview（card.ts:137-139）----

    @Test
    void markdownPreview_18_sliceTrimCloseFenceComposite() {
        // 长 110 > 55；\n\n 无；\n 位于 8（<30.25）→ 切点 55；前缀含行首 ``` → 补全
        String text = "```intro\n" + "a".repeat(50) + "\n" + "b".repeat(50);
        assertEquals("```intro\n" + "a".repeat(46) + "\n\n```",
                CardText.markdownPreview(text, 55));
    }

    // ---- compactAnswerPreview（card.ts:141-150）----

    @Test
    void compactAnswerPreview_19_noFenceShortTextTrimmed() {
        // 无代码块 → 全文，trim 后返回
        assertEquals("x".repeat(50), CardText.compactAnswerPreview("  " + "x".repeat(50) + "  "));
    }

    @Test
    void compactAnswerPreview_20_fenceSplitsProse() {
        // fenceIndex>0 → 取 prose="正文一"（<40 → 兜底文案，代码段被剥除可观测）
        assertEquals("回答包含较多代码与细节，展开后可以查看完整内容。",
                CardText.compactAnswerPreview("正文一\n```code```"));
        // prose ≥40 → prose 原样返回
        String prose = "结论很明确：" + "x".repeat(40);
        assertEquals(prose, CardText.compactAnswerPreview(prose + "\n```code```"));
    }

    @Test
    void compactAnswerPreview_21_fenceAtZeroNotCut() {
        // 串以 "\n```" 开头 → indexOf 命中位置 0，不 >0 → 不切，全文保留
        String answer = "\n```" + "c".repeat(60) + "```";
        assertEquals("```" + "c".repeat(60) + "```" + "\n\n```",
                CardText.compactAnswerPreview(answer));
    }

    @Test
    void compactAnswerPreview_22_tailDecorationStripped() {
        // 尾部 --- 剥除
        String withRule = "结论：" + "x".repeat(40) + "\n---\n";
        assertEquals("结论：" + "x".repeat(40), CardText.compactAnswerPreview(withRule));
        // 尾部标题剥除
        String withHeading = "结论：" + "x".repeat(40) + "\n## 附注";
        assertEquals("结论：" + "x".repeat(40), CardText.compactAnswerPreview(withHeading));
        // 串中间的 --- 不剥（$ 串尾锚）
        String middleRule = "结论：" + "x".repeat(20) + "\n---\n" + "y".repeat(30);
        assertEquals(middleRule, CardText.compactAnswerPreview(middleRule));
    }

    @Test
    void compactAnswerPreview_23_fallbackBelowFortyCodeUnits() {
        assertEquals(900, CardText.COMPACT_ANSWER_LENGTH);
        // 39 < 40 → 兜底
        assertEquals("回答包含较多代码与细节，展开后可以查看完整内容。",
                CardText.compactAnswerPreview("x".repeat(39)));
        // 恰 40 → 原样
        assertEquals("x".repeat(40), CardText.compactAnswerPreview("x".repeat(40)));
        // code unit 语义（R7）：😀×20 = 20 code point / 40 code unit → 恰 40 → 原样
        assertEquals("😀".repeat(20), CardText.compactAnswerPreview("😀".repeat(20)));
    }

    @Test
    void answerCodeUnitSemantics_37_astralReverseNail() {
        // R7 反方向钉子：阈值与切点均按 code unit（card.ts 的 .length 恒为 code unit）
        // 😀×3000 = 6000 code unit = 恰 6000 → false；😀×3001 = 6002 code unit > 6000 → true
        assertFalse(CardText.answerNeedsContinuation("😀".repeat(3000)));
        assertTrue(CardText.answerNeedsContinuation("😀".repeat(3001)));
        // 😀×19 = 38 code unit < 40 → 兜底
        assertEquals("回答包含较多代码与细节，展开后可以查看完整内容。",
                CardText.compactAnswerPreview("😀".repeat(19)));
        // code unit 切片：8004 code unit（😀×4000 + "tail"）→ 切点 6000（即 3000 个 emoji 之后）
        String answer = "😀".repeat(4000) + "tail";
        assertEquals("😀".repeat(1000) + "tail", CardText.answerContinuation(answer));
    }

    // ---- splitLongText（card.ts:962-973，默认 4000）----

    @Test
    void splitLongText_24_shortTextSingleChunk() {
        assertEquals(List.of("hello"), CardText.splitLongText("hello"));
    }

    @Test
    void splitLongText_25_noNewlineHardSplitWithTrim() {
        List<String> chunks = CardText.splitLongText("a".repeat(9000));
        assertEquals(3, chunks.size());
        assertEquals("a".repeat(4000), chunks.get(0));
        assertEquals("a".repeat(4000), chunks.get(1));
        assertEquals("a".repeat(1000), chunks.get(2));
    }

    @Test
    void splitLongText_26_newlineAboveHalfSplitsThere() {
        // \n 位于 3001（>2000）→ 从 \n 切（不含换行符，两侧 trim）
        List<String> chunks = CardText.splitLongText("a".repeat(3000) + " \n " + "b".repeat(5000));
        assertEquals(List.of("a".repeat(3000), "b".repeat(4000), "b".repeat(1000)), chunks);
    }

    @Test
    void splitLongText_27_newlineAtOrBelowHalfHardSplits() {
        // \n 位于 2000（≤2000）→ 硬切 4000
        List<String> chunks = CardText.splitLongText("a".repeat(2000) + "\n" + "b".repeat(5000));
        assertEquals(2, chunks.size());
        assertEquals("a".repeat(2000) + "\n" + "b".repeat(1999), chunks.get(0));
        assertEquals("b".repeat(3001), chunks.get(1));
    }

    @Test
    void splitLongText_28_tailRemainderPushedOnlyIfNonEmpty() {
        // 余量 trim 后为空串 → 不 push
        List<String> emptyTail = CardText.splitLongText("a".repeat(4000) + "\n  ");
        assertEquals(1, emptyTail.size());
        assertEquals("a".repeat(4000), emptyTail.get(0));
        // 尾部余量 push
        List<String> withTail = CardText.splitLongText("a".repeat(4001));
        assertEquals(List.of("a".repeat(4000), "a"), withTail);
    }

    @Test
    void splitLongText_29_defaultOverloadUsesConstant() {
        assertEquals(4000, CardText.SPLIT_DEFAULT_MAX);
        assertEquals(3, CardText.RUNNING_ACTIVITY_LIMIT);
        assertEquals(8, CardText.FINISHED_ACTIVITY_LIMIT);
        String text = "a".repeat(9000);
        assertEquals(CardText.splitLongText(text, CardText.SPLIT_DEFAULT_MAX), CardText.splitLongText(text));
    }

    // ---- answerNeedsContinuation / answerContinuation（card.ts:954-960）----

    @Test
    void answerNeedsContinuation_30_codeUnitThreshold() {
        assertEquals(6000, CardText.MAX_CARD_ANSWER_LENGTH);
        assertFalse(CardText.answerNeedsContinuation("a".repeat(6000)));  // 恰 6000 → false
        assertTrue(CardText.answerNeedsContinuation("a".repeat(6001)));   // > 6000 → true
        // code unit 语义（R7）：😀×3001 = 3001 code point / 6002 code unit > 6000 → true
        assertTrue(CardText.answerNeedsContinuation("😀".repeat(3001)));
    }

    @Test
    void answerContinuation_31_splitIndexSubstringTail() {
        String answer = "a".repeat(6000) + "\n" + "b".repeat(100);
        String continuation = CardText.answerContinuation(answer);
        // markdownSplitIndex(answer, 6000) = 6000（\n 恰在 6000 ≥ 3300）→ 取剩余部分
        assertEquals("\n" + "b".repeat(100), continuation);
    }

    // ---- usageTotal（card.ts:152-162）----

    @Test
    void usageTotal_32_nullStatsOrTotalTokensShortcut() {
        assertNull(CardText.usageTotal(null));
        // totalTokens 非空 → 直接取，忽略其余分项
        CliRunStats stats = new CliRunStats(null, null, 500L, 1L, 2L, 3L, 4L, null, null);
        assertEquals(500L, CardText.usageTotal(stats));
    }

    @Test
    void usageTotal_33_sumSkipsNullParts() {
        assertEquals(10L, CardText.usageTotal(
                new CliRunStats(null, null, null, 1L, 2L, 3L, 4L, null, null)));
        assertEquals(3L, CardText.usageTotal(
                new CliRunStats(null, null, null, 1L, 2L, null, null, null, null)));
        assertNull(CardText.usageTotal(
                new CliRunStats(null, null, null, null, null, null, null, null, null)));
    }

    // ---- formatContextUsage / formatContextGrowth（card.ts:164-189）----

    @Test
    void formatContextUsage_34_branchesAndRounding() {
        assertNull(CardText.formatContextUsage(null, 200_000L));
        assertEquals("当前上下文约 12k tokens", CardText.formatContextUsage(12_000L, null));
        assertEquals("当前上下文约 12k tokens", CardText.formatContextUsage(12_000L, 0L));
        assertEquals("当前上下文约 12k tokens", CardText.formatContextUsage(12_000L, -5L));
        assertNull(CardText.formatContextUsage(300L, 200L));
        assertEquals("当前上下文 12k / 200k（6%）",
                CardText.formatContextUsage(12_000L, 200_000L));
        // round 语义：12500/200000 = 6.25 → 6
        assertEquals("当前上下文 12.5k / 200k（6%）",
                CardText.formatContextUsage(12_500L, 200_000L));
    }

    @Test
    void formatContextGrowth_35_branchesAndLabels() {
        assertNull(CardText.formatContextGrowth(null, 10_000L, false));
        assertNull(CardText.formatContextGrowth(12_000L, null, false));
        // delta ≥ 0
        assertEquals("本轮开始 10k · 新增 2k",
                CardText.formatContextGrowth(12_000L, 10_000L, false));
        // delta < 0 → abs
        assertEquals("本轮开始 10k · 减少 2k",
                CardText.formatContextGrowth(8_000L, 10_000L, false));
        // startedNewSession
        assertEquals("新会话基础 10k · 新增 2k",
                CardText.formatContextGrowth(12_000L, 10_000L, true));
    }

    // ---- activityLine（card.ts:113-122）----

    @Test
    void activityLine_36_iconsDetailAndDuration() {
        // icon 映射抽查
        assertEquals("🧩 启动子代理", CardText.activityLine(new TaskActivity("Agent", "启动子代理", null, 0, false)));
        assertEquals("⌘ 执行命令", CardText.activityLine(new TaskActivity("Bash", "执行命令", null, 0, false)));
        assertEquals("⚙️ 做事情", CardText.activityLine(new TaskActivity("Mystery", "做事情", null, 0, false)));
        // failed → ⚠️ 优先于 icon，且带时长段
        assertEquals("⚠️ 启动子代理 · 5 秒",
                CardText.activityLine(new TaskActivity("Agent", "启动子代理", null, 5_000, true)));
        // detail 拼接（escapeInlineCode 后），无时长
        assertEquals("⌘ 执行命令 · `npm ˋtestˋ`",
                CardText.activityLine(new TaskActivity("Bash", "执行命令", "npm `test`", 0, false)));
        // durationMs >= 1000 → 时长段；< 1000 → 无
        assertEquals("⌘ 执行命令 · 1 分钟",
                CardText.activityLine(new TaskActivity("Bash", "执行命令", null, 60_000, false)));
        assertEquals("⌘ 执行命令",
                CardText.activityLine(new TaskActivity("Bash", "执行命令", null, 999, false)));
        // 边界：恰 1000 → 有时长段
        assertEquals("⌘ 执行命令 · 1 秒",
                CardText.activityLine(new TaskActivity("Bash", "执行命令", null, 1_000, false)));
    }
}

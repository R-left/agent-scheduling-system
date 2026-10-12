package com.leftrr.agentscheduling.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/lark.ts 的文本限额常量与 fitFeishuText（Task 0 测试用例清单）。
 */
class FeishuTextTest {

    @Test
    void constantsMatchTsLarkDotTs() {
        assertEquals(3000, FeishuText.FEISHU_TEXT_LIMIT);
        assertEquals(1200, FeishuText.FEISHU_MENTION_LIMIT);
        assertEquals(1000, FeishuText.FEISHU_COMMENT_LIMIT);
    }

    @Test
    void shortTextPassesThroughIdentically() {
        assertEquals("短文本", FeishuText.fitFeishuText("短文本", 3000));
    }

    @Test
    void textExactlyAtMaxLengthPassesThrough() {
        String exact = "字".repeat(3000);
        assertEquals(3000, FeishuText.codePointLength(exact));
        assertEquals(exact, FeishuText.fitFeishuText(exact, 3000));
    }

    @Test
    void overLimitTextIsTruncatedWithVerbatimSuffix() {
        String suffix = FeishuText.TRUNCATE_SUFFIX;
        assertEquals("\n\n（内容过长，已截断。详细内容请写入文档或工作区文件。）", suffix);
        String over = "长".repeat(3100);
        String result = FeishuText.fitFeishuText(over, 3000);
        assertTrue(result.endsWith(suffix));
        assertEquals("长".repeat(3000 - FeishuText.codePointLength(suffix)) + suffix, result);
    }

    @Test
    void truncatedResultHasExactlyMaxLengthCodePoints() {
        String suffix = FeishuText.TRUNCATE_SUFFIX;
        // brief 记后缀 28 个 code point，实测（对齐 TS Array.from）为 29："\n\n（" 3 + 正文 24 + "。）" 2
        assertEquals(29, FeishuText.codePointLength(suffix));
        String result = FeishuText.fitFeishuText("文".repeat(3100), 3000);
        assertEquals(3000, FeishuText.codePointLength(result));
        assertEquals(3000 - FeishuText.codePointLength(suffix), FeishuText.codePointLength(result.substring(0, result.length() - suffix.length())));
    }

    @Test
    void surrogatePairsAreNotSplitAtTruncationBoundary() {
        // "😀" 是代理对（2 code unit / 1 code point）。40 个 emoji = 40 code point = 80 code unit。
        // keep = 34 - 29 = 5（奇数），若按 code unit 下标截会劈在第 3 个 emoji 中间。
        String text = "😀".repeat(40);
        String result = FeishuText.fitFeishuText(text, 34);
        assertEquals(34, FeishuText.codePointLength(result));
        // 逐 char 检查：每个高位代理项后必须紧跟低位代理项，不允许孤立代理项
        char[] chars = result.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (Character.isHighSurrogate(chars[i])) {
                assertTrue(i + 1 < chars.length && Character.isLowSurrogate(chars[i + 1]),
                        "结果中存在孤立的高位代理项，index=" + i);
            }
            if (Character.isLowSurrogate(chars[i])) {
                assertTrue(i > 0 && Character.isHighSurrogate(chars[i - 1]),
                        "结果中存在孤立的低位代理项，index=" + i);
            }
        }
        // 截断边界处 emoji 完整保留：前缀恰为 5 个完整 emoji
        assertTrue(result.startsWith("😀".repeat(5)));
    }

    @Test
    void maxLengthSmallerThanSuffixYieldsSuffixAlone() {
        // TS Math.max(0, maxLength - suffixLength) → 前缀空串。
        // brief 原输入"很长的一段文本内容"仅 9 code point ≤ 10，TS 语义下原样返回不触发截断，
        // 故加长到 14 code point 真正超限，保留用例意图：maxLength=10 < 后缀 29。
        assertEquals(14, FeishuText.codePointLength("很长的一段文本内容需要被截断"));
        assertEquals(FeishuText.TRUNCATE_SUFFIX, FeishuText.fitFeishuText("很长的一段文本内容需要被截断", 10));
    }

    @Test
    void codePointLengthCountsCodePointsNotCodeUnits() {
        assertEquals(2, FeishuText.codePointLength("😀😀"));
        assertEquals(2, FeishuText.codePointLength("ab"));
    }

    @Test
    void codePointSliceCutsByCodePointIndex() {
        assertEquals("a😀", FeishuText.codePointSlice("a😀b", 0, 2));
    }
}

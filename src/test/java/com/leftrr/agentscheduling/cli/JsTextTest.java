package com.leftrr.agentscheduling.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsTextTest {
    @Test
    void trimsLikeEcmascript() {
        // 向量与 M1 CommandParserTest 的 jsTrim 用例一致（字符用 uXXXX 转义书写，防编辑器规范化）
        assertEquals("x", JsText.jsTrim("  x  "));
        assertEquals("x", JsText.jsTrim(" x"));        // NBSP
        assertEquals("x", JsText.jsTrim("　x﻿"));  // 全角空格 + BOM
        assertEquals("x", JsText.jsTrim(" x "));  // 行分隔符 + 段落分隔符
        assertEquals("", JsText.jsTrim(""));
        assertEquals("", JsText.jsTrim(" ﻿"));    // 仅空白字符
    }

    @Test
    void stripsVerticalTabAsJsWhiteSpace() {
        // \u000B（VT）是 ECMAScript 空白 → 位于首尾时应被剥除（对齐 JS "a\u000Bb".trim() 语义：
        // trim 只剥首尾，中段 VT 保留）。brief 原向量把 VT 写在中段却期望 "ab"，自相矛盾；
        // 按括注的本意（VT 属于 JS 空白集合、会被剥除）以首尾向量钉住防退化。
        assertTrue(JsText.isJsWhiteSpace('\u000B'));
        assertEquals("", JsText.jsTrim("\u000B"));
        assertEquals("ab", JsText.jsTrim("\u000Bab\u000B"));
        // 中段空白不受 trim 影响（JS 行为基准）
        assertEquals("a\u000Bb", JsText.jsTrim("a\u000Bb"));
    }

    @Test
    void nullInputThrowsNpe() {
        // 对齐"入参必为非 null 字符串"的 M1 约定
        assertThrows(NullPointerException.class, () -> JsText.jsTrim(null));
    }
}

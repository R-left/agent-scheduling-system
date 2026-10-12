package com.leftrr.agentscheduling.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 对齐 TS 版 src/im/lark.ts 的 extractMessageText（lark.ts:205-222）
 * 与 renderPostElement（lark.ts:196-203）的测试用例清单
 * （M4 Task 1 brief Step 1，用例编号 19-25 与 brief 一一对应）。
 */
class MessageContentTest {

    @Test
    void case19_textMessageTypeReturnsTextFieldOrDefault() {
        // {"text":"你好"} → "你好"；{"other":1} → ""（TS parsed.text ?? ''）
        assertEquals("你好", MessageContent.extractMessageText("text", "{\"text\":\"你好\"}"));
        assertEquals("", MessageContent.extractMessageText("text", "{\"other\":1}"));
    }

    @Test
    void case20_postSegmentsRenderAtBrAndTextLikeTags() {
        // 段内 at → user_id、br → "\n"、text/a/code/code_block/md → text 字段，段内直接拼接，段间 "\n"
        String content = "{\"content\":["
                + "[{\"tag\":\"text\",\"text\":\"第一段\"}],"
                + "[{\"tag\":\"at\",\"user_id\":\"ou_9\"},{\"tag\":\"br\"},"
                + "{\"tag\":\"a\",\"text\":\"链接\"},{\"tag\":\"code\",\"text\":\"c\"},"
                + "{\"tag\":\"code_block\",\"text\":\"cb\"},{\"tag\":\"md\",\"text\":\"m\"}]]}";
        assertEquals("第一段\nou_9\n链接ccbm", MessageContent.extractMessageText("post", content));
    }

    @Test
    void case21_emptyParagraphsFilteredOutWithoutExtraNewline() {
        // 段落 filter(Boolean)：空段（全未知 tag）剔除，不产生多余 "\n"；全空 post → ""
        String content = "{\"content\":["
                + "[{\"tag\":\"text\",\"text\":\"a\"}],"
                + "[{\"tag\":\"foo\"}]]}";
        assertEquals("a", MessageContent.extractMessageText("post", content));
        assertEquals("", MessageContent.extractMessageText("post", "{\"content\":[[{\"tag\":\"foo\"}]]}"));
    }

    @Test
    void case22_postResultJsTrimmedOverall() {
        // 整体 jsTrim：前后空白/换行剥除（设计裁决 6，对齐 TS .trim()）
        String content = "{\"content\":[[{\"tag\":\"text\",\"text\":\"\\n\\t你好\\n  \"}]]}";
        assertEquals("你好", MessageContent.extractMessageText("post", content));
    }

    @Test
    void case23_unknownMessageTypeReturnsEmptyString() {
        assertEquals("", MessageContent.extractMessageText("voice", "{\"text\":\"x\"}"));
    }

    @Test
    void case24_invalidContentJsonThrowsIllegalArgument() {
        // TS JSON.parse SyntaxError 冒泡 → unchecked IllegalArgumentException（设计裁决 10）
        assertThrows(IllegalArgumentException.class,
                () -> MessageContent.extractMessageText("text", "not json"));
    }

    @Test
    void case25_unknownTagElementRendersEmptyString() {
        // renderPostElement 默认分支（lark.ts:202）
        String content = "{\"content\":[[{\"tag\":\"unknown_tag\",\"text\":\"x\"}]]}";
        assertEquals("", MessageContent.extractMessageText("post", content));
    }
}

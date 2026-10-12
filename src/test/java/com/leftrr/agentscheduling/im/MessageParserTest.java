package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/message-parser.ts 的测试用例清单（M4 Task 1 brief Step 1，
 * 用例编号 1-18 与 brief 一一对应）。
 */
class MessageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- parseMentions（message-parser.ts:14-21，brief 用例 1-5）----

    @Test
    void case01_nullAndEmptyArrayReturnEmptyList() {
        assertTrue(MessageParser.parseMentions(null).isEmpty());
        assertTrue(MessageParser.parseMentions(json("[]")).isEmpty());
    }

    @Test
    void case02_fullElementMapsToMention() {
        JsonNode raw = json(
                "[{\"key\":\"@_user_1\",\"name\":\"MyBot\",\"id\":{\"open_id\":\"ou_1\"}}]");
        assertEquals(List.of(new Mention("@_user_1", "MyBot", "ou_1")),
                MessageParser.parseMentions(raw));
    }

    @Test
    void case03_missingNameFallsBackToEmptyString() {
        // TS m.name ?? ''
        JsonNode raw = json("[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"ou_1\"}}]");
        assertEquals(List.of(new Mention("@_user_1", "", "ou_1")),
                MessageParser.parseMentions(raw));
    }

    @Test
    void case04_missingIdOrOpenIdFallsBackToEmptyOpenId() {
        // TS m.id?.open_id ?? ''：id 缺失 / id.open_id 缺失 两例
        JsonNode noId = json("[{\"key\":\"@_user_1\",\"name\":\"甲\"}]");
        assertEquals(List.of(new Mention("@_user_1", "甲", "")),
                MessageParser.parseMentions(noId));
        JsonNode emptyId = json("[{\"key\":\"@_user_1\",\"name\":\"甲\",\"id\":{}}]");
        assertEquals(List.of(new Mention("@_user_1", "甲", "")),
                MessageParser.parseMentions(emptyId));
    }

    @Test
    void case05_multipleElementsPreserveOrder() {
        JsonNode raw = json(
                "[{\"key\":\"@_user_1\",\"name\":\"甲\",\"id\":{\"open_id\":\"ou_a\"}},"
                        + "{\"key\":\"@_user_2\",\"name\":\"乙\",\"id\":{\"open_id\":\"ou_b\"}}]");
        List<Mention> result = MessageParser.parseMentions(raw);
        assertEquals(2, result.size());
        assertEquals("@_user_1", result.get(0).key());
        assertEquals("@_user_2", result.get(1).key());
        assertEquals("ou_a", result.get(0).openId());
        assertEquals("ou_b", result.get(1).openId());
    }

    // ---- resolveMentions（message-parser.ts:26-32，brief 用例 6-10）----

    @Test
    void case06_singlePlaceholderReplacedWithDisplayName() {
        String result = MessageParser.resolveMentions("@_user_1 你好",
                List.of(new Mention("@_user_1", "MyBot", "ou_1")));
        assertEquals("@MyBot 你好", result);
    }

    @Test
    void case07_multiplePlaceholdersEachResolveToItsOwnMention() {
        String result = MessageParser.resolveMentions("@_user_1 好好干，@_user_2",
                List.of(new Mention("@_user_1", "甲", "ou_a"),
                        new Mention("@_user_2", "乙", "ou_b")));
        assertEquals("@甲 好好干，@乙", result);
    }

    @Test
    void case08_samePlaceholderReplacedAtEveryOccurrence() {
        // TS replaceAll 语义：同一占位多次出现全替换
        String result = MessageParser.resolveMentions("@_user_1 看看 @_user_1",
                List.of(new Mention("@_user_1", "MyBot", "ou_1")));
        assertEquals("@MyBot 看看 @MyBot", result);
    }

    @Test
    void case09_leadingTrailingWhitespaceStrippedViaJsTrim() {
        List<Mention> mentions = List.of(new Mention("@_user_1", "MyBot", "ou_1"));
        // ASCII 空白
        assertEquals("@MyBot", MessageParser.resolveMentions(" @_user_1 ", mentions));
        // NBSP 向量：ECMAScript trim 剥 NBSP；jsTrim（设计裁决 6）覆盖同一全集。
        // UNICODE_CHARACTER_CLASS 差异注释：Java 正则 \s 即使开了该旗标也不含 BOM（U+FEFF），
        // 而 ECMAScript \s/trim 含之——此处不走正则，由 JsText.isJsWhiteSpace 统一剥除（M2 先例）。
        assertEquals("@MyBot",
                MessageParser.resolveMentions("  @_user_1  ", mentions));
    }

    @Test
    void case10_noMentionsReturnsJsTrimmedOriginal() {
        assertEquals("原文", MessageParser.resolveMentions("  原文  ", List.of()));
    }

    // ---- extractResourceKeys（message-parser.ts:37-61，brief 用例 11-18）----

    @Test
    void case11_imageWithImageKeyExtracted() {
        List<ExtractedResource> result =
                MessageParser.extractResourceKeys("image", "{\"image_key\":\"img_v2_x\"}");
        assertEquals(List.of(new ExtractedResource(ExtractedResource.ResourceType.IMAGE, "img_v2_x", null)),
                result);
    }

    @Test
    void case12_imageWithoutImageKeyReturnsEmpty() {
        assertTrue(MessageParser.extractResourceKeys("image", "{}").isEmpty());
    }

    @Test
    void case13_fileWithKeyAndNameExtracted() {
        List<ExtractedResource> result = MessageParser.extractResourceKeys(
                "file", "{\"file_key\":\"file_v3_x\",\"file_name\":\"报告.pdf\"}");
        assertEquals(List.of(new ExtractedResource(ExtractedResource.ResourceType.FILE, "file_v3_x", "报告.pdf")),
                result);
    }

    @Test
    void case14_fileWithoutFileNameYieldsNullFileName() {
        // TS fileName: parsed.file_name（undefined）→ null（设计裁决 11）
        List<ExtractedResource> result =
                MessageParser.extractResourceKeys("file", "{\"file_key\":\"file_v3_x\"}");
        assertEquals(List.of(new ExtractedResource(ExtractedResource.ResourceType.FILE, "file_v3_x", null)),
                result);
    }

    @Test
    void case15_postNestedParagraphsExtractImagesInFlatOrder() {
        // 两段各含 img 元素 + 混入 text 元素，paragraphs.flat() 语义 → 按 flat 顺序提取 2 个 image
        String content = "{\"content\":["
                + "[{\"tag\":\"text\",\"text\":\"图一前\"},{\"tag\":\"img\",\"image_key\":\"img_v2_1\"}],"
                + "[{\"tag\":\"img\",\"image_key\":\"img_v2_2\"},{\"tag\":\"text\",\"text\":\"图二后\"}]]}";
        List<ExtractedResource> result = MessageParser.extractResourceKeys("post", content);
        assertEquals(List.of(
                        new ExtractedResource(ExtractedResource.ResourceType.IMAGE, "img_v2_1", null),
                        new ExtractedResource(ExtractedResource.ResourceType.IMAGE, "img_v2_2", null)),
                result);
    }

    @Test
    void case16_postWithoutImgReturnsEmpty() {
        String content = "{\"content\":[[{\"tag\":\"text\",\"text\":\"纯文字\"}]]}";
        assertTrue(MessageParser.extractResourceKeys("post", content).isEmpty());
    }

    @Test
    void case17_invalidContentJsonThrowsIllegalArgument() {
        // TS JSON.parse SyntaxError 冒泡 → unchecked IllegalArgumentException（设计裁决 10）
        assertThrows(IllegalArgumentException.class,
                () -> MessageParser.extractResourceKeys("image", "not json"));
    }

    @Test
    void case18_otherMessageTypeReturnsEmptyEvenWithImageKey() {
        assertTrue(MessageParser
                .extractResourceKeys("text", "{\"image_key\":\"img_v2_x\"}").isEmpty());
    }
}

package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/lark.ts 的 buildMentionPostContent（129-149）、
 * resourceExtension（178-188）与 CONTENT_TYPE_EXTENSIONS（161-168）
 * （M4 Task 2 brief Step 1，用例编号 6-17 与 brief 一一对应；
 * 用例 14 拆 image/file 两例凑齐 13 条）。
 */
class LarkPostsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- buildMentionPostContent（lark.ts:129-149，brief 用例 6-9）----

    @Test
    void case06_goldenStructureWithoutUserName() {
        // 键序、前导空格逐字（brief 黄金断言）
        JsonNode result = LarkPosts.buildMentionPostContent(new BotIdentity("ou_x", ""), "你好");
        String actual;
        try {
            actual = MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        assertEquals("""
                {"zh_cn":{"title":"","content":[[{"tag":"at","user_id":"ou_x"},\
                {"tag":"text","text":" 你好"}]]}}""", actual);
    }

    @Test
    void case07_nonEmptyNameAddsUserNameAfterUserId() {
        JsonNode result = LarkPosts.buildMentionPostContent(new BotIdentity("ou_x", "甲乙"), "hi");
        JsonNode at = result.path("zh_cn").path("content").get(0).get(0);
        assertEquals("at", at.path("tag").asText());
        Iterator<String> fields = at.fieldNames();
        assertEquals("tag", fields.next());
        assertEquals("user_id", fields.next());
        assertEquals("user_name", fields.next());
        assertFalse(fields.hasNext());
        assertEquals("甲乙", at.path("user_name").asText());
    }

    @Test
    void case08_emptyNameOmitsUserNameKey() {
        JsonNode result = LarkPosts.buildMentionPostContent(new BotIdentity("ou_x", ""), "hi");
        JsonNode at = result.path("zh_cn").path("content").get(0).get(0);
        assertFalse(at.has("user_name"));
    }

    @Test
    void case09_overLimitTextTruncatedToMentionLimitCodePoints() {
        String over = "文".repeat(1300);
        JsonNode result = LarkPosts.buildMentionPostContent(new BotIdentity("ou_x", ""), over);
        JsonNode textElement = result.path("zh_cn").path("content").get(0).get(1);
        assertEquals("text", textElement.path("tag").asText());
        String expected = " " + FeishuText.fitFeishuText(over, FeishuText.FEISHU_MENTION_LIMIT);
        assertEquals(expected, textElement.path("text").asText());
        // 复用 fitFeishuText(FEISHU_MENTION_LIMIT)：正文（不含前导空格）总长 1200 code point
        assertEquals(1200, FeishuText.codePointLength(textElement.path("text").asText()) - 1);
    }

    // ---- resourceExtension（lark.ts:178-188，brief 用例 10-15、17）----

    @Test
    void case10_validFileNameExtensionReturnedLowercased() {
        assertEquals("pdf", LarkPosts.resourceExtension("file", "报告.PDF", ""));
        assertEquals("b", LarkPosts.resourceExtension("file", "a.b", ""));
    }

    @Test
    void case11_tooLongOrIllegalExtensionFallsBackToContentType() {
        // 11 位扩展名 → 超出 [a-z0-9]{1,10}
        assertEquals("png", LarkPosts.resourceExtension("file", "a.abcdefghijk", "image/png"));
        // 含非法字符（空格）→ 走 contentType 映射
        assertEquals("png", LarkPosts.resourceExtension("file", "a.abc def", "image/png"));
    }

    @Test
    void case12_contentTypeTakesSemicolonPrefixTrimmedAndLowercased() {
        assertEquals("jpg", LarkPosts.resourceExtension("file", null, "image/jpeg; charset=utf-8"));
        assertEquals("jpg", LarkPosts.resourceExtension("file", null, "IMAGE/JPEG"));
    }

    @Test
    void case13_sixKnownMimeMappings() {
        assertEquals("jpg", LarkPosts.resourceExtension("file", null, "image/jpeg"));
        assertEquals("png", LarkPosts.resourceExtension("file", null, "image/png"));
        assertEquals("gif", LarkPosts.resourceExtension("file", null, "image/gif"));
        assertEquals("webp", LarkPosts.resourceExtension("file", null, "image/webp"));
        assertEquals("bmp", LarkPosts.resourceExtension("file", null, "image/bmp"));
        assertEquals("ico", LarkPosts.resourceExtension("file", null, "image/x-icon"));
    }

    @Test
    void case14a_unknownMimeImageFallsBackToImg() {
        assertEquals("img", LarkPosts.resourceExtension("image", null, "image/svg+xml"));
    }

    @Test
    void case14b_unknownMimeFileFallsBackToBin() {
        assertEquals("bin", LarkPosts.resourceExtension("file", null, "application/octet-stream"));
    }

    @Test
    void case15_nullOrEmptyFileNameGoesStraightToMime() {
        assertEquals("jpg", LarkPosts.resourceExtension("file", null, "image/jpeg"));
        assertEquals("jpg", LarkPosts.resourceExtension("file", "", "image/jpeg"));
    }

    @Test
    void case16_contentTypeExtensionsGoldenTableInTsLiteralOrder() {
        List<Map.Entry<String, String>> expected = List.of(
                Map.entry("image/jpeg", "jpg"),
                Map.entry("image/png", "png"),
                Map.entry("image/gif", "gif"),
                Map.entry("image/webp", "webp"),
                Map.entry("image/bmp", "bmp"),
                Map.entry("image/x-icon", "ico"));
        assertEquals(expected.size(), LarkPosts.CONTENT_TYPE_EXTENSIONS.size());
        int i = 0;
        for (Map.Entry<String, String> entry : LarkPosts.CONTENT_TYPE_EXTENSIONS.entrySet()) {
            assertEquals(expected.get(i), entry);
            i++;
        }
    }

    @Test
    void case17_extensionRegexBoundaryOneAndTenCharsValid() {
        // 恰好 10 位合法
        assertEquals("abcdefghij", LarkPosts.resourceExtension("file", "x.abcdefghij", ""));
        // 1 位合法
        assertEquals("a", LarkPosts.resourceExtension("file", "x.a", ""));
        // 11 位非法（对照，见用例 11）
        assertEquals("bin", LarkPosts.resourceExtension("file", "x.abcdefghijk", ""));
    }
}

package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.JsText;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息正文提取：从飞书消息 content JSON 还原纯文本。
 * 对齐 TS 版 src/im/lark.ts 的 extractMessageText（lark.ts:205-222）
 * 与 renderPostElement（lark.ts:196-203）。
 */
public final class MessageContent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageContent() {
    }

    /**
     * 对齐 lark.ts:205-222。TS JSON.parse（SyntaxError 冒泡）→
     * unchecked IllegalArgumentException（设计裁决 10）。
     */
    public static String extractMessageText(String messageType, String content) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("消息 content 非法 JSON: " + e.getMessage(), e);
        }

        if ("text".equals(messageType)) {
            return parsed.path("text").asText(""); // TS parsed.text ?? ''
        }

        if ("post".equals(messageType)) {
            // TS parsed.content ?? []：缺失/null 视空数组
            JsonNode paragraphs = parsed.path("content");
            if (paragraphs.isMissingNode() || paragraphs.isNull()) {
                return "";
            }
            if (!paragraphs.isArray()) {
                // TS paragraphs.map 对非数组抛 TypeError（结构非法冒泡）→ 对齐为 unchecked
                throw new IllegalArgumentException("post content 必须是段落数组");
            }
            List<String> rendered = new ArrayList<>();
            for (JsonNode paragraph : paragraphs) {
                if (!paragraph.isArray()) {
                    // TS paragraph.map 对非数组抛 TypeError（结构非法冒泡）→ 对齐为 unchecked
                    throw new IllegalArgumentException("post 段落必须是元素数组");
                }
                StringBuilder line = new StringBuilder();
                for (JsonNode element : paragraph) {
                    line.append(renderPostElement(element)); // 段内直接拼接
                }
                rendered.add(line.toString());
            }
            // 段落 filter(Boolean) 剔除空段，段间 join('\n')，整体 trim（设计裁决 6）
            List<String> nonEmpty = rendered.stream().filter(s -> !s.isEmpty()).toList();
            return JsText.jsTrim(String.join("\n", nonEmpty));
        }

        return "";
    }

    /** 对齐 lark.ts:196-203：at → user_id、br → 换行、text/a/code/code_block/md → text 字段，其余空串。 */
    private static String renderPostElement(JsonNode element) {
        String tag = element.path("tag").asText("");
        if ("at".equals(tag)) {
            return element.path("user_id").asText(""); // TS element.user_id ?? ''
        }
        if ("br".equals(tag)) {
            return "\n";
        }
        if ("text".equals(tag) || "a".equals(tag) || "code".equals(tag)
                || "code_block".equals(tag) || "md".equals(tag)) {
            return element.path("text").asText(""); // TS element.text ?? ''
        }
        return "";
    }
}

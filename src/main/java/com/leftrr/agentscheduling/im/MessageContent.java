package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息正文提取：从飞书消息 content JSON 还原纯文本。
 */
public final class MessageContent {

    private MessageContent() {
    }

    /** content 非法 JSON 抛 IllegalArgumentException（来自 parseContent）。 */
    public static String extractMessageText(String messageType, String content) {
        JsonNode parsed = MessageParser.parseContent(content);

        if ("text".equals(messageType)) {
            return parsed.path("text").asText("");
        }

        if ("post".equals(messageType)) {
            JsonNode paragraphs = parsed.path("content");
            if (paragraphs.isMissingNode() || paragraphs.isNull()) {
                return "";
            }
            if (!paragraphs.isArray()) {
                throw new IllegalArgumentException("post content 必须是段落数组");
            }
            List<String> rendered = new ArrayList<>();
            for (JsonNode paragraph : paragraphs) {
                if (!paragraph.isArray()) {
                    throw new IllegalArgumentException("post 段落必须是元素数组");
                }
                StringBuilder line = new StringBuilder();
                for (JsonNode element : paragraph) {
                    line.append(renderPostElement(element));
                }
                rendered.add(line.toString());
            }
            List<String> nonEmpty = rendered.stream().filter(s -> !s.isEmpty()).toList();
            return StringUtil.trim(String.join("\n", nonEmpty));
        }

        return "";
    }

    /** at → user_id、br → 换行、text/a/code/code_block/md → text 字段，其余空串。 */
    private static String renderPostElement(JsonNode element) {
        String tag = element.path("tag").asText("");
        if ("at".equals(tag)) {
            return element.path("user_id").asText("");
        }
        if ("br".equals(tag)) {
            return "\n";
        }
        if ("text".equals(tag) || "a".equals(tag) || "code".equals(tag)
                || "code_block".equals(tag) || "md".equals(tag)) {
            return element.path("text").asText("");
        }
        return "";
    }
}

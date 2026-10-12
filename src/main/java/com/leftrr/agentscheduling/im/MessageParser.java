package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.JsText;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息解析：@提及还原、图片/文件资源提取。
 * 对齐 TS 版 src/im/message-parser.ts。
 */
public final class MessageParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageParser() {
    }

    /**
     * TS JSON.parse（SyntaxError 冒泡）→ unchecked IllegalArgumentException（设计裁决 10）。
     * 包内共享：MessageContent.extractMessageText 复用同一包装（T1②）。
     */
    static JsonNode parseContent(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("消息 content 非法 JSON: " + e.getMessage(), e);
        }
    }

    /** 对齐 TS truthy（资源 key 场景）：undefined/null/''/0 为假，其余为真。 */
    private static boolean isTruthy(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asDouble() != 0d;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        return true; // 对象/数组在 TS 中恒真
    }

    /**
     * 从事件的 mentions 数组中提取结构化提及信息。对齐 message-parser.ts:14-21。
     */
    public static List<Mention> parseMentions(JsonNode raw) {
        if (raw == null || !raw.isArray() || raw.isEmpty()) {
            return List.of();
        }
        List<Mention> mentions = new ArrayList<>();
        for (JsonNode m : raw) {
            mentions.add(new Mention(
                    m.path("key").asText(""),
                    m.path("name").asText(""),                 // TS m.name ?? ''
                    m.path("id").path("open_id").asText("")));
        }
        return List.copyOf(mentions);
    }

    /**
     * 把消息文本里的 @_user_N 占位符替换成 @显示名。对齐 message-parser.ts:26-32。
     */
    public static String resolveMentions(String text, List<Mention> mentions) {
        String resolved = text;
        for (Mention m : mentions) {
            resolved = resolved.replace(m.key(), "@" + m.name()); // String.replace 全替换，对齐 TS replaceAll
        }
        return JsText.jsTrim(resolved);                            // TS resolved.trim()，设计裁决 6
    }

    /**
     * 从消息 content 中提取资源 key（image_key / file_key）。对齐 message-parser.ts:37-61。
     */
    public static List<ExtractedResource> extractResourceKeys(String messageType, String content) {
        JsonNode parsed = parseContent(content);
        List<ExtractedResource> resources = new ArrayList<>();

        // TS ===：messageType 精确相等
        if ("image".equals(messageType) && isTruthy(parsed.path("image_key"))) {
            resources.add(new ExtractedResource(
                    ExtractedResource.ResourceType.IMAGE, parsed.path("image_key").asText(), null));
        }
        if ("file".equals(messageType) && isTruthy(parsed.path("file_key"))) {
            // TS fileName: parsed.file_name —— undefined/null → null（设计裁决 11）
            JsonNode fileNameNode = parsed.path("file_name");
            String fileName = (fileNameNode.isMissingNode() || fileNameNode.isNull())
                    ? null : fileNameNode.asText();
            resources.add(new ExtractedResource(
                    ExtractedResource.ResourceType.FILE, parsed.path("file_key").asText(), fileName));
        }

        if ("post".equals(messageType)) {
            // TS parsed.content ?? []：缺失/null 视空数组
            JsonNode paragraphs = parsed.path("content");
            if (paragraphs.isMissingNode() || paragraphs.isNull()) {
                return List.copyOf(resources);
            }
            if (!paragraphs.isArray()) {
                // TS paragraphs.flat() 对非数组抛 TypeError（结构非法冒泡）→ 对齐为 unchecked
                throw new IllegalArgumentException("post content 必须是段落数组");
            }
            // TS paragraphs.flat() 只有一层嵌套 → 双层遍历
            for (JsonNode paragraph : paragraphs) {
                if (!paragraph.isArray()) {
                    // 差异（T1①）：TS flat() 把非数组段（对象）原样保留进扁平结果，
                    // 若该对象恰为 {tag:'img', image_key} 形态，TS 仍会提取该图片；
                    // Java 此处整段跳过，不提取。
                    continue;
                }
                for (JsonNode element : paragraph) {
                    if ("img".equals(element.path("tag").asText("")) && isTruthy(element.path("image_key"))) {
                        resources.add(new ExtractedResource(
                                ExtractedResource.ResourceType.IMAGE,
                                element.path("image_key").asText(), null));
                    }
                }
            }
        }

        return List.copyOf(resources);
    }
}

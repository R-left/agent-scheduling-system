package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.util.StringUtil;
import com.leftrr.agentscheduling.dto.im.ExtractedResource;
import com.leftrr.agentscheduling.dto.im.Mention;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息解析：@提及还原、图片/文件资源提取。
 */
public final class MessageParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageParser() {
    }

    /** content 非法 JSON 抛 IllegalArgumentException；MessageContent 复用这份。 */
    static JsonNode parseContent(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("消息 content 非法 JSON: " + e.getMessage(), e);
        }
    }

    /** 真值判定：缺失/null/空串/0 为假，其余为真。 */
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
        return true; // 对象/数组恒真
    }

    public static List<Mention> parseMentions(JsonNode raw) {
        if (raw == null || !raw.isArray() || raw.isEmpty()) {
            return List.of();
        }
        List<Mention> mentions = new ArrayList<>();
        for (JsonNode m : raw) {
            mentions.add(new Mention(
                    m.path("key").asText(""),
                    m.path("name").asText(""),
                    m.path("id").path("open_id").asText("")));
        }
        return List.copyOf(mentions);
    }

    /** 把 @_user_N 占位替换成 @显示名，trim 后返回。 */
    public static String resolveMentions(String text, List<Mention> mentions) {
        String resolved = text;
        for (Mention m : mentions) {
            resolved = resolved.replace(m.key(), "@" + m.name());
        }
        return StringUtil.trim(resolved);
    }

    public static List<ExtractedResource> extractResourceKeys(String messageType, String content) {
        JsonNode parsed = parseContent(content);
        List<ExtractedResource> resources = new ArrayList<>();

        if ("image".equals(messageType) && isTruthy(parsed.path("image_key"))) {
            resources.add(new ExtractedResource(
                    ExtractedResource.ResourceType.IMAGE, parsed.path("image_key").asText(), null));
        }
        if ("file".equals(messageType) && isTruthy(parsed.path("file_key"))) {
            JsonNode fileNameNode = parsed.path("file_name");
            String fileName = (fileNameNode.isMissingNode() || fileNameNode.isNull())
                    ? null : fileNameNode.asText();
            resources.add(new ExtractedResource(
                    ExtractedResource.ResourceType.FILE, parsed.path("file_key").asText(), fileName));
        }

        if ("post".equals(messageType)) {
            JsonNode paragraphs = parsed.path("content");
            if (paragraphs.isMissingNode() || paragraphs.isNull()) {
                return List.copyOf(resources);
            }
            if (!paragraphs.isArray()) {
                throw new IllegalArgumentException("post content 必须是段落数组");
            }
            for (JsonNode paragraph : paragraphs) {
                if (!paragraph.isArray()) {
                    // 非数组段（对象）整段跳过，里面的图片也不提取
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

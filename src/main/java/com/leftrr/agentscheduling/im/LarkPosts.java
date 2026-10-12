package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 飞书 post 消息构造与资源扩展名推断。
 * 对齐 TS 版 src/im/lark.ts 的 buildMentionPostContent（129-149）、
 * resourceExtension（178-188）与 CONTENT_TYPE_EXTENSIONS（161-168）。
 */
public final class LarkPosts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LarkPosts() {
    }

    /** 对齐 lark.ts:161-168，键序 = TS 字面量序。 */
    public static final Map<String, String> CONTENT_TYPE_EXTENSIONS = contentTypeExtensions();

    private static Map<String, String> contentTypeExtensions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("image/jpeg", "jpg");
        map.put("image/png", "png");
        map.put("image/gif", "gif");
        map.put("image/webp", "webp");
        map.put("image/bmp", "bmp");
        map.put("image/x-icon", "ico");
        return Collections.unmodifiableMap(map);
    }

    /**
     * 构造 @bot 任务的 post 消息 content。对齐 lark.ts:129-149。
     * boundedText 长度按 code point 计（M4 设计裁决 5）；正文元素带前导空格
     * ' ' + boundedText（M4 Review Focus 14）；user_name 仅在 target.name 非空时出现，
     * 且位于 user_id 之后；at 元素在 text 元素之前。
     */
    public static JsonNode buildMentionPostContent(BotIdentity target, String text) {
        String boundedText = FeishuText.fitFeishuText(text, FeishuText.FEISHU_MENTION_LIMIT);

        ObjectNode at = MAPPER.createObjectNode();
        at.put("tag", "at");
        at.put("user_id", target.openId());
        if (target.name() != null && !target.name().isEmpty()) { // TS ...(target.name ? {...} : {})
            at.put("user_name", target.name());
        }
        ObjectNode textElement = MAPPER.createObjectNode();
        textElement.put("tag", "text");
        textElement.put("text", " " + boundedText);

        ArrayNode paragraph = MAPPER.createArrayNode();
        paragraph.add(at);
        paragraph.add(textElement);
        ArrayNode content = MAPPER.createArrayNode();
        content.add(paragraph);

        ObjectNode zhCn = MAPPER.createObjectNode();
        zhCn.put("title", "");
        zhCn.set("content", content);

        ObjectNode root = MAPPER.createObjectNode();
        root.set("zh_cn", zhCn);
        return root;
    }

    /**
     * 由文件名/Content-Type 推断资源扩展名。对齐 lark.ts:178-188。
     * TS extname(fileName).slice(1).toLowerCase() → 取最后一个 '.' 后的子串再
     * lowercase（无 '.' → 空串，brief Step 2）；命中 /^[a-z0-9]{1,10}$/ 原样返回，
     * 否则取 contentType ';' 前段 trim + lowercase 查映射表，未知 mime 按 image→img、
     * file→bin 兜底。
     */
    public static String resourceExtension(String type, String fileName, String contentType) {
        String original = "";
        if (fileName != null && !fileName.isEmpty()) {
            int dot = fileName.lastIndexOf('.');
            original = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        if (original.matches("^[a-z0-9]{1,10}$")) {
            return original;
        }
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return CONTENT_TYPE_EXTENSIONS.getOrDefault(mime, "image".equals(type) ? "img" : "bin");
    }
}

package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 飞书 post 消息构造与资源扩展名推断。
 */
public final class LarkPosts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LarkPosts() {
    }

    /** content-type → 扩展名映射表。 */
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
     * @bot 的 post content。正文带一个前导空格；user_name 只在名字非空时出现，
     * 且必须放在 user_id 之后；at 在 text 之前——这是飞书 post 的既有格式，别调。
     */
    public static JsonNode buildMentionPostContent(BotIdentity target, String text) {
        String boundedText = FeishuText.fitFeishuText(text, FeishuText.FEISHU_MENTION_LIMIT);

        ObjectNode at = MAPPER.createObjectNode();
        at.put("tag", "at");
        at.put("user_id", target.openId());
        if (target.name() != null && !target.name().isEmpty()) {
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

    /** /^[a-z0-9]{1,10}$/（扩展名合法判定，预编译避免 String.matches 每次重编译）。 */
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("^[a-z0-9]{1,10}$");

    /**
     * 扩展名先取文件名最后一个 '.' 后的段（".hidden" 这种视同没有），小写后命中
     * /^[a-z0-9]{1,10}$/ 就直接用；不合法再查 content-type 映射，未知 mime 按 image→img、file→bin 兜底。
     */
    public static String resourceExtension(String type, String fileName, String contentType) {
        String original = "";
        if (fileName != null && !fileName.isEmpty()) {
            int dot = fileName.lastIndexOf('.');
            original = (dot < 0 || dot == 0)
                    ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        if (EXTENSION_PATTERN.matcher(original).matches()) {
            return original;
        }
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return CONTENT_TYPE_EXTENSIONS.getOrDefault(mime, "image".equals(type) ? "img" : "bin");
    }
}

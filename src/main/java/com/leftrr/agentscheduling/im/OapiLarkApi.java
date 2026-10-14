package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.leftrr.agentscheduling.dto.im.DownloadedResource;
import com.leftrr.agentscheduling.dto.im.LarkResponse;

/**
 * LarkApi 的 oapi-sdk 2.4.7 薄壳：全部走 client.post/patch/get(url, body,
 * AccessTokenType.Tenant)。不做单测，由 LarkProbe 冒烟验证。
 *
 * <p>URL 依据：im.v1 reply/patch、messageResource、drive.v1 replies 与 SDK jar
 * 内资源类常量逐一比对得出。subscribeDocumentComments 和 updateCommentReaction
 * 在 2.4.7 没有对应资源类，URL 照 &#64;larksuiteoapi/node-sdk 1.71.1 源码核对；
 * 后者是 <b>POST 不是 PUT</b>。</p>
 *
 * <p>三个真机踩过的坑：① bot/v3/info 无 data 信封（见 fetchBotInfo）；② OkHttpTransport
 * 对 body 无条件 Gson.toJson，String 会被二次编码（Bad Request）；③ SDK 的 parseInput
 * 对入参对象逐字段 setAccessible 找 @Body 注解——String/Map 都会碰 JDK 内部字段
 * （JDK 9+ 模块封锁）。故 body 一律经 gsonBody 包成 {@link GsonBody} 壳：
 * 反射只发生在自家类上，@Body 字段原样透传给 Gson。</p>
 */
public final class OapiLarkApi implements LarkApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.lark.oapi.Client client;

    public OapiLarkApi(com.lark.oapi.Client client) {
        this.client = client;
    }

    public OapiLarkApi(String appId, String appSecret) {
        this(com.lark.oapi.Client.newBuilder(appId, appSecret).build());
    }

    @Override
    public LarkResponse fetchBotInfo() {
        String url = "/open-apis/bot/v3/info";
        // bot/v3/info 是特殊 v3 接口：响应体没有 data 信封，bot 直接挂根节点，
        // 把根节点整体当 data 交给上层
        return call(url, () -> client.get(url, null, AccessTokenType.Tenant), true);
    }

    @Override
    public LarkResponse replyMessage(String messageId, String msgType, String contentJson, boolean replyInThread) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("msg_type", msgType);
        body.put("content", contentJson);
        if (replyInThread) {
            body.put("reply_in_thread", true);
        }
        String url = "/open-apis/im/v1/messages/" + messageId + "/reply";
        return call(url, () -> client.post(url, gsonBody(body), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse patchMessage(String messageId, String contentJson) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("content", contentJson);
        String url = "/open-apis/im/v1/messages/" + messageId;
        return call(url, () -> client.patch(url, gsonBody(body), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse subscribeDocumentComments() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("event_type", "drive.notice.comment_add_v1");
        String url = "/open-apis/drive/v1/user/subscription";
        return call(url, () -> client.post(url, gsonBody(body), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse createCommentReply(String fileToken, String commentId, String fileType, String contentJson) {
        // 这里的 content 是对象不是字符串，要先解析再包一层
        String url = "/open-apis/drive/v1/files/" + fileToken + "/comments/" + commentId + "/replies"
                + "?file_type=" + fileType + "&user_id_type=open_id";
        ObjectNode body = wrapContent(contentJson);
        return call(url, () -> client.post(url, gsonBody(body), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse listFirstCommentReplyId(String fileToken, String commentId, String fileType) {
        String url = "/open-apis/drive/v1/files/" + fileToken + "/comments/" + commentId + "/replies"
                + "?file_type=" + fileType + "&page_size=1&user_id_type=open_id";
        return call(url, () -> client.get(url, null, AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse updateCommentReaction(String fileToken, String fileType, String replyId, boolean add) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("action", add ? "add" : "delete");
        body.put("reply_id", replyId);
        body.put("reaction_type", TYPING_REACTION_TYPE);
        String url = "/open-apis/drive/v2/files/" + fileToken + "/comments/reaction?file_type=" + fileType;
        return call(url, () -> client.post(url, gsonBody(body), AccessTokenType.Tenant));
    }

    @Override
    public DownloadedResource getResource(String messageId, String fileKey, String type) {
        String url = "/open-apis/im/v1/messages/" + messageId + "/resources/" + fileKey + "?type=" + type;
        try {
            RawResponse response = client.get(url, null, AccessTokenType.Tenant);
            return new DownloadedResource(response.getBody(), response.getContentType());
        } catch (Exception e) {
            throw new LarkException("调用飞书接口失败: " + url, e);
        }
    }

    /** 请求体壳：Jackson 树转 Map/List 后包一层 @Body，绕开 SDK 反射与 String 二次编码（见类注释）。 */
    private static Object gsonBody(JsonNode node) {
        return new GsonBody(MAPPER.convertValue(node, Object.class));
    }

    /** parseInput 只反射这个类的字段（无 JDK 内部结构），@Body 字段值即真实请求体。 */
    private static final class GsonBody {
        @com.lark.oapi.core.annotation.Body
        final Object body;

        GsonBody(Object body) {
            this.body = body;
        }
    }

    private static ObjectNode wrapContent(String contentJson) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.set("content", MAPPER.readTree(contentJson));
            return body;
        } catch (Exception e) {
            throw new LarkException("评论回复 content 非法 JSON", e);
        }
    }

    private interface RawCall {
        RawResponse execute() throws Exception;
    }

    private LarkResponse call(String url, RawCall raw) {
        return call(url, raw, false);
    }

    private LarkResponse call(String url, RawCall raw, boolean rootAsData) {
        RawResponse response;
        try {
            response = raw.execute();
        } catch (Exception e) {
            throw new LarkException("调用飞书接口失败: " + url, e);
        }
        return toLarkResponse(response, rootAsData);
    }

    /** 飞书 REST 信封 {code, msg, data} → LarkResponse；code 缺失 → null，按成功处理。 */
    private static LarkResponse toLarkResponse(RawResponse response, boolean rootAsData) {
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            return new LarkResponse(null, null, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new LarkException("飞书响应体解析失败", e);
        }
        JsonNode codeNode = root.path("code");
        Integer code = codeNode.isNumber() ? codeNode.asInt() : null;
        JsonNode msgNode = root.path("msg");
        String msg = msgNode.isMissingNode() || msgNode.isNull() ? null : msgNode.asText();
        if (rootAsData) {
            return new LarkResponse(code, msg, root);
        }
        JsonNode dataNode = root.path("data");
        JsonNode data = dataNode.isMissingNode() || dataNode.isNull() ? null : dataNode;
        return new LarkResponse(code, msg, data);
    }
}

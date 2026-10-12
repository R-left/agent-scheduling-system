package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;

/**
 * LarkApi 的 oapi-sdk 2.4.7 raw 通道薄壳：client.post/patch/get/put(url, bodyJson,
 * AccessTokenType.Tenant) + RawResponse.getBody()。本类不写单元测试，
 * <b>LarkProbe 冒烟验证（需 .env 凭证，M7 端到端）</b>。
 *
 * <p>URL 依据（2026-10-13 核对，未做真实调用）：前四类与 Java SDK jar 内资源类
 * URL 常量逐一比对（unzip + javap 提取）——im.v1 reply(/messages/:id/reply)、
 * patch(/messages/:id)、messageResource(/messages/:id/resources/:file_key)、
 * drive.v1 replies(/drive/v1/files/:file_token/comments/:comment_id/replies)。
 *
 * <p>以下两条在 Java SDK 2.4.7 中无对应资源类，URL 依 TS 侧
 * &#64;larksuiteoapi/node-sdk 1.71.1 源码（本项目 TS 版所用版本）核对：
 * <ul>
 *   <li>subscribeDocumentComments → POST /open-apis/drive/v1/user/subscription
 *       （body {event_type}，无 path 参数，与 TS drive.v1.user.subscription 一致）</li>
 *   <li>updateCommentReaction → POST /open-apis/drive/v2/files/:file_token/comments/reaction?file_type=…
 *       （body {action, reply_id, reaction_type}；<b>POST 而非 PUT</b>，
 *       brief Step 2 曾推断 raw put + /comment_reactions，以 Node SDK 源码实测为准）</li>
 * </ul>
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
        return call(url, () -> client.get(url, null, AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse replyMessage(String messageId, String msgType, String contentJson, boolean replyInThread) {
        // TS data: {msg_type, content: JSON.stringify(…), reply_in_thread?}：content 为 JSON 字符串
        ObjectNode body = MAPPER.createObjectNode();
        body.put("msg_type", msgType);
        body.put("content", contentJson);
        if (replyInThread) {
            body.put("reply_in_thread", true);
        }
        String url = "/open-apis/im/v1/messages/" + messageId + "/reply";
        return call(url, () -> client.post(url, body.toString(), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse patchMessage(String messageId, String contentJson) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("content", contentJson);
        String url = "/open-apis/im/v1/messages/" + messageId;
        return call(url, () -> client.patch(url, body.toString(), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse subscribeDocumentComments() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("event_type", "drive.notice.comment_add_v1");
        String url = "/open-apis/drive/v1/user/subscription";
        return call(url, () -> client.post(url, body.toString(), AccessTokenType.Tenant));
    }

    @Override
    public LarkResponse createCommentReply(String fileToken, String commentId, String fileType, String contentJson) {
        // TS data: { content: {elements:[…]} }：content 是对象，此处解析 contentJson 后包裹
        String url = "/open-apis/drive/v1/files/" + fileToken + "/comments/" + commentId + "/replies"
                + "?file_type=" + fileType + "&user_id_type=open_id";
        return call(url, () -> client.post(url, wrapContent(contentJson), AccessTokenType.Tenant));
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
        return call(url, () -> client.post(url, body.toString(), AccessTokenType.Tenant));
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

    /** 评论回复 body：{content: <contentJson 解析结果>}（TS content 为对象）。 */
    private static String wrapContent(String contentJson) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.set("content", MAPPER.readTree(contentJson));
            return body.toString();
        } catch (Exception e) {
            throw new LarkException("评论回复 content 非法 JSON", e);
        }
    }

    private interface RawCall {
        RawResponse execute() throws Exception;
    }

    private LarkResponse call(String url, RawCall raw) {
        RawResponse response;
        try {
            response = raw.execute();
        } catch (Exception e) {
            throw new LarkException("调用飞书接口失败: " + url, e);
        }
        return toLarkResponse(response);
    }

    /** 飞书 REST 信封 {code, msg, data} → LarkResponse；code 缺失 → null（视为成功）。 */
    private static LarkResponse toLarkResponse(RawResponse response) {
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
        JsonNode dataNode = root.path("data");
        JsonNode data = dataNode.isMissingNode() || dataNode.isNull() ? null : dataNode;
        return new LarkResponse(code, msg, data);
    }
}

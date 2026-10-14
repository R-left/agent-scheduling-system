package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import com.leftrr.agentscheduling.dto.im.DownloadedResource;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.dto.im.LarkResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bot 门面的 LarkApi 实现（REST 部分），全部同步返回。
 */
public final class LarkBot implements Bot {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LarkApi api;

    public LarkBot(LarkApi api) {
        this.api = api;
    }

    @Override
    public BotIdentity getIdentity() {
        LarkResponse response = api.fetchBotInfo();
        JsonNode data = response.data();
        JsonNode bot = data == null ? null : data.path("bot");
        String openId = bot == null ? null : bot.path("open_id").asText(null);
        if (openId == null || openId.isEmpty()) {
            throw new LarkException("飞书没有返回 bot open_id");
        }
        JsonNode appNameNode = bot.path("app_name");
        String appName = appNameNode.isMissingNode() || appNameNode.isNull() ? null : appNameNode.asText();
        String name = appName == null || appName.trim().isEmpty() ? "Bot" : appName.trim();
        return new BotIdentity(openId, name);
    }

    @Override
    public String reply(String messageId, String text, boolean replyInThread) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("text", FeishuText.fitFeishuText(text, FeishuText.FEISHU_TEXT_LIMIT));
        LarkResponse response = api.replyMessage(messageId, "text", content.toString(), replyInThread);
        return messageIdOf(response);
    }

    @Override
    public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
        LarkResponse response = api.replyMessage(messageId, "interactive", card.toString(), replyInThread);
        return messageIdOf(response);
    }

    @Override
    public String replyMention(String messageId, BotIdentity target, String text, boolean replyInThread) {
        String content = LarkPosts.buildMentionPostContent(target, text).toString();
        LarkResponse response = api.replyMessage(messageId, "post", content, replyInThread);
        return messageIdOf(response);
    }

    /** 不检查返回 code，无返回值消费。 */
    @Override
    public void updateCard(String messageId, JsonNode card) {
        api.patchMessage(messageId, card.toString());
    }

    @Override
    public void subscribeToDocumentComments() {
        requireSuccess(api.subscribeDocumentComments(), "订阅飞书文档评论事件失败");
    }

    @Override
    public void replyToDocumentComment(IncomingDocumentComment comment, String text) {
        ObjectNode textRun = MAPPER.createObjectNode();
        textRun.put("text", FeishuText.fitFeishuText(text, FeishuText.FEISHU_COMMENT_LIMIT));
        ObjectNode element = MAPPER.createObjectNode();
        element.put("type", "text_run");
        element.set("text_run", textRun);
        ArrayNode elements = MAPPER.createArrayNode();
        elements.add(element);
        ObjectNode content = MAPPER.createObjectNode();
        content.set("elements", elements);

        LarkResponse response = api.createCommentReply(
                comment.fileToken(), comment.commentId(), comment.fileType(), content.toString());
        requireSuccess(response, "回复飞书文档评论失败");
    }

    @Override
    public void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active) {
        String replyId = comment.replyId();
        if (replyId == null || replyId.isEmpty()) {
            replyId = findRootCommentReplyId(comment);
        }
        LarkResponse response = api.updateCommentReaction(
                comment.fileToken(), comment.fileType(), replyId, active);
        requireSuccess(response, "更新飞书文档评论状态失败");
    }

    private String findRootCommentReplyId(IncomingDocumentComment comment) {
        LarkResponse response = api.listFirstCommentReplyId(
                comment.fileToken(), comment.commentId(), comment.fileType());
        requireSuccess(response, "读取飞书文档评论回复失败");
        JsonNode data = response.data();
        JsonNode items = data == null ? null : data.path("items");
        if (items != null && items.isArray() && !items.isEmpty()) {
            JsonNode replyIdNode = items.get(0).path("reply_id");
            if (!replyIdNode.isMissingNode() && !replyIdNode.isNull()) {
                String replyId = replyIdNode.asText();
                if (!replyId.isEmpty()) {
                    return replyId;
                }
            }
        }
        throw new LarkException("飞书文档评论缺少可添加表情的回复 ID");
    }

    @Override
    public String downloadResource(String messageId, String fileKey, String type,
                                   String saveDir, String fileName) throws IOException {
        DownloadedResource resource = api.getResource(messageId, fileKey, type);
        String extension = LarkPosts.resourceExtension(type, fileName, resource.contentType());
        Path savePath = Path.of(saveDir, fileKey + "." + extension);
        Files.createDirectories(savePath.getParent());
        Files.write(savePath, resource.body());
        return savePath.toString();
    }

    /** code 非 0 即失败；code 为 null 视成功（有的接口不回 code）。msg 空就用 defaultMsg。 */
    private void requireSuccess(LarkResponse response, String defaultMsg) {
        Integer code = response.code();
        if (code != null && code != 0) {
            String msg = response.msg();
            throw new LarkException(msg != null && !msg.isEmpty() ? msg : defaultMsg);
        }
    }

    /** data 或 message_id 缺失/null → null。 */
    private static String messageIdOf(LarkResponse response) {
        JsonNode data = response.data();
        if (data == null) {
            return null;
        }
        JsonNode messageId = data.path("message_id");
        if (messageId.isMissingNode() || messageId.isNull()) {
            return null;
        }
        return messageId.asText();
    }
}

package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.dto.im.CardAction;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.dto.im.IncomingMessage;

/**
 * 事件翻译层（纯静态）：飞书 v2 事件信封的 event 子树 →
 * 领域对象，字段缺失按 ""/false/null 回退。
 */
public final class EventTranslator {

    private EventTranslator() {
    }

    /**
     * 'im.message.receive_v1' 事件 → IncomingMessage。
     * text 不还原提及占位，原始串（含 @_user_N）在 rawContent 里。
     * mapper 参数现阶段没被直接用到，只是让调用方和翻译层共用同一个 ObjectMapper。
     */
    public static IncomingMessage toIncomingMessage(JsonNode event, ObjectMapper mapper) {
        JsonNode message = event.path("message");
        JsonNode sender = event.path("sender");
        String messageType = message.path("message_type").asText("");
        String content = message.path("content").asText("");
        return new IncomingMessage(
                message.path("message_id").asText(""),
                message.path("chat_id").asText(""),
                message.path("chat_type").asText(""),
                messageType,
                MessageContent.extractMessageText(messageType, content),
                message.path("root_id").asText(""),
                message.path("thread_id").asText(""),
                sender.path("sender_type").asText(""),
                sender.path("sender_id").path("open_id").asText(""),
                sender.path("sender_id").path("union_id").asText(""),
                MessageParser.parseMentions(message.path("mentions")),
                content);
    }

    /**
     * 'drive.notice.comment_add_v1' 事件 → IncomingDocumentComment。
     * file_token/file_type/comment_id 任一为空即返回 null，调用方跳过。
     */
    public static IncomingDocumentComment toDocumentComment(JsonNode event) {
        JsonNode meta = event.path("notice_meta");
        String fileToken = meta.path("file_token").asText("");
        if (fileToken.isEmpty()) {
            return null;
        }
        String fileType = meta.path("file_type").asText("");
        if (fileType.isEmpty()) {
            return null;
        }
        String commentId = event.path("comment_id").asText("");
        if (commentId.isEmpty()) {
            return null;
        }
        return new IncomingDocumentComment(
                event.path("event_id").asText(""),
                fileToken,
                fileType,
                commentId,
                event.path("reply_id").asText(""),
                meta.path("from_user_id").path("open_id").asText(""),
                meta.path("from_user_id").path("union_id").asText(""),
                event.path("is_mentioned").asBoolean(false));
    }

    /** 直接委托 CardActions.parseCardAction。 */
    public static CardAction toCardAction(JsonNode data) {
        return CardActions.parseCardAction(data);
    }
}

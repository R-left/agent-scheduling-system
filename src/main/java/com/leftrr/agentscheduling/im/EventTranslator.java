package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 事件翻译层（纯静态）：飞书 v2 事件信封的 event 子树（即 TS 回调里的 data）→
 * 领域对象。对齐 TS 版 src/im/lark.ts:364-399 的三个 dispatcher 回调体，
 * 字段缺失回退（""/false/null）逐点对齐 TS 实际行为。
 */
public final class EventTranslator {

    private EventTranslator() {
    }

    /**
     * 对齐 lark.ts:364-380 的 'im.message.receive_v1' 回调体。
     * text = extractMessageText(message_type, content)（不还原提及，对齐 TS lark.ts:205-222）；
     * rawContent 为原始 content 串（含 @_user_N 占位）。
     *
     * <p>{@code mapper} 为 brief 接口规格保留的共享参数：供调用方（FeishuBots）
     * 与翻译层共用同一 ObjectMapper；当前翻译路径经 MessageContent/MessageParser
     * 静态工具完成解析，本参数未被直接消费。
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
                message.path("root_id").asText(""),            // TS m.root_id ?? ''
                message.path("thread_id").asText(""),          // TS m.thread_id ?? ''
                sender.path("sender_type").asText(""),         // TS data.sender.sender_type ?? ''
                sender.path("sender_id").path("open_id").asText(""),
                sender.path("sender_id").path("union_id").asText(""),
                MessageParser.parseMentions(message.path("mentions")),
                content);
    }

    /**
     * 对齐 lark.ts:382-399 的 'drive.notice.comment_add_v1' 回调体。
     * TS 任一前置缺失（file_token/file_type/comment_id 非真值）→ return 跳过，
     * 这里返回 null 表示跳过；空串与缺失同判（TS truthy）。
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
                event.path("event_id").asText(""),        // TS data.event_id ?? ''
                fileToken,
                fileType,
                commentId,
                event.path("reply_id").asText(""),        // TS data.reply_id ?? ''
                meta.path("from_user_id").path("open_id").asText(""),
                meta.path("from_user_id").path("union_id").asText(""),
                event.path("is_mentioned").asBoolean(false)); // TS data.is_mentioned ?? false
    }

    /** 委托 CardActions.parseCardAction（对齐 lark.ts:79-89，含数组通过语义）。 */
    public static CardAction toCardAction(JsonNode data) {
        return CardActions.parseCardAction(data);
    }
}

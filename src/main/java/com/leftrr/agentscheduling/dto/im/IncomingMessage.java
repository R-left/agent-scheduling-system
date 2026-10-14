package com.leftrr.agentscheduling.dto.im;

import java.util.List;

/**
 * 飞书收到的消息的结构化表示。
 * messageId/chatId/rootId/threadId 与 dto.core.TopicAddress 一一对应。
 */
public record IncomingMessage(String messageId, String chatId, String chatType,
                              String messageType, String text,
                              String rootId, String threadId,
                              String senderType, String senderOpenId, String senderUnionId,
                              List<Mention> mentions, String rawContent) {

    public IncomingMessage {
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
    }
}

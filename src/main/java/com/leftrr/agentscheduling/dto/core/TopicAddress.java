package com.leftrr.agentscheduling.dto.core;

/** 飞书消息地址。 */
public record TopicAddress(
        String messageId,
        String chatId,
        String threadId,
        String rootId) {
}

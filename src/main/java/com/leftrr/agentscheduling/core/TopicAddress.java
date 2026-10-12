package com.leftrr.agentscheduling.core;

/** 飞书消息地址。对应 TS 版 src/core/topic-task.ts 的 TopicAddress。 */
public record TopicAddress(
        String messageId,
        String chatId,
        String threadId,
        String rootId) {
}

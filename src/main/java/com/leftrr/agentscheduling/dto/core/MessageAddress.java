package com.leftrr.agentscheduling.dto.core;

/** 飞书消息地址。 */
public record MessageAddress(String messageId, String chatId, String threadId, String rootId) {}

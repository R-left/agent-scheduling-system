package com.leftrr.agentscheduling.core;

/**
 * 飞书消息地址。对应 TS 版 src/core/session-manager.ts 的 MessageAddress 接口。
 */
public record MessageAddress(String messageId, String chatId, String threadId, String rootId) {}

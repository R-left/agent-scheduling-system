package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * 业务侧 Bot 门面。对齐 TS 版 src/im/lark.ts:91-127 的 Bot 接口（Java 面，
 * 全部同步返回；设计裁决 9b：不含 TS 的 client 属性）。
 *
 * TS 可选参数（replyInThread 默认 false）→ Java 两参默认重载，委托全参方法。
 */
public interface Bot {

    /** TS fetchBotIdentity（lark.ts:151-159）。 */
    BotIdentity getIdentity();

    /** TS reply（lark.ts:242-254），返回 data.message_id，缺失 → null。 */
    String reply(String messageId, String text, boolean replyInThread);

    default String reply(String messageId, String text) {
        return reply(messageId, text, false);
    }

    /** TS replyCard（lark.ts:256-266），card 为卡片 JSON（内部序列化）。 */
    String replyCard(String messageId, JsonNode card, boolean replyInThread);

    default String replyCard(String messageId, JsonNode card) {
        return replyCard(messageId, card, false);
    }

    /** TS replyMention（lark.ts:268-278），content 由 buildMentionPostContent 构造。 */
    String replyMention(String messageId, BotIdentity target, String text, boolean replyInThread);

    default String replyMention(String messageId, BotIdentity target, String text) {
        return replyMention(messageId, target, text, false);
    }

    /** TS updateCard（lark.ts:280-285）。 */
    void updateCard(String messageId, JsonNode card);

    /** TS subscribeToDocumentComments（lark.ts:287-294）。 */
    void subscribeToDocumentComments();

    /** TS replyToDocumentComment（lark.ts:296-326）。 */
    void replyToDocumentComment(IncomingDocumentComment comment, String text);

    /** TS setDocumentCommentWorking（lark.ts:328-343）。 */
    void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active);

    /**
     * TS downloadResource（lark.ts:345-356），返回落盘路径字符串。
     * TS Promise reject 的 IO 失败 → Java 受检 IOException。
     */
    String downloadResource(String messageId, String fileKey, String type,
                            String saveDir, String fileName) throws IOException;
}

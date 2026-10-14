package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;
import java.io.IOException;

/**
 * 业务侧 Bot 门面，全部同步返回。
 * replyInThread 提供默认 false 的两参重载，委托全参方法。
 */
public interface Bot {

    /** 获取 bot 身份（open_id 与显示名）。 */
    BotIdentity getIdentity();

    /** 回复文本消息，返回 data.message_id，缺失 → null。 */
    String reply(String messageId, String text, boolean replyInThread);

    default String reply(String messageId, String text) {
        return reply(messageId, text, false);
    }

    /** 回复卡片消息，card 为卡片 JSON（内部序列化）。 */
    String replyCard(String messageId, JsonNode card, boolean replyInThread);

    default String replyCard(String messageId, JsonNode card) {
        return replyCard(messageId, card, false);
    }

    /** 回复 @提及消息，content 由 buildMentionPostContent 构造。 */
    String replyMention(String messageId, BotIdentity target, String text, boolean replyInThread);

    default String replyMention(String messageId, BotIdentity target, String text) {
        return replyMention(messageId, target, text, false);
    }

    /** 更新消息卡片。 */
    void updateCard(String messageId, JsonNode card);

    /** 订阅文档评论事件。 */
    void subscribeToDocumentComments();

    /** 回复文档评论。 */
    void replyToDocumentComment(IncomingDocumentComment comment, String text);

    /** 设置/取消文档评论的「处理中」表情回应。 */
    void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active);

    /** 下载消息资源到 saveDir，返回落盘路径字符串。 */
    String downloadResource(String messageId, String fileKey, String type,
                            String saveDir, String fileName) throws IOException;
}

package com.leftrr.agentscheduling.im;

import com.leftrr.agentscheduling.dto.im.CardAction;
import com.leftrr.agentscheduling.dto.im.CardActionResponse;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.dto.im.IncomingMessage;

/**
 * bot 启动选项（appId/appSecret + 三个回调）。
 * onMessage 里用 TopicTask.topicTaskId 定位会话；卡片回调在 onCardAction 里
 * 按 value.action 分流（abort_task / answer_clarification / resume_cli_session /
 * approve_product_spec），value 保持原始 JsonNode。
 */
public record BotOptions(String appId, String appSecret,
                         MessageListener onMessage,
                         CardActionListener onCardAction,
                         DocumentCommentListener onDocumentComment) {

    /** 消息监听回调。 */
    @FunctionalInterface
    public interface MessageListener {
        void onMessage(IncomingMessage msg, Bot bot);
    }

    /** 卡片动作回调，可 null。 */
    @FunctionalInterface
    public interface CardActionListener {
        CardActionResponse onCardAction(CardAction action);
    }

    /** 文档评论回调，可 null。 */
    @FunctionalInterface
    public interface DocumentCommentListener {
        void onDocumentComment(IncomingDocumentComment comment, Bot bot);
    }
}

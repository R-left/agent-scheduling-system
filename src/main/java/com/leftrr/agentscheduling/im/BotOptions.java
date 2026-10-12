package com.leftrr.agentscheduling.im;

/**
 * 对齐 TS 版 src/im/lark.ts:36-47 的 BotOptions（appId/appSecret + 三个回调）。
 * onCardAction/onDocumentComment 对齐 TS 可选回调，可为 null。
 *
 * <p><b>M6 留缝收口</b>：{@code onMessage} 内 M6 将 (a) {@code new TopicAddress(msg.messageId(),
 * msg.chatId(), msg.threadId(), msg.rootId())} → {@code TopicTask.topicTaskId} 定位会话；
 * (b) 协作/澄清/产品流程的卡片回调 value 分发（{@code action: abort_task / answer_clarification /
 * resume_cli_session / approve_product_spec}）在 {@code onCardAction} 内按 {@code value.action}
 * 分流——M4 不实现分流，仅保证 {@code CardAction.value} 为原始 {@code JsonNode}。
 */
public record BotOptions(String appId, String appSecret,
                         MessageListener onMessage,
                         CardActionListener onCardAction,
                         DocumentCommentListener onDocumentComment) {

    /** TS onMessage: (msg, bot) => Promise&lt;void&gt; → 同步回调。 */
    @FunctionalInterface
    public interface MessageListener {
        void onMessage(IncomingMessage msg, Bot bot);
    }

    /** TS onCardAction?: (action) => Promise&lt;CardActionResponse | undefined&gt;，可 null。 */
    @FunctionalInterface
    public interface CardActionListener {
        CardActionResponse onCardAction(CardAction action);
    }

    /** TS onDocumentComment?: (comment, bot) => Promise&lt;void&gt;，可 null。 */
    @FunctionalInterface
    public interface DocumentCommentListener {
        void onDocumentComment(IncomingDocumentComment comment, Bot bot);
    }
}

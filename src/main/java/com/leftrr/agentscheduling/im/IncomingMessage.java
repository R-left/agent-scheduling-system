package com.leftrr.agentscheduling.im;

import java.util.List;

/**
 * 对齐 TS 版 src/im/lark.ts:10-23 的 IncomingMessage。
 * 分量顺序 = TS lark.ts:366-379 赋值序（brief 用例 8 黄金断言钉住）；
 * messageId/chatId/rootId/threadId 与 core.TopicAddress 一一对应（设计裁决 9a）。
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

package com.leftrr.agentscheduling.im;

/**
 * 对齐 TS 版 src/im/lark.ts:25-34 的 IncomingDocumentComment
 * （drive.notice.comment_add_v1 事件解析结果，由 M4 Task 10 的 WS 事件层构造）。
 * replyId 为 null/空串表示 TS 空串（根评论无回复 ID）。
 */
public record IncomingDocumentComment(String eventId, String fileToken, String fileType,
                                      String commentId, String replyId,
                                      String senderOpenId, String senderUnionId,
                                      boolean mentionedBot) {
}

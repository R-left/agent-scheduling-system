package com.leftrr.agentscheduling.dto.im;

/**
 * drive.notice.comment_add_v1 事件解析结果（由 WS 事件层构造）。
 * replyId 为 null/空串表示根评论无回复 ID。
 */
public record IncomingDocumentComment(String eventId, String fileToken, String fileType,
                                      String commentId, String replyId,
                                      String senderOpenId, String senderUnionId,
                                      boolean mentionedBot) {
}

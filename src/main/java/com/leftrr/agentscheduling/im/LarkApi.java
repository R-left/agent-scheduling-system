package com.leftrr.agentscheduling.im;

import com.leftrr.agentscheduling.dto.im.DownloadedResource;
import com.leftrr.agentscheduling.dto.im.LarkResponse;

/**
 * 飞书 REST 调用窄接口，全部同步返回。
 * 实现方：OapiLarkApi（SDK 薄壳）。
 */
public interface LarkApi {

    /** GET /open-apis/bot/v3/info，获取 bot 信息。 */
    LarkResponse fetchBotInfo();

    /** 回复消息（各类回复共用）；contentJson 为业务内容串（包裹形态由实现方决定）。 */
    LarkResponse replyMessage(String messageId, String msgType, String contentJson, boolean replyInThread);

    /** 更新消息卡片。 */
    LarkResponse patchMessage(String messageId, String contentJson);

    /** 订阅文档评论事件，event_type: 'drive.notice.comment_add_v1'。 */
    LarkResponse subscribeDocumentComments();

    /** 创建文档评论回复（params 含 file_type 与 user_id_type: open_id）。 */
    LarkResponse createCommentReply(String fileToken, String commentId, String fileType, String contentJson);

    /** 查询某评论的第一条回复（page_size=1、user_id_type: open_id），用于定位根回复。 */
    LarkResponse listFirstCommentReplyId(String fileToken, String commentId, String fileType);

    /** 评论表情回复增删；add=true → action 'add'，false → 'delete'。 */
    LarkResponse updateCommentReaction(String fileToken, String fileType, String replyId, boolean add);

    /** 下载消息资源，返回响应体字节 + content-type。 */
    DownloadedResource getResource(String messageId, String fileKey, String type);

    /** 表情回复固定 reaction_type，由 OapiLarkApi 拼请求体。 */
    String TYPING_REACTION_TYPE = "Typing";
}

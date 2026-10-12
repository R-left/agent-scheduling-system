package com.leftrr.agentscheduling.im;

/**
 * 飞书 REST 调用窄接口。对齐 TS 版 src/im/lark.ts:238-356 的 client 调用面
 * （设计裁决 9b：不含 TS Bot.client 属性），全部同步返回。
 * 实现方：OapiLarkApi（SDK 薄壳）；测试用 FakeLarkApi 记录入参。
 */
public interface LarkApi {

    /** TS lark.ts:152-155，GET /open-apis/bot/v3/info。 */
    LarkResponse fetchBotInfo();

    /** TS lark.ts:242-278 三类回复共用；contentJson 为业务内容串（包裹形态由实现方决定）。 */
    LarkResponse replyMessage(String messageId, String msgType, String contentJson, boolean replyInThread);

    /** TS lark.ts:280-285 卡片更新。 */
    LarkResponse patchMessage(String messageId, String contentJson);

    /** TS lark.ts:287-294，event_type: 'drive.notice.comment_add_v1'。 */
    LarkResponse subscribeDocumentComments();

    /** TS lark.ts:296-326 评论回复创建（params 含 file_type 与 user_id_type: open_id）。 */
    LarkResponse createCommentReply(String fileToken, String commentId, String fileType, String contentJson);

    /** TS lark.ts:408-435 findRootCommentReplyId（page_size=1、user_id_type: open_id）。 */
    LarkResponse listFirstCommentReplyId(String fileToken, String commentId, String fileType);

    /** TS lark.ts:328-343 updateReaction；add=true → action 'add'，false → 'delete'。 */
    LarkResponse updateCommentReaction(String fileToken, String fileType, String replyId, boolean add);

    /** TS lark.ts:345-356 messageResource.get 的响应体字节 + content-type。 */
    DownloadedResource getResource(String messageId, String fileKey, String type);

    /** 表情回复固定 reaction_type（TS lark.ts:337 'Typing'），由 OapiLarkApi 拼请求体。 */
    String TYPING_REACTION_TYPE = "Typing";
}

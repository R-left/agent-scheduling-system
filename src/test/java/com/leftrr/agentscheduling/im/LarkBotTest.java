package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 Task 9 brief Step 1：LarkBot 22 条用例（用例编号 1-22 与 brief 一一对应）。
 * FakeLarkApi 记录调用入参、可编程返回；不触达真实飞书（OapiLarkApi 骨架不单元测试，
 * LarkProbe 冒烟验证留给 M7）。
 *
 * Review Focus 4：truthy 判定 code != null && code != 0 —— code null 视为成功。
 */
class LarkBotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记录入参、可编程返回的 LarkApi 测试替身。 */
    private static final class FakeLarkApi implements LarkApi {

        LarkResponse fetchBotInfoResponse;

        record ReplyCall(String messageId, String msgType, String contentJson, boolean replyInThread) {}

        final List<ReplyCall> replyCalls = new ArrayList<>();
        LarkResponse nextReplyResponse;

        record PatchCall(String messageId, String contentJson) {}

        final List<PatchCall> patchCalls = new ArrayList<>();
        LarkResponse nextPatchResponse;

        int subscribeCalls;
        LarkResponse nextSubscribeResponse;

        record CreateCommentReplyCall(String fileToken, String commentId, String fileType, String contentJson) {}

        final List<CreateCommentReplyCall> createCommentReplyCalls = new ArrayList<>();
        LarkResponse nextCreateCommentReplyResponse;

        record ListRepliesCall(String fileToken, String commentId, String fileType) {}

        final List<ListRepliesCall> listRepliesCalls = new ArrayList<>();
        LarkResponse nextListRepliesResponse;

        record UpdateReactionCall(String fileToken, String fileType, String replyId, boolean add) {}

        final List<UpdateReactionCall> updateReactionCalls = new ArrayList<>();
        LarkResponse nextUpdateReactionResponse;

        record GetResourceCall(String messageId, String fileKey, String type) {}

        final List<GetResourceCall> getResourceCalls = new ArrayList<>();
        DownloadedResource nextResource;

        @Override
        public LarkResponse fetchBotInfo() {
            return fetchBotInfoResponse;
        }

        @Override
        public LarkResponse replyMessage(String messageId, String msgType, String contentJson, boolean replyInThread) {
            replyCalls.add(new ReplyCall(messageId, msgType, contentJson, replyInThread));
            return nextReplyResponse;
        }

        @Override
        public LarkResponse patchMessage(String messageId, String contentJson) {
            patchCalls.add(new PatchCall(messageId, contentJson));
            return nextPatchResponse;
        }

        @Override
        public LarkResponse subscribeDocumentComments() {
            subscribeCalls++;
            return nextSubscribeResponse;
        }

        @Override
        public LarkResponse createCommentReply(String fileToken, String commentId, String fileType, String contentJson) {
            createCommentReplyCalls.add(new CreateCommentReplyCall(fileToken, commentId, fileType, contentJson));
            return nextCreateCommentReplyResponse;
        }

        @Override
        public LarkResponse listFirstCommentReplyId(String fileToken, String commentId, String fileType) {
            listRepliesCalls.add(new ListRepliesCall(fileToken, commentId, fileType));
            return nextListRepliesResponse;
        }

        @Override
        public LarkResponse updateCommentReaction(String fileToken, String fileType, String replyId, boolean add) {
            updateReactionCalls.add(new UpdateReactionCall(fileToken, fileType, replyId, add));
            return nextUpdateReactionResponse;
        }

        @Override
        public DownloadedResource getResource(String messageId, String fileKey, String type) {
            getResourceCalls.add(new GetResourceCall(messageId, fileKey, type));
            return nextResource;
        }
    }

    private final FakeLarkApi api = new FakeLarkApi();
    private final LarkBot bot = new LarkBot(api);

    /** 构造 LarkResponse；dataJson 为 null 时 data 为 null（TS data 缺失）。 */
    private static LarkResponse response(Integer code, String msg, String dataJson) {
        if (dataJson == null) {
            return new LarkResponse(code, msg, null);
        }
        try {
            return new LarkResponse(code, msg, MAPPER.readTree(dataJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static IncomingDocumentComment comment(String replyId) {
        return new IncomingDocumentComment("ev_1", "file_tok", "docx", "cm_1", replyId,
                "ou_sender", "un_sender", true);
    }

    // ---- 用例 1-3：getIdentity（lark.ts:151-159） ----

    @Test
    void case01_getIdentity_returnsOpenIdAndTrimmedAppName() {
        api.fetchBotInfoResponse = response(0, null,
                "{\"bot\":{\"open_id\":\"ou_123\",\"app_name\":\"  CEO 助手  \"}}");
        BotIdentity identity = bot.getIdentity();
        assertEquals(new BotIdentity("ou_123", "CEO 助手"), identity);
    }

    @Test
    void case02_getIdentity_blankOrMissingAppNameFallsBackToBot() {
        api.fetchBotInfoResponse = response(0, null,
                "{\"bot\":{\"open_id\":\"ou_123\",\"app_name\":\"   \"}}");
        assertEquals("Bot", bot.getIdentity().name());

        api.fetchBotInfoResponse = response(0, null, "{\"bot\":{\"open_id\":\"ou_123\"}}");
        assertEquals("Bot", bot.getIdentity().name());
    }

    @Test
    void case03_getIdentity_missingOpenIdThrows() {
        api.fetchBotInfoResponse = response(0, null, "{\"bot\":{\"app_name\":\"x\"}}");
        LarkException e = assertThrows(LarkException.class, bot::getIdentity);
        assertEquals("飞书没有返回 bot open_id", e.getMessage());

        // bot 节点整个缺失同样抛出
        api.fetchBotInfoResponse = response(0, null, "{}");
        assertThrows(LarkException.class, bot::getIdentity);
    }

    // ---- 用例 4-5：reply（lark.ts:242-254） ----

    @Test
    void case04_reply_sendsTextContentWithLimitAndReturnsMessageId() throws Exception {
        String longText = "鱼".repeat(3100);
        api.nextReplyResponse = response(0, null, "{\"message_id\":\"om_1\"}");

        String messageId = bot.reply("om_old", longText, false);

        assertEquals("om_1", messageId);
        assertEquals(1, api.replyCalls.size());
        FakeLarkApi.ReplyCall call = api.replyCalls.get(0);
        assertEquals("om_old", call.messageId());
        assertEquals("text", call.msgType());
        // replyInThread=false：LarkApi 层为布尔参数，OapiLarkApi 适配器据 false 不拼 reply_in_thread 键
        assertFalse(call.replyInThread());
        JsonNode content = MAPPER.readTree(call.contentJson());
        assertEquals(3000, content.path("text").asText().codePointCount(0, content.path("text").asText().length()));
        assertTrue(content.path("text").asText().endsWith(FeishuText.TRUNCATE_SUFFIX));
    }

    @Test
    void case05_reply_replyInThreadTrueAndMissingMessageIdReturnsNull() {
        api.nextReplyResponse = response(0, null, "{}");
        assertNull(bot.reply("om_old", "hello", true));
        assertTrue(api.replyCalls.get(0).replyInThread());
    }

    // ---- 用例 6-7：replyCard / replyMention（lark.ts:256-278） ----

    @Test
    void case06_replyCard_sendsInteractiveCardJson() {
        com.fasterxml.jackson.databind.node.ObjectNode card = MAPPER.createObjectNode();
        card.put("id", "card_1");
        api.nextReplyResponse = response(0, null, "{\"message_id\":\"om_2\"}");

        String messageId = bot.replyCard("om_old", card, true);

        assertEquals("om_2", messageId);
        FakeLarkApi.ReplyCall call = api.replyCalls.get(0);
        assertEquals("interactive", call.msgType());
        assertEquals(card.toString(), call.contentJson());
        assertTrue(call.replyInThread());
    }

    @Test
    void case07_replyMention_sendsPostContentFromBuildMentionPostContent() {
        BotIdentity target = new BotIdentity("ou_t", "小助手");
        api.nextReplyResponse = response(0, null, "{\"message_id\":\"om_3\"}");

        String messageId = bot.replyMention("om_old", target, "你好", false);

        assertEquals("om_3", messageId);
        FakeLarkApi.ReplyCall call = api.replyCalls.get(0);
        assertEquals("post", call.msgType());
        assertEquals(LarkPosts.buildMentionPostContent(target, "你好").toString(), call.contentJson());
        assertFalse(call.replyInThread());
    }

    // ---- 用例 8：updateCard（lark.ts:280-285） ----

    @Test
    void case08_updateCard_patchesMessageWithCardContent() {
        com.fasterxml.jackson.databind.node.ObjectNode card = MAPPER.createObjectNode();
        card.put("id", "card_2");

        bot.updateCard("om_4", card);

        assertEquals(1, api.patchCalls.size());
        assertEquals("om_4", api.patchCalls.get(0).messageId());
        assertEquals(card.toString(), api.patchCalls.get(0).contentJson());
        // 返回值无消费：patch 响应可为 null，不抛
        api.nextPatchResponse = null;
        bot.updateCard("om_4", card);
    }

    // ---- 用例 9-10：subscribeToDocumentComments（lark.ts:287-294） ----

    @Test
    void case09_subscribe_codeZeroAndNullBothSucceed() {
        api.nextSubscribeResponse = response(0, null, null);
        bot.subscribeToDocumentComments();

        api.nextSubscribeResponse = response(null, null, null); // code 缺失 → truthy 判定视为成功
        bot.subscribeToDocumentComments();

        assertEquals(2, api.subscribeCalls);
    }

    @Test
    void case10_subscribe_failureThrowsMsgOrDefault() {
        api.nextSubscribeResponse = response(1, " boom", null);
        LarkException e = assertThrows(LarkException.class, bot::subscribeToDocumentComments);
        assertEquals(" boom", e.getMessage());

        api.nextSubscribeResponse = response(1, "", null);
        e = assertThrows(LarkException.class, bot::subscribeToDocumentComments);
        assertEquals("订阅飞书文档评论事件失败", e.getMessage());
    }

    // ---- 用例 11-12：replyToDocumentComment（lark.ts:296-326） ----

    @Test
    void case11_replyToDocumentComment_sendsTextRunWithCommentLimit() throws Exception {
        api.nextCreateCommentReplyResponse = response(0, null, null);
        String longText = "评".repeat(1100);

        bot.replyToDocumentComment(comment("or_1"), longText);

        assertEquals(1, api.createCommentReplyCalls.size());
        FakeLarkApi.CreateCommentReplyCall call = api.createCommentReplyCalls.get(0);
        assertEquals("file_tok", call.fileToken());
        assertEquals("cm_1", call.commentId());
        assertEquals("docx", call.fileType());
        JsonNode content = MAPPER.readTree(call.contentJson());
        JsonNode textRun = content.path("elements").get(0).path("text_run");
        assertEquals("text_run", content.path("elements").get(0).path("type").asText());
        String text = textRun.path("text").asText();
        assertEquals(1000, text.codePointCount(0, text.length()));
    }

    @Test
    void case12_replyToDocumentComment_failureThrowsDefaultMsg() {
        api.nextCreateCommentReplyResponse = response(1, "", null);
        LarkException e = assertThrows(LarkException.class,
                () -> bot.replyToDocumentComment(comment("or_1"), "text"));
        assertEquals("回复飞书文档评论失败", e.getMessage());
    }

    // ---- 用例 13-16：setDocumentCommentWorking（lark.ts:328-343 + 408-435） ----

    @Test
    void case13_setWorking_existingReplyIdUsedDirectlyWithoutList() {
        api.nextUpdateReactionResponse = response(0, null, null);

        bot.setDocumentCommentWorking(comment("or_9"), true);

        assertTrue(api.listRepliesCalls.isEmpty());
        assertEquals(1, api.updateReactionCalls.size());
        FakeLarkApi.UpdateReactionCall call = api.updateReactionCalls.get(0);
        assertEquals("or_9", call.replyId());
        assertTrue(call.add());
    }

    @Test
    void case14_setWorking_emptyReplyIdFallsBackToList() {
        api.nextListRepliesResponse = response(0, null, "{\"items\":[{\"reply_id\":\"or_root\"}]}");
        api.nextUpdateReactionResponse = response(0, null, null);

        bot.setDocumentCommentWorking(comment(""), true);

        assertEquals(1, api.listRepliesCalls.size());
        FakeLarkApi.ListRepliesCall listCall = api.listRepliesCalls.get(0);
        assertEquals("file_tok", listCall.fileToken());
        assertEquals("cm_1", listCall.commentId());
        assertEquals("docx", listCall.fileType());
        assertEquals(1, api.updateReactionCalls.size());
        assertEquals("or_root", api.updateReactionCalls.get(0).replyId());

        // items 为空 / reply_id 缺失 → 抛出且不再调 update
        api.nextListRepliesResponse = response(0, null, "{\"items\":[]}");
        api.nextUpdateReactionResponse = null;
        LarkException e = assertThrows(LarkException.class, () -> bot.setDocumentCommentWorking(comment(""), true));
        assertEquals("飞书文档评论缺少可添加表情的回复 ID", e.getMessage());
        assertEquals(1, api.updateReactionCalls.size());

        api.nextListRepliesResponse = response(0, null, "{\"items\":[{\"other\":\"x\"}]}");
        e = assertThrows(LarkException.class, () -> bot.setDocumentCommentWorking(comment(""), true));
        assertEquals("飞书文档评论缺少可添加表情的回复 ID", e.getMessage());
    }

    @Test
    void case15_setWorking_activeFlagMapsToAddAndReactionTypeIsTyping() {
        api.nextUpdateReactionResponse = response(0, null, null);

        bot.setDocumentCommentWorking(comment("or_1"), true);
        assertTrue(api.updateReactionCalls.get(0).add());

        bot.setDocumentCommentWorking(comment("or_1"), false);
        assertFalse(api.updateReactionCalls.get(1).add());

        // reaction_type 固定 "Typing"，由适配层（OapiLarkApi）取 LarkApi 常量拼请求体
        assertEquals("Typing", LarkApi.TYPING_REACTION_TYPE);
    }

    @Test
    void case16_setWorking_updateFailureThrowsDefaultMsg() {
        api.nextUpdateReactionResponse = response(1, "", null);
        LarkException e = assertThrows(LarkException.class,
                () -> bot.setDocumentCommentWorking(comment("or_1"), true));
        assertEquals("更新飞书文档评论状态失败", e.getMessage());
    }

    // ---- 用例 17-20：downloadResource（lark.ts:345-356，@TempDir） ----

    @Test
    void case17_downloadResource_pngExtensionAndMkdirRecursive(@TempDir Path tempDir) throws Exception {
        byte[] body = "png-bytes".getBytes(StandardCharsets.UTF_8);
        api.nextResource = new DownloadedResource(body, "image/png");
        Path saveDir = tempDir.resolve("nested/sub"); // 目录不存在 → 递归创建

        String saved = bot.downloadResource("om_5", "fk123", "image", saveDir.toString(), null);

        Path expected = saveDir.resolve("fk123.png");
        assertEquals(expected.toString(), saved);
        assertArrayEquals(body, Files.readAllBytes(expected));
    }

    @Test
    void case18_downloadResource_unknownContentTypeFallsBackByType(@TempDir Path tempDir) throws Exception {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);

        api.nextResource = new DownloadedResource(body, "weird/stuff");
        String savedImage = bot.downloadResource("om_5", "fk_img", "image", tempDir.toString(), null);
        assertTrue(savedImage.endsWith("fk_img.img"));

        api.nextResource = new DownloadedResource(body, "weird/stuff");
        String savedFile = bot.downloadResource("om_5", "fk_file", "file", tempDir.toString(), null);
        assertTrue(savedFile.endsWith("fk_file.bin"));
    }

    @Test
    void case19_downloadResource_returnsAbsolutePathWithBodyBytes(@TempDir Path tempDir) throws Exception {
        byte[] body = new byte[]{0x00, 0x01, (byte) 0xFF};
        api.nextResource = new DownloadedResource(body, "weird/stuff");

        String saved = bot.downloadResource("om_5", "fk_bin", "file", tempDir.toString(), null);

        Path path = Path.of(saved);
        assertTrue(path.isAbsolute());
        assertArrayEquals(body, Files.readAllBytes(path));
        // 入参记录
        assertEquals(1, api.getResourceCalls.size());
        assertEquals(new FakeLarkApi.GetResourceCall("om_5", "fk_bin", "file"), api.getResourceCalls.get(0));
    }

    @Test
    void case20_downloadResource_explicitFileNameExtensionWins(@TempDir Path tempDir) throws Exception {
        api.nextResource = new DownloadedResource("pdf".getBytes(StandardCharsets.UTF_8), "image/png");

        String saved = bot.downloadResource("om_5", "fk_doc", "file", tempDir.toString(), "报告.PDF");

        assertTrue(saved.endsWith("fk_doc.pdf"));
        assertTrue(Files.exists(Path.of(saved)));
    }

    // ---- 用例 21-22：requireSuccess（Review Focus 4） ----

    @Test
    void case21_requireSuccess_codeZeroOrNullPassOthersThrow() {
        api.nextSubscribeResponse = response(0, null, null);
        bot.subscribeToDocumentComments();

        api.nextSubscribeResponse = response(null, null, null);
        bot.subscribeToDocumentComments();

        api.nextSubscribeResponse = response(2, "boom", null);
        LarkException e = assertThrows(LarkException.class, bot::subscribeToDocumentComments);
        assertEquals("boom", e.getMessage());

        api.nextSubscribeResponse = response(2, null, null);
        e = assertThrows(LarkException.class, bot::subscribeToDocumentComments);
        assertEquals("订阅飞书文档评论事件失败", e.getMessage()); // msg null → defaultMsg
    }

    @Test
    void case22_requireSuccess_alwaysThrowsLarkException() {
        api.nextSubscribeResponse = response(1, "", null);
        assertThrows(LarkException.class, bot::subscribeToDocumentComments);

        api.fetchBotInfoResponse = response(0, null, "{}");
        assertThrows(LarkException.class, bot::getIdentity);

        api.nextListRepliesResponse = response(0, null, "{\"items\":[]}");
        assertThrows(LarkException.class, () -> bot.setDocumentCommentWorking(comment(""), true));
    }
}

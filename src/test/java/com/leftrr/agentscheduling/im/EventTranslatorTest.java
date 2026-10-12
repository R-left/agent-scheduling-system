package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 Task 10 brief Step 1 用例 1-10：EventTranslator 纯翻译层（入参为模拟
 * SDK 事件模型的 JsonNode，形态对齐飞书 v2 事件信封的 event 子树，即 TS
 * 回调里的 data）。字段缺失回退逐点对齐 TS lark.ts:364-399。
 */
class EventTranslatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- toIncomingMessage（用例 1-4，lark.ts:364-380 逐字段）----

    @Test
    void case01_fullEventTranslatesAllTwelveComponents() {
        JsonNode event = json("""
                {"message":{"message_id":"om_1","chat_id":"oc_1","chat_type":"group",
                   "message_type":"text","content":"{\\"text\\":\\"hello\\"}",
                   "root_id":"om_root","thread_id":"ot_1",
                   "mentions":[{"key":"@_user_1","name":"Bot","id":{"open_id":"ou_bot"}}]},
                 "sender":{"sender_type":"user","sender_id":{"open_id":"ou_1","union_id":"on_1"}}}""");

        IncomingMessage msg = EventTranslator.toIncomingMessage(event, MAPPER);
        assertEquals("om_1", msg.messageId());
        assertEquals("oc_1", msg.chatId());
        assertEquals("group", msg.chatType());
        assertEquals("text", msg.messageType());
        assertEquals("hello", msg.text()); // text = extractMessageText(message_type, content)
        assertEquals("om_root", msg.rootId());
        assertEquals("ot_1", msg.threadId());
        assertEquals("user", msg.senderType());
        assertEquals("ou_1", msg.senderOpenId());
        assertEquals("on_1", msg.senderUnionId());
        assertEquals(List.of(new Mention("@_user_1", "Bot", "ou_bot")), msg.mentions());
        assertEquals("{\"text\":\"hello\"}", msg.rawContent());
    }

    @Test
    void case02_missingRootThreadSenderTypeAndIdsFallBackToEmpty() {
        // TS ?? ''：root_id/thread_id/sender_type/sender_id.open_id/union_id 缺失 → ""
        JsonNode event = json("""
                {"message":{"message_id":"om_2","chat_id":"oc_2","chat_type":"p2p",
                   "message_type":"text","content":"{\\"text\\":\\"hi\\"}"},
                 "sender":{}}""");

        IncomingMessage msg = EventTranslator.toIncomingMessage(event, MAPPER);
        assertEquals("", msg.rootId());
        assertEquals("", msg.threadId());
        assertEquals("", msg.senderType());
        assertEquals("", msg.senderOpenId());
        assertEquals("", msg.senderUnionId());
    }

    @Test
    void case03_missingMentionsYieldEmptyListAndRawContentPassesThrough() {
        String rawContent = "{\"text\":\"@_user_1 你好\"}";
        JsonNode event = json("""
                {"message":{"message_id":"om_3","chat_id":"oc_3","chat_type":"group",
                   "message_type":"text","content":"{\\"text\\":\\"@_user_1 你好\\"}"},
                 "sender":{"sender_type":"user","sender_id":{"open_id":"ou_3"}}}""");

        IncomingMessage msg = EventTranslator.toIncomingMessage(event, MAPPER);
        assertTrue(msg.mentions().isEmpty()); // TS parseMentions(undefined) → []
        assertEquals(rawContent, msg.rawContent()); // 原样透传（未解析串）
    }

    @Test
    void case04_textAndRawContentAreIndependentFields() {
        // text 已提取正文、rawContent 保留原始占位串——两者独立字段并存。
        // 对齐 TS：extractMessageText 不还原提及（lark.ts:205-222），占位符原样保留；
        // brief 用例 4 括注「text 已还原提及」与 TS 实际行为不符，按 TS 为准（已申报偏差）。
        JsonNode event = json("""
                {"message":{"message_id":"om_4","chat_id":"oc_4","chat_type":"group",
                   "message_type":"text","content":"{\\"text\\":\\"hi @_user_1\\"}",
                   "mentions":[{"key":"@_user_1","name":"Bot","id":{"open_id":"ou_bot"}}]},
                 "sender":{"sender_type":"user","sender_id":{"open_id":"ou_4"}}}""");

        IncomingMessage msg = EventTranslator.toIncomingMessage(event, MAPPER);
        assertEquals("hi @_user_1", msg.text());
        assertEquals("{\"text\":\"hi @_user_1\"}", msg.rawContent());
        assertNotEquals(msg.text(), msg.rawContent()); // 独立字段：text 已脱壳，rawContent 原始串
    }

    // ---- toDocumentComment（用例 5-6，lark.ts:382-399）----

    @Test
    void case05_fullCommentEventTranslatesAllEightComponentsWithFallbacks() {
        JsonNode full = json("""
                {"event_id":"evt_1",
                 "notice_meta":{"file_token":"ft_1","file_type":"doc",
                   "from_user_id":{"open_id":"ou_9","union_id":"on_9"}},
                 "comment_id":"cm_1","reply_id":"rp_1","is_mentioned":true}""");
        IncomingDocumentComment comment = EventTranslator.toDocumentComment(full);
        assertNotNull(comment);
        assertEquals("evt_1", comment.eventId());
        assertEquals("ft_1", comment.fileToken());
        assertEquals("doc", comment.fileType());
        assertEquals("cm_1", comment.commentId());
        assertEquals("rp_1", comment.replyId());
        assertEquals("ou_9", comment.senderOpenId());
        assertEquals("on_9", comment.senderUnionId());
        assertTrue(comment.mentionedBot());

        // 缺失回退：event_id/reply_id → ""、is_mentioned → false、from_user_id 子字段 → ""
        JsonNode partial = json("""
                {"notice_meta":{"file_token":"ft_2","file_type":"doc","from_user_id":{}},
                 "comment_id":"cm_2"}""");
        IncomingDocumentComment fallback = EventTranslator.toDocumentComment(partial);
        assertNotNull(fallback);
        assertEquals("", fallback.eventId());
        assertEquals("", fallback.replyId());
        assertEquals(false, fallback.mentionedBot());
        assertEquals("", fallback.senderOpenId());
        assertEquals("", fallback.senderUnionId());
    }

    @Test
    void case06_missingFileTokenOrFileTypeOrCommentIdSkipsWholeEvent() {
        // lark.ts:385：任一缺失（或空串，TS truthy）→ 整事件跳过（Java 返回 null）
        assertNull(EventTranslator.toDocumentComment(json(
                "{\"notice_meta\":{\"file_type\":\"doc\"},\"comment_id\":\"cm_1\"}")));
        assertNull(EventTranslator.toDocumentComment(json(
                "{\"notice_meta\":{\"file_token\":\"ft_1\"},\"comment_id\":\"cm_1\"}")));
        assertNull(EventTranslator.toDocumentComment(json(
                "{\"notice_meta\":{\"file_token\":\"ft_1\",\"file_type\":\"doc\"}}")));
        // 空串与缺失同判（TS !meta?.file_token 对 "" 同样为真）
        assertNull(EventTranslator.toDocumentComment(json(
                "{\"notice_meta\":{\"file_token\":\"\",\"file_type\":\"doc\"},\"comment_id\":\"cm_1\"}")));
    }

    // ---- toCardAction（用例 7）----

    @Test
    void case07_toCardActionDelegatesToCardActionsWithArrayPassThrough() {
        // 委托一致性：同入参同输出（record 逐分量 equals）
        JsonNode full = json("""
                {"operator":{"open_id":"ou_1","union_id":"on_1"},
                 "context":{"open_message_id":"om_1"},
                 "action":{"value":{"k":"v"},"form_value":{"f":"1"}}}""");
        assertEquals(CardActions.parseCardAction(full), EventTranslator.toCardAction(full));

        // Task 2 用例 4 的数组通过语义：value 为数组时原样保留
        JsonNode arrayValue = json("{\"action\":{\"value\":[1,2],\"form_value\":{}}}");
        assertEquals(CardActions.parseCardAction(arrayValue), EventTranslator.toCardAction(arrayValue));
    }

    // ---- record 契约（用例 8-10）----

    @Test
    void case08_incomingMessageComponentOrderMatchesTsAssignmentOrder() {
        // 黄金断言：JSON 键序 = TS lark.ts:366-379 赋值序 = record 分量声明序
        IncomingMessage msg = new IncomingMessage(
                "om", "oc", "group", "text", "t", "r", "th", "user", "ou", "on", List.of(), "c");
        JsonNode node;
        try {
            node = MAPPER.readTree(MAPPER.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("messageId", "chatId", "chatType", "messageType", "text",
                "rootId", "threadId", "senderType", "senderOpenId", "senderUnionId",
                "mentions", "rawContent"), keys);
    }

    @Test
    void case09_toastTypeHasFourValuesWithTsLiteralIds() {
        assertEquals("success", CardActionResponse.ToastType.SUCCESS.id());
        assertEquals("info", CardActionResponse.ToastType.INFO.id());
        assertEquals("warning", CardActionResponse.ToastType.WARNING.id());
        assertEquals("error", CardActionResponse.ToastType.ERROR.id());
    }

    @Test
    void case10_botOptionsAllowsNullOptionalListeners() {
        // 编译期（字面量直接传 null）+ getter 断言：onCardAction/onDocumentComment 可空
        BotOptions opts = new BotOptions("app_id", "app_secret", (msg, bot) -> { }, null, null);
        assertNotNull(opts.onMessage());
        assertNull(opts.onCardAction());
        assertNull(opts.onDocumentComment());
        assertEquals("app_id", opts.appId());
        assertEquals("app_secret", opts.appSecret());
    }
}

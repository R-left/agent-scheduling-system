package com.leftrr.agentscheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Bot 门面的记录型 fake（Main 装配接线测试用；行为可按需换装）。 */
final class FakeBot implements Bot {

    final List<String> replies = new ArrayList<>();
    final List<String> cards = new ArrayList<>();
    final List<String> mentions = new ArrayList<>();
    final List<String> updatedCards = new ArrayList<>();
    final AtomicInteger subscribeCalls = new AtomicInteger();
    final List<String> commentReplies = new ArrayList<>();
    boolean failReply = false;
    private final BotIdentity identity;

    FakeBot(String name, String openId) {
        this.identity = new BotIdentity(openId, name);    }

    @Override
    public BotIdentity getIdentity() {
        return identity;
    }

    @Override
    public String reply(String messageId, String text, boolean replyInThread) {
        if (failReply) {
            throw new RuntimeException("reply boom");
        }
        replies.add(text);
        return "reply-" + messageId;
    }

    @Override
    public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
        cards.add(card.toString());
        return "card-" + messageId;
    }

    @Override
    public String replyMention(String messageId, BotIdentity target, String text,
            boolean replyInThread) {
        mentions.add(text);
        return "mention-" + messageId;
    }

    @Override
    public void updateCard(String messageId, JsonNode card) {
        updatedCards.add(card.toString());
    }

    @Override
    public void subscribeToDocumentComments() {
        subscribeCalls.incrementAndGet();
    }

    @Override
    public void replyToDocumentComment(IncomingDocumentComment comment, String text) {
        commentReplies.add(text);
    }

    @Override
    public void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active) {
        // 记录省略：装配接线测试不消费
    }

    @Override
    public String downloadResource(String messageId, String fileKey, String type,
            String saveDir, String fileName) {
        return saveDir + "/" + fileName;
    }
}

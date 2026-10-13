package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.im.LarkException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 2：sendResultNotification 测试。
 * 对齐 TS 版 src/app/notification-service.ts（21 行）：成功透传 replyMention，
 * 失败仅 error 日志记录并吞异常（不向上抛；M7 Task 0 起 SLF4J → logback STDERR）。
 */
class NotificationsTest {

    /** 记录 replyMention 调用参数的 fake Bot；可注入异常。其余方法 no-op。 */
    private static final class RecordingBot implements Bot {
        String messageId;
        BotIdentity target;
        String text;
        boolean replyInThread;
        int calls;
        RuntimeException failure;

        @Override
        public BotIdentity getIdentity() {
            return null;
        }

        @Override
        public String reply(String mid, String t, boolean r) {
            return null;
        }

        @Override
        public String replyCard(String mid, JsonNode card, boolean r) {
            return null;
        }

        @Override
        public String replyMention(String messageId, BotIdentity target, String text,
                boolean replyInThread) {
            calls++;
            this.messageId = messageId;
            this.target = target;
            this.text = text;
            this.replyInThread = replyInThread;
            if (failure != null) {
                throw failure;
            }
            return "om-reply-1";
        }

        @Override
        public void updateCard(String mid, JsonNode card) {
        }

        @Override
        public void subscribeToDocumentComments() {
        }

        @Override
        public void replyToDocumentComment(IncomingDocumentComment comment, String text) {
        }

        @Override
        public void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active) {
        }

        @Override
        public String downloadResource(String mid, String fileKey, String type,
                String saveDir, String fileName) throws IOException {
            return null;
        }
    }

    // 成功路径：参数原样透传给 bot.replyMention（对齐 TS 的 await options.bot.replyMention(...)）
    @Test
    void successForwardsArgumentsToBotReplyMention() {
        RecordingBot bot = new RecordingBot();
        BotIdentity target = new BotIdentity("ou_worker", "");

        Notifications.sendResultNotification(bot, "om-1", target, "任务完成", true);

        assertEquals(1, bot.calls);
        assertEquals("om-1", bot.messageId);
        assertSame(target, bot.target);
        assertEquals("任务完成", bot.text);
        assertTrue(bot.replyInThread);
    }

    // M7 Task 0（裁决 2）切换申报：System.err 直写改 SLF4J error（{} 占位），消息体
    // 文案逐字不变；logback ConsoleAppender 缓存流引用致 System.setErr 捕获不到，
    // 捕获方式改 LogbackCapture（对 formattedMessage 断言，文本与切换前一致）。
    // 失败路径：LarkException 不向上抛，error 日志记录（TS console.error 两参以空格连接）
    @Test
    void larkExceptionIsSwallowedAndLoggedToStderr() {
        RecordingBot bot = new RecordingBot();
        bot.failure = new LarkException("飞书接口炸了");

        LogbackCapture capture = LogbackCapture.attach(Notifications.class);
        try {
            Notifications.sendResultNotification(bot, "om-1",
                    new BotIdentity("ou_worker", ""), "任务完成", false);
        } finally {
            capture.detach();
        }

        assertEquals(1, bot.calls);
        assertEquals("[通知] 结果通知发送失败: 飞书接口炸了", capture.singleMessage());
    }

    // 失败路径：任意 RuntimeException 同样吞掉（LarkException 是其子类，catch 一并覆盖）
    @Test
    void runtimeExceptionIsSwallowedAndLoggedToStderr() {
        RecordingBot bot = new RecordingBot();
        bot.failure = new IllegalStateException("网络断开");

        LogbackCapture capture = LogbackCapture.attach(Notifications.class);
        try {
            Notifications.sendResultNotification(bot, "om-2",
                    new BotIdentity("ou_worker", ""), "任务完成", false);
        } finally {
            capture.detach();
        }

        assertEquals("[通知] 结果通知发送失败: 网络断开", capture.singleMessage());
    }
}

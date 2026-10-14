package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import com.leftrr.agentscheduling.im.LarkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结果 @通知容错封装：失败只记日志吞异常——通知挂了不能影响收尾。
 * catch RuntimeException 已覆盖 LarkException（其父类）。
 */
public final class Notifications {

    private static final Logger LOGGER = LoggerFactory.getLogger(Notifications.class);

    private Notifications() {}

    public static void sendResultNotification(Bot bot, String replyToMessageId,
            BotIdentity target, String text, boolean replyInThread) {
        try {
            bot.replyMention(replyToMessageId, target, text, replyInThread);
        } catch (RuntimeException e) {
            LOGGER.error("[通知] 结果通知发送失败: {}", e.getMessage());
        }
    }
}

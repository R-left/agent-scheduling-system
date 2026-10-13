package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.LarkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结果 @通知容错封装。对齐 TS 版 src/app/notification-service.ts（21 行）：
 * try replyMention，失败仅 console.error 记录并吞异常（通知失败不影响主流程收尾）。
 * TS→Java：TS 的 target 构造在各调用点（{ openId, name: '' } 或 identity），
 * Java 直接收 BotIdentity 参数；TS catch (error) 捕获一切，Java 捕获 RuntimeException
 * 已覆盖 LarkException（其父类），Error/受检异常不在此层吞。
 *
 * <p>M7 Task 0（裁决 2）：失败日志切 SLF4J error（logback.xml 定向 STDERR）。
 * R12 形态约束备案：TS console.error 多参以空格连接、冒号后一个空格——原文案
 * 「[通知] 结果通知发送失败: {消息}」经 {} 占位保持最终输出逐字不变（logback
 * 前缀时间戳/级别为配置观感，非消息体）。</p>
 */
public final class Notifications {

    private static final Logger LOGGER = LoggerFactory.getLogger(Notifications.class);

    private Notifications() {}

    /** TS sendResultNotification（notification-service.ts:3-20）。 */
    public static void sendResultNotification(Bot bot, String replyToMessageId,
            BotIdentity target, String text, boolean replyInThread) {
        try {
            bot.replyMention(replyToMessageId, target, text, replyInThread);
        } catch (RuntimeException e) {
            LOGGER.error("[通知] 结果通知发送失败: {}", e.getMessage());
        }
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.LarkException;

/**
 * 结果 @通知容错封装。对齐 TS 版 src/app/notification-service.ts（21 行）：
 * try replyMention，失败仅 console.error 记录并吞异常（通知失败不影响主流程收尾）。
 * TS→Java：TS 的 target 构造在各调用点（{ openId, name: '' } 或 identity），
 * Java 直接收 BotIdentity 参数；TS catch (error) 捕获一切，Java 捕获 RuntimeException
 * 已覆盖 LarkException（其父类），Error/受检异常不在此层吞。
 */
public final class Notifications {

    private Notifications() {}

    /** TS sendResultNotification（notification-service.ts:3-20）。 */
    public static void sendResultNotification(Bot bot, String replyToMessageId,
            BotIdentity target, String text, boolean replyInThread) {
        try {
            bot.replyMention(replyToMessageId, target, text, replyInThread);
        } catch (RuntimeException e) {
            // TS console.error 多参以空格连接，冒号后有一个空格；M7 切 SLF4J
            System.err.println("[通知] 结果通知发送失败: " + e.getMessage());
        }
    }
}

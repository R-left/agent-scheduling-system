package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.dto.core.TopicAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 话题任务 ID。 */
public final class TopicTask {
    private TopicTask() {
    }

    /** threadId 优先，其次 rootId，最后 messageId。 */
    public static String topicIdOf(TopicAddress message) {
        if (notBlank(message.threadId())) return message.threadId();
        if (notBlank(message.rootId())) return message.rootId();
        return message.messageId();
    }

    /** sha256("chatId:topicId") 的 UTF-8 hex 前 24 位。 */
    public static String topicTaskId(TopicAddress message) {
        String input = message.chatId() + ":" + topicIdOf(message);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }
}

package com.leftrr.agentscheduling.dto.im;

/**
 * 消息中一个 @提及 的结构化信息。
 *
 * @param key    占位符，如 '@_user_1'
 * @param name   显示名，如 'MyBot'
 * @param openId 如 'ou_xxx'
 */
public record Mention(String key, String name, String openId) {
}

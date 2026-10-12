package com.leftrr.agentscheduling.im;

/**
 * 对齐 TS 版 src/im/message-parser.ts 的 Mention 接口。
 *
 * @param key    占位符，如 '@_user_1'
 * @param name   显示名，如 'MyBot'
 * @param openId 如 'ou_xxx'
 */
public record Mention(String key, String name, String openId) {
}

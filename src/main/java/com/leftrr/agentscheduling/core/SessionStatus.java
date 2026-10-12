package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话状态机。对应 TS 版 src/core/session-manager.ts 的 SessionStatus。
 * 小写 id 串用于 JSON 序列化（@JsonValue）与状态机错误消息，与 TS 版一致（Review Focus 1）。
 */
public enum SessionStatus {
    CREATING("creating"),
    ACTIVE("active"),
    IDLE("idle"),
    CLOSED("closed");

    private final String id;

    SessionStatus(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** 未知值返回 null（调用方负责判失败，对齐 zod enum 校验）。 */
    public static SessionStatus fromId(String value) {
        for (SessionStatus status : values()) {
            if (status.id.equals(value)) return status;
        }
        return null;
    }
}

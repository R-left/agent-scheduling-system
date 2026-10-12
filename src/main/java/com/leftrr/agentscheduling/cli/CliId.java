package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 执行引擎标识。对应 TS 版 src/cli/types.ts 的 type CliId = 'claude' | 'codex'。
 * 本模块暂只定义标识本身；事件协议与适配器在 M2 补齐。
 */
public enum CliId {
    CLAUDE("claude"),
    CODEX("codex");

    private final String id;

    CliId(String id) {
        this.id = id;
    }

    /** 小写标识串，持久化与 JSON 序列化均使用该值，与 TS 版一致（M3 Task 4：会话持久化需要）。 */
    @JsonValue
    public String id() {
        return id;
    }

    public static Optional<CliId> fromId(String value) {
        if (value == null) return Optional.empty();
        for (CliId candidate : values()) {
            if (candidate.id.equals(value)) return Optional.of(candidate);
        }
        return Optional.empty();
    }
}

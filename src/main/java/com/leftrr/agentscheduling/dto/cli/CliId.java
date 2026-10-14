package com.leftrr.agentscheduling.dto.cli;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** 执行引擎标识。 */
public enum CliId {
    CLAUDE("claude"),
    CODEX("codex");

    private final String id;

    CliId(String id) {
        this.id = id;
    }

    /** 小写标识串，持久化与 JSON 序列化均使用该值。 */
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

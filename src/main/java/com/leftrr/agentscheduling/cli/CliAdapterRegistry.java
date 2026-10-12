package com.leftrr.agentscheduling.cli;

import java.util.List;
import java.util.Map;

/** 对齐 TS registry.ts：适配器注册表与 DEFAULT_CLI 解析。 */
public final class CliAdapterRegistry {
    private static final Map<CliId, CliAdapter> ADAPTERS = Map.of(
            CliId.CLAUDE, new ClaudeAdapter(),
            CliId.CODEX, new CodexAdapter());

    private CliAdapterRegistry() {}

    public static CliAdapter get(CliId id) {
        return ADAPTERS.get(id);
    }

    public static List<CliAdapter> list() {
        return List.copyOf(ADAPTERS.values());
    }

    /** 对齐 parseCliId()：null/空 → CLAUDE；非法值抛中文文案。 */
    public static CliId parseCliId(String value) {
        if (value == null || value.isEmpty()) {
            return CliId.CLAUDE;
        }
        return CliId.fromId(value)
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的 DEFAULT_CLI: " + value + "，请填写 claude 或 codex"));
    }
}

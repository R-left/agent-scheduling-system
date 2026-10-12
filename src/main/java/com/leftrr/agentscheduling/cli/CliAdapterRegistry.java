package com.leftrr.agentscheduling.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对齐 TS registry.ts：适配器注册表与 DEFAULT_CLI 解析。 */
public final class CliAdapterRegistry {
    // LinkedHashMap 保持 claude→codex 插入序（对齐 TS 对象字面量序），保证 list() 顺序确定
    private static final Map<CliId, CliAdapter> ADAPTERS;
    static {
        Map<CliId, CliAdapter> adapters = new LinkedHashMap<>();
        adapters.put(CliId.CLAUDE, new ClaudeAdapter());
        adapters.put(CliId.CODEX, new CodexAdapter());
        ADAPTERS = Collections.unmodifiableMap(adapters);
    }

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

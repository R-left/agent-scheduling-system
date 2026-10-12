package com.leftrr.agentscheduling.cli;

import java.util.Locale;
import java.util.Map;

/**
 * 对齐 TS runner.ts / native-compact.ts 中重复的 envTimeoutMs()：
 * {ID大写}_TIMEOUT_MS 优先，其次 CLI_TIMEOUT_MS，无有效值返回 null（调用方落默认超时）。
 */
public final class CliTimeouts {
    private CliTimeouts() {}

    public static Long fromEnv(CliId id) {
        return fromEnv(id, System.getenv());
    }

    /** 核心逻辑可注入 env，测试用。 */
    public static Long fromEnv(CliId id, Map<String, String> env) {
        String raw = env.get(id.id().toUpperCase(Locale.ROOT) + "_TIMEOUT_MS");
        if (raw == null) {
            raw = env.get("CLI_TIMEOUT_MS");
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(JsText.jsTrim(raw));
            return Double.isFinite(parsed) && parsed > 0 ? (long) parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

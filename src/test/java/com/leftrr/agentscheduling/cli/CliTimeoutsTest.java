package com.leftrr.agentscheduling.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliTimeoutsTest {

    @Test
    void emptyEnvYieldsNull() {
        assertNull(CliTimeouts.fromEnv(CliId.CLAUDE, Map.of()));
        assertNull(CliTimeouts.fromEnv(CliId.CODEX, Map.of()));
    }

    @Test
    void readsCliSpecificTimeout() {
        assertEquals(Long.valueOf(5000L),
                CliTimeouts.fromEnv(CliId.CLAUDE, Map.of("CLAUDE_TIMEOUT_MS", "5000")));
        // 对齐 JS Number(' 5000 ')==5000 的宽容性：jsTrim 后再解析
        assertEquals(Long.valueOf(5000L),
                CliTimeouts.fromEnv(CliId.CLAUDE, Map.of("CLAUDE_TIMEOUT_MS", " 5000 ")));
    }

    @Test
    void readsSharedCliTimeoutAsFallback() {
        assertEquals(Long.valueOf(3000L),
                CliTimeouts.fromEnv(CliId.CLAUDE, Map.of("CLI_TIMEOUT_MS", "3000")));
        assertEquals(Long.valueOf(3000L),
                CliTimeouts.fromEnv(CliId.CODEX, Map.of("CLI_TIMEOUT_MS", "3000")));
    }

    @Test
    void cliSpecificWinsOverShared() {
        // CLAUDE 专属优先于共享 CLI_TIMEOUT_MS
        assertEquals(Long.valueOf(5000L), CliTimeouts.fromEnv(CliId.CLAUDE,
                Map.of("CLAUDE_TIMEOUT_MS", "5000", "CLI_TIMEOUT_MS", "3000")));
        // CODEX 请求时读 CODEX_TIMEOUT_MS
        assertEquals(Long.valueOf(7000L), CliTimeouts.fromEnv(CliId.CODEX,
                Map.of("CODEX_TIMEOUT_MS", "7000", "CLI_TIMEOUT_MS", "3000")));
        assertEquals(Long.valueOf(7000L), CliTimeouts.fromEnv(CliId.CODEX,
                Map.of("CODEX_TIMEOUT_MS", "7000", "CLAUDE_TIMEOUT_MS", "5000")));
    }

    @Test
    void invalidValuesYieldNull() {
        for (String bad : new String[] {"abc", "0", "-5", "   ", ""}) {
            assertNull(CliTimeouts.fromEnv(CliId.CLAUDE, Map.of("CLAUDE_TIMEOUT_MS", bad)),
                    "值 [" + bad + "] 应判为无效");
        }
    }

    @Test
    void fractionalMillisTruncate() {
        // 截断到毫秒，与 JS Number 行为一致
        assertEquals(Long.valueOf(3000L),
                CliTimeouts.fromEnv(CliId.CLAUDE, Map.of("CLAUDE_TIMEOUT_MS", "3000.9")));
    }
}

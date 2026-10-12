package com.leftrr.agentscheduling.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CliAdapterRegistry 测试：适配器清单与 DEFAULT_CLI 解析（对齐 TS registry.ts）。 */
class CliAdapterRegistryTest {

    @Test
    void getReturnsAdapterPerCliId() {
        assertInstanceOf(ClaudeAdapter.class, CliAdapterRegistry.get(CliId.CLAUDE));
        assertInstanceOf(CodexAdapter.class, CliAdapterRegistry.get(CliId.CODEX));
    }

    @Test
    void listContainsExactlyTwoAdapters() {
        List<CliAdapter> adapters = CliAdapterRegistry.list();
        assertEquals(2, adapters.size());
    }

    @Test
    void listKeepsClaudeThenCodexOrder() {
        List<CliAdapter> adapters = CliAdapterRegistry.list();
        assertEquals(2, adapters.size());
        assertTrue(adapters.get(0) instanceof ClaudeAdapter);
        assertTrue(adapters.get(1) instanceof CodexAdapter);
    }

    @Test
    void parseCliIdNullAndEmptyDefaultToClaude() {
        assertEquals(CliId.CLAUDE, CliAdapterRegistry.parseCliId(null));
        assertEquals(CliId.CLAUDE, CliAdapterRegistry.parseCliId(""));
    }

    @Test
    void parseCliIdResolvesKnownIdsCaseSensitively() {
        assertEquals(CliId.CLAUDE, CliAdapterRegistry.parseCliId("claude"));
        assertEquals(CliId.CODEX, CliAdapterRegistry.parseCliId("codex"));
        // 大小写敏感：fromId 不识别 "Claude" → orElseThrow 抛 IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> CliAdapterRegistry.parseCliId("Claude"));
    }

    @Test
    void parseCliIdUnknownValueThrowsWithExactMessage() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> CliAdapterRegistry.parseCliId("bun"));
        // 文案逐字对齐 TS（Review Focus 9）
        assertEquals("不支持的 DEFAULT_CLI: bun，请填写 claude 或 codex", e.getMessage());
    }
}

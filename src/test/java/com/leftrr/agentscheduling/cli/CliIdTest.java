package com.leftrr.agentscheduling.cli;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliIdTest {
    @Test
    void resolvesLowercaseIds() {
        assertEquals("claude", CliId.CLAUDE.id());
        assertEquals("codex", CliId.CODEX.id());
    }

    @Test
    void parsesKnownIds() {
        assertEquals(Optional.of(CliId.CLAUDE), CliId.fromId("claude"));
        assertEquals(Optional.of(CliId.CODEX), CliId.fromId("codex"));
    }

    @Test
    void rejectsUnknownIds() {
        assertTrue(CliId.fromId("gpt").isEmpty());
        assertTrue(CliId.fromId(null).isEmpty());
        assertTrue(CliId.fromId("").isEmpty());
    }
}

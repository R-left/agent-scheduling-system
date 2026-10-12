package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * M6 Task 2：formatSessionStatus / markSessionIdle 测试。
 * 对齐 TS 版 src/app/session-view.ts（33 行）：STATUS_LABELS 四状态标签、8 行输出、
 * cliSessionId 缺失 → (尚未建立)；markSessionIdle 仅 active → idle。
 */
class SessionViewTest {

    private static Session session(SessionStatus status, String cliSessionId, CliId cliId) {
        return new Session("sess-1", "bot-1", "ot-1", "oc-1", cliId,
                "D:/ws", status, "2026-01-01T00:00:00.000Z", "2026-01-01T01:00:00.000Z",
                cliSessionId);
    }

    // 四状态标签逐字（对齐 TS STATUS_LABELS：creating/active/idle/closed）
    @Test
    void statusLabelsAreVerbatim() {
        assertEquals("状态：创建中", lineOf(session(SessionStatus.CREATING, null, CliId.CLAUDE), 2));
        assertEquals("状态：执行中", lineOf(session(SessionStatus.ACTIVE, null, CliId.CLAUDE), 2));
        assertEquals("状态：空闲", lineOf(session(SessionStatus.IDLE, null, CliId.CLAUDE), 2));
        assertEquals("状态：已关闭", lineOf(session(SessionStatus.CLOSED, null, CliId.CLAUDE), 2));
    }

    // 8 行结构与各前缀逐字（执行引擎走真 registry：Claude → Claude Code）
    @Test
    void outputHasEightLinesWithVerbatimPrefixes() {
        String text = SessionView.formatSessionStatus(
                session(SessionStatus.ACTIVE, "cli-abc", CliId.CLAUDE), "bot-1");

        assertArrayEquals(new String[] {
                "机器人：bot-1",
                "会话：sess-1",
                "状态：执行中",
                "执行引擎：Claude Code",
                "CLI 会话：cli-abc",
                "工作目录：D:/ws",
                "话题：ot-1",
                "更新时间：2026-01-01T01:00:00.000Z",
        }, text.split("\n", -1));
    }

    // cliSessionId 为 null（TS undefined）→ (尚未建立) 兜底；Codex 引擎名走真 registry
    @Test
    void missingCliSessionIdFallsBackToPlaceholder() {
        String[] lines = SessionView.formatSessionStatus(
                session(SessionStatus.IDLE, null, CliId.CODEX), "bot-2").split("\n", -1);

        assertEquals("机器人：bot-2", lines[0]);
        assertEquals("执行引擎：Codex", lines[3]);
        assertEquals("CLI 会话：(尚未建立)", lines[4]);
    }

    private static String lineOf(Session session, int index) {
        return SessionView.formatSessionStatus(session, "bot-1").split("\n", -1)[index];
    }

    // markSessionIdle：active 会话 → transition 到 idle + System.out 逐字输出
    @Test
    void activeSessionTransitionsToIdleAndLogs() throws IOException {
        SessionManager sessions = new SessionManager();
        Session created = sessions.resolve(
                new MessageAddress("om-1", "oc-1", "ot-1", null),
                CliId.CLAUDE, "bot-1", "D:/ws").session();
        sessions.transition(created.id(), SessionStatus.ACTIVE);

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String stdout;
        try {
            SessionView.markSessionIdle(sessions, created.id());
        } finally {
            System.setOut(original);
        }
        stdout = buffer.toString(StandardCharsets.UTF_8);

        assertEquals(SessionStatus.IDLE, sessions.get(created.id()).status());
        assertEquals("[会话] id=" + created.id() + " status=idle", stdout.strip());
    }

    // markSessionIdle：非 active（creating）直接 return，状态不变、无输出
    @Test
    void nonActiveSessionIsLeftUnchanged() throws IOException {
        SessionManager sessions = new SessionManager();
        Session created = sessions.resolve(
                new MessageAddress("om-1", "oc-1", "ot-1", null),
                CliId.CLAUDE, "bot-1", "D:/ws").session();

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String stdout;
        try {
            SessionView.markSessionIdle(sessions, created.id());
        } finally {
            System.setOut(original);
        }
        stdout = buffer.toString(StandardCharsets.UTF_8);

        assertEquals(SessionStatus.CREATING, sessions.get(created.id()).status());
        assertEquals("", stdout.strip());
    }

    // markSessionIdle：会话不存在（get 返回 null）直接 return，不抛
    @Test
    void unknownSessionIdIsIgnored() throws IOException {
        SessionView.markSessionIdle(new SessionManager(), "no-such-session");
    }
}

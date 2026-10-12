package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应 TS 版 src/core/session-store.ts（brief Task 4 Step 1 清单）：
 * 旧格式迁移、中断恢复、非法行跳过（任一发生即回写）、黄金格式逐字节对齐 JSON.stringify(v, null, 2)。
 */
class JsonSessionStoreTest {
    private static final String T0 = "2026-01-01T00:00:00.000Z";

    @TempDir
    Path tempDir;

    // ---- 测试私有工厂 ----

    private static Session session(String id, CliId cliId, SessionStatus status, String cliSessionId) {
        return new Session(id, "default", "ot_" + id, "oc_" + id, cliId,
                "D:\\ws", status, T0, T0, cliSessionId);
    }

    private String path(String fileName) {
        return tempDir.resolve(fileName).toString();
    }

    private static String fullRow(String id, String cliId, String status, String extraFields) {
        return "{\"id\":\"" + id + "\",\"botId\":\"default\",\"threadId\":\"ot_" + id
                + "\",\"chatId\":\"oc_" + id + "\",\"cliId\":\"" + cliId
                + "\",\"workspaceDir\":\"D:\\\\ws\",\"status\":\"" + status
                + "\",\"createdAt\":\"" + T0 + "\",\"updatedAt\":\"" + T0 + "\"" + extraFields + "}";
    }

    // ---- 用例 1：文件不存在 → load 返回空列表，不抛 ----

    @Test
    void loadReturnsEmptyListWhenFileMissing() throws IOException {
        JsonSessionStore store = new JsonSessionStore(path("sessions.json"));
        List<Session> sessions = store.load();
        assertTrue(sessions.isEmpty());
    }

    // ---- 用例 2：save→load 往返 + 空列表 → "[]\n" ----

    @Test
    void roundTripPreservesAllFields() throws IOException {
        JsonSessionStore store = new JsonSessionStore(path("sessions.json"));
        Session original = session("s1", CliId.CODEX, SessionStatus.IDLE, "cli-abc");
        store.save(List.of(original));
        List<Session> loaded = store.load();
        assertEquals(List.of(original), loaded);
    }

    @Test
    void saveEmptyListWritesExactlyEmptyArrayWithNewline() throws IOException {
        JsonSessionStore store = new JsonSessionStore(path("sessions.json"));
        store.save(List.of());
        assertEquals("[]\n", Files.readString(Path.of(path("sessions.json"))));
    }

    // ---- 用例 3：黄金格式（对齐 JSON.stringify(v, null, 2)）----

    @Test
    void goldenFormatMatchesJsonStringifyIndentTwo() throws IOException {
        JsonSessionStore store = new JsonSessionStore(path("sessions.json"));
        store.save(List.of(session("s1", CliId.CLAUDE, SessionStatus.CREATING, null)));
        String expected = """
                [
                  {
                    "id": "s1",
                    "botId": "default",
                    "threadId": "ot_s1",
                    "chatId": "oc_s1",
                    "cliId": "claude",
                    "workspaceDir": "D:\\\\ws",
                    "status": "creating",
                    "createdAt": "2026-01-01T00:00:00.000Z",
                    "updatedAt": "2026-01-01T00:00:00.000Z"
                  }
                ]
                """;
        assertEquals(expected, Files.readString(Path.of(path("sessions.json"))));
    }

    // ---- 用例 4：根节点非数组 → IOException ----

    @Test
    void loadRejectsNonArrayRootObject() {
        String file = path("sessions.json");
        JsonSessionStore store = new JsonSessionStore(file);
        IOException error = assertThrows(IOException.class, () -> writeRawAndLoad(store, file, "{}"));
        assertEquals("会话文件格式错误: " + file, error.getMessage());
    }

    @Test
    void loadRejectsNonArrayRootString() {
        String file = path("sessions.json");
        JsonSessionStore store = new JsonSessionStore(file);
        IOException error = assertThrows(IOException.class, () -> writeRawAndLoad(store, file, "\"x\""));
        assertEquals("会话文件格式错误: " + file, error.getMessage());
    }

    // ---- 用例 5：非法 JSON 文本 → IOException ----

    @Test
    void loadRejectsMalformedJsonText() {
        String file = path("sessions.json");
        JsonSessionStore store = new JsonSessionStore(file);
        assertThrows(IOException.class, () -> writeRawAndLoad(store, file, "not json {"));
    }

    // ---- 用例 6：旧格式迁移：缺 botId 与 workspaceDir → 补默认值并回写 ----

    @Test
    void legacyRowMissingBotIdAndWorkspaceGetsDefaultsAndFileRewritten() throws IOException {
        String file = path("sessions.json");
        String row = "{\"id\":\"s1\",\"threadId\":\"ot_x\",\"chatId\":\"oc_y\",\"cliId\":\"claude\","
                + "\"status\":\"idle\",\"createdAt\":\"" + T0 + "\",\"updatedAt\":\"" + T0 + "\"}";
        Files.writeString(Path.of(file), "[" + row + "]\n");
        JsonSessionStore store = new JsonSessionStore(file, "default", Map.of("default", "D:\\legacy-ws"));
        List<Session> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("default", loaded.get(0).botId());
        assertEquals("D:\\legacy-ws", loaded.get(0).workspaceDir());
        assertTrue(Files.readString(Path.of(file)).contains("\"botId\""), "迁移后文件应回写补齐 botId");
    }

    // ---- 用例 7：botId 存在缺 workspaceDir → defaultWorkspaces 优先，缺失退 cwd ----

    @Test
    void legacyWorkspaceFallsBackToDefaultWorkspacesThenCwd() throws IOException {
        String rowTemplate = "{\"id\":\"s1\",\"botId\":\"alpha\",\"threadId\":\"ot_x\",\"chatId\":\"oc_y\","
                + "\"cliId\":\"claude\",\"status\":\"idle\",\"createdAt\":\"" + T0
                + "\",\"updatedAt\":\"" + T0 + "\"}";

        String file1 = path("ws-map.json");
        Files.writeString(Path.of(file1), "[" + rowTemplate + "]\n");
        JsonSessionStore store1 = new JsonSessionStore(file1, "default", Map.of("alpha", "D:\\alpha-ws"));
        assertEquals("D:\\alpha-ws", store1.load().get(0).workspaceDir());

        String file2 = path("cwd.json");
        Files.writeString(Path.of(file2), "[" + rowTemplate + "]\n");
        JsonSessionStore store2 = new JsonSessionStore(file2, "default", Map.of());
        assertEquals(Path.of("").toAbsolutePath().toString(), store2.load().get(0).workspaceDir());
    }

    // ---- 用例 8：缺 botId 但有 workspaceDir → 只补 botId，workspace 保留 ----

    @Test
    void legacyRowWithWorkspaceKeepsWorkspace() throws IOException {
        String file = path("sessions.json");
        String row = "{\"id\":\"s1\",\"threadId\":\"ot_x\",\"chatId\":\"oc_y\",\"cliId\":\"claude\","
                + "\"workspaceDir\":\"D:\\\\kept\",\"status\":\"idle\","
                + "\"createdAt\":\"" + T0 + "\",\"updatedAt\":\"" + T0 + "\"}";
        Files.writeString(Path.of(file), "[" + row + "]\n");
        JsonSessionStore store = new JsonSessionStore(file, "default", Map.of("default", "D:\\unused"));
        List<Session> loaded = store.load();
        assertEquals("default", loaded.get(0).botId());
        assertEquals("D:\\kept", loaded.get(0).workspaceDir());
    }

    // ---- 用例 9：非法行跳过，合法行保留，回写后不含非法行 ----

    @Test
    void invalidRowsAreDroppedAndFileRewrittenWithoutThem() throws IOException {
        String file = path("sessions.json");
        String bogusStatus = fullRow("s1", "claude", "bogus", "");
        String missingId = fullRow("s2", "claude", "idle", "")
                .replace("\"id\":\"s2\",", "");
        String unknownCli = fullRow("s3", "bun", "idle", "");
        String badTime = fullRow("s4", "claude", "idle", "")
                .replace("\"createdAt\":\"" + T0 + "\"", "\"createdAt\":\"not-iso\"");
        String valid = fullRow("s5", "codex", "idle", "");
        Files.writeString(Path.of(file), "["
                + bogusStatus + "," + missingId + "," + unknownCli + "," + badTime + "," + valid + "]\n");

        JsonSessionStore store = new JsonSessionStore(file);
        List<Session> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("s5", loaded.get(0).id());

        String rewritten = Files.readString(Path.of(file));
        assertFalse(rewritten.contains("bogus"));
        assertFalse(rewritten.contains("s2"));
        assertFalse(rewritten.contains("\"bun\""));
        assertFalse(rewritten.contains("not-iso"));
        assertTrue(rewritten.contains("s5"));
    }

    // ---- 用例 10：cliSessionId 空串 / 显式 null → 行判非法跳过 ----

    @Test
    void emptyOrExplicitNullCliSessionIdMakesRowInvalid() throws IOException {
        String file = path("sessions.json");
        String emptyCliSession = fullRow("s1", "claude", "idle", ",\"cliSessionId\":\"\"");
        String nullCliSession = fullRow("s2", "claude", "idle", ",\"cliSessionId\":null");
        String valid = fullRow("s3", "claude", "idle", ",\"cliSessionId\":\"c-1\"");
        Files.writeString(Path.of(file), "[" + emptyCliSession + "," + nullCliSession + "," + valid + "]\n");

        JsonSessionStore store = new JsonSessionStore(file);
        List<Session> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("s3", loaded.get(0).id());
        assertEquals("c-1", loaded.get(0).cliSessionId());
        assertFalse(Files.readString(Path.of(file)).contains("cliSessionId\":\"\""));
    }

    // ---- 用例 11：中断恢复 creating/active → idle，idle/closed 不变 ----

    @Test
    void interruptedSessionsRecoverToIdleAndFileRewritten() throws IOException {
        String file = path("sessions.json");
        Files.writeString(Path.of(file), "["
                + fullRow("s1", "claude", "creating", "") + ","
                + fullRow("s2", "claude", "active", "") + ","
                + fullRow("s3", "claude", "idle", "") + ","
                + fullRow("s4", "claude", "closed", "") + "]\n");

        JsonSessionStore store = new JsonSessionStore(file);
        List<Session> loaded = store.load();
        assertEquals(SessionStatus.IDLE, loaded.get(0).status());
        assertEquals(SessionStatus.IDLE, loaded.get(1).status());
        assertEquals(SessionStatus.IDLE, loaded.get(2).status());
        assertEquals(SessionStatus.CLOSED, loaded.get(3).status());
        String rewritten = Files.readString(Path.of(file));
        assertFalse(rewritten.contains("creating"));
        assertFalse(rewritten.contains("\"active\""));
        assertEquals(3, rewritten.split("\"idle\"", -1).length - 1);
    }

    @Test
    void idleAndClosedFilesAreNotRewritten() throws IOException {
        String file = path("sessions.json");
        String content = "[" + fullRow("s1", "claude", "idle", "") + ","
                + fullRow("s2", "claude", "closed", "") + "]\n";
        Files.writeString(Path.of(file), content);

        JsonSessionStore store = new JsonSessionStore(file);
        store.load();
        assertEquals(content, Files.readString(Path.of(file)), "无迁移无恢复时文件字节不变");
    }

    // ---- 用例 12：完全合法文件 → load 后字节不变 ----

    @Test
    void fullyValidFileIsByteIdenticalAfterLoad() throws IOException {
        JsonSessionStore store = new JsonSessionStore(path("sessions.json"));
        store.save(List.of(
                session("s1", CliId.CLAUDE, SessionStatus.IDLE, "c-1"),
                session("s2", CliId.CODEX, SessionStatus.CLOSED, null)));
        String before = Files.readString(Path.of(path("sessions.json")));
        store.load();
        assertEquals(before, Files.readString(Path.of(path("sessions.json"))));
    }

    // ---- 用例 13：save 自动建父目录、无 .tmp 残留 ----

    @Test
    void saveCreatesParentDirectoriesAndLeavesNoTmpResidue() throws IOException {
        String file = tempDir.resolve("sub").resolve("dir").resolve("sessions.json").toString();
        JsonSessionStore store = new JsonSessionStore(file);
        store.save(List.of(session("s1", CliId.CLAUDE, SessionStatus.IDLE, null)));
        assertTrue(Files.exists(Path.of(file)));
        assertFalse(Files.exists(Path.of(file + ".tmp")));
        assertEquals(1, store.load().size());
    }

    // ---- 用例 14：并发 save 串行化，最终为最后一次内容 ----

    @Test
    void concurrentSavesSerializeAndLeaveLoadableFile() throws Exception {
        JsonSessionStore store = new JsonSessionStore(path("sessions.json"));
        List<Session> first = List.of(session("s1", CliId.CLAUDE, SessionStatus.IDLE, null));
        List<Session> second = List.of(
                session("s1", CliId.CLAUDE, SessionStatus.IDLE, null),
                session("s2", CliId.CODEX, SessionStatus.IDLE, "c-2"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Exception> failures = new ArrayList<>();
        Runnable task1 = () -> runSave(store, first, barrier, failures);
        Runnable task2 = () -> runSave(store, second, barrier, failures);
        Future<?> f1 = pool.submit(task1);
        Future<?> f2 = pool.submit(task2);
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertTrue(failures.isEmpty(), () -> "并发 save 出现异常: " + failures);

        List<Session> loaded = store.load();
        assertTrue(loaded.equals(first) || loaded.equals(second), () -> "应为两份写入之一: " + loaded);
    }

    private static void runSave(JsonSessionStore store, List<Session> sessions,
            CyclicBarrier barrier, List<Exception> failures) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            store.save(sessions);
        } catch (Exception e) {
            synchronized (failures) {
                failures.add(e);
            }
        }
    }

    // ---- 行为补充：load 返回的合法会话不因迁移/恢复以外的原因回写 ----

    @Test
    void loadDoesNotRewriteWhenOnlyValidRowsPresent() throws IOException {
        String file = path("sessions.json");
        String content = "[" + fullRow("s1", "claude", "idle", ",\"cliSessionId\":\"c-1\"") + "]\n";
        Files.writeString(Path.of(file), content);
        JsonSessionStore store = new JsonSessionStore(file);
        List<Session> loaded = store.load();
        assertEquals("c-1", loaded.get(0).cliSessionId());
        assertEquals(content, Files.readString(Path.of(file)));
    }

    @Test
    void recoverInterruptedSessionKeepsUpdatedAtAndOtherFields() {
        Session active = session("s1", CliId.CLAUDE, SessionStatus.ACTIVE, "c-1");
        Session recovered = JsonSessionStore.recoverInterruptedSession(active);
        assertEquals(SessionStatus.IDLE, recovered.status());
        assertEquals(active.updatedAt(), recovered.updatedAt());
        assertEquals(active.cliSessionId(), recovered.cliSessionId());

        Session closed = session("s2", CliId.CODEX, SessionStatus.CLOSED, null);
        assertEquals(closed, JsonSessionStore.recoverInterruptedSession(closed));
    }

    @Test
    void parseSessionRejectsEmptyRequiredStrings() throws IOException {
        String file = path("sessions.json");
        String emptyBotId = fullRow("s1", "claude", "idle", "")
                .replace("\"botId\":\"default\"", "\"botId\":\"\"");
        Files.writeString(Path.of(file), "[" + emptyBotId + "]\n");
        JsonSessionStore store = new JsonSessionStore(file);
        assertTrue(store.load().isEmpty());
        assertTrue(Files.readString(Path.of(file)).contains("[]"), "空文件回写为 []");
    }

    @Test
    void parseSessionKeepsExplicitNullRecoveryStatusOut() throws IOException {
        // status: null（JSON null）→ zod enum 拒绝 → 行跳过
        String file = path("sessions.json");
        String nullStatus = fullRow("s1", "claude", "idle", "")
                .replace("\"status\":\"idle\"", "\"status\":null");
        Files.writeString(Path.of(file), "[" + nullStatus + "]\n");
        assertNull(JsonSessionStore.parseSession(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(nullStatus)));
        assertTrue(new JsonSessionStore(file).load().isEmpty());
    }

    private static void writeRawAndLoad(JsonSessionStore store, String file, String content)
            throws IOException {
        Files.writeString(Path.of(file), content, StandardCharsets.UTF_8);
        store.load();
    }
}

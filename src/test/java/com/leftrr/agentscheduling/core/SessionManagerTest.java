package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应 TS 版 src/core/session-manager.ts 的语义对齐用例（brief Task 3 Step 2 清单 19 用例）。
 * now/createId 注入计数器；store 用测试内联实现（JsonSessionStore 在 Task 4）。
 */
class SessionManagerTest {

    // ---------- fixture ----------

    /** 内联 store：记录 save 收到的快照，可注入故障。 */
    private static final class InMemoryStore implements SessionStore {
        final List<Session> initial;
        final List<List<Session>> savedSnapshots = new ArrayList<>();
        boolean failSave = false;
        int saveCalls = 0;

        InMemoryStore(List<Session> initial) {
            this.initial = initial;
        }

        @Override
        public List<Session> load() {
            return new ArrayList<>(initial);
        }

        @Override
        public void save(List<Session> sessions) throws IOException {
            saveCalls++;
            savedSnapshots.add(new ArrayList<>(sessions));
            if (failSave) {
                throw new IOException("boom");
            }
        }
    }

    private static MessageAddress address(String messageId, String chatId,
            String threadId, String rootId) {
        return new MessageAddress(messageId, chatId, threadId, rootId);
    }

    /** now 注入：依次吐出 T0、T1…；createId 注入：id-1、id-2… */
    private static SupplierPair counters() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger seq = new AtomicInteger();
        return new SupplierPair(
                () -> "2026-01-01T00:00:00." + String.format("%03d", clock.getAndIncrement() * 10) + "Z",
                () -> "id-" + seq.incrementAndGet());
    }

    private record SupplierPair(java.util.function.Supplier<String> now,
            java.util.function.Supplier<String> createId) {
    }

    // ---------- resolve ----------

    @Test
    void resolveNewSessionUsesInjectedClockAndId() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        ResolvedSession resolved = manager.resolve(
                address("om-1", "oc-1", "ot-1", null), CliId.CODEX, "dev", "D:/ws");

        assertTrue(resolved.isNew());
        Session session = resolved.session();
        assertEquals("id-1", session.id());
        assertEquals(SessionStatus.CREATING, session.status());
        assertEquals("2026-01-01T00:00:00.000Z", session.createdAt());
        assertEquals("2026-01-01T00:00:00.000Z", session.updatedAt());
        assertEquals(CliId.CODEX, session.cliId());
        assertEquals("dev", session.botId());
        assertEquals("D:/ws", session.workspaceDir());
        assertEquals("ot-1", session.threadId());
        assertEquals("oc-1", session.chatId());
        assertNull(session.cliSessionId());
    }

    @Test
    void resolveSameAddressReturnsExistingEvenWithDifferentCliId() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        MessageAddress message = address("om-1", "oc-1", "ot-1", null);
        Session first = manager.resolve(message, CliId.CLAUDE, "dev", "D:/ws").session();
        ResolvedSession second = manager.resolve(message, CliId.CODEX, "dev", "D:/ws");

        assertFalse(second.isNew());
        assertSame(first, second.session());
        assertEquals(CliId.CLAUDE, second.session().cliId());
        assertEquals(1, manager.size());
    }

    @Test
    void topicIdOfFallbackChain() {
        // threadId 非空 → threadId
        assertEquals("ot-1", SessionManager.topicIdOf(address("om", "oc", "ot-1", "root")));
        // threadId 为 null → rootId
        assertEquals("root", SessionManager.topicIdOf(address("om", "oc", null, "root")));
        // 两者都 null → messageId
        assertEquals("om", SessionManager.topicIdOf(address("om", "oc", null, null)));
        // threadId 为空串 → rootId（TS || 对空串 falsy——必须 isEmpty 判定而非 null）
        assertEquals("root", SessionManager.topicIdOf(address("om", "oc", "", "root")));
    }

    @Test
    void resolveSingleArgDefaults() throws IOException {
        SessionManager manager = new SessionManager();
        String expectedWorkspace = java.nio.file.Path.of("").toAbsolutePath().toString();
        ResolvedSession resolved = manager.resolve(address("om-1", "oc-1", "ot-1", null));

        assertTrue(resolved.isNew());
        Session session = resolved.session();
        assertEquals(CliId.CLAUDE, session.cliId());
        assertEquals("default", session.botId());
        assertEquals(expectedWorkspace, session.workspaceDir());
    }

    // ---------- 状态机 ----------

    @Test
    void legalTransitionChainUpdatesTimestamp() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        // creating→closed 属合法迁移（CREATING 白名单含 CLOSED）
        Session closed = manager.transition(session.id(), SessionStatus.CLOSED);
        assertEquals(SessionStatus.CLOSED, closed.status());
        assertEquals("2026-01-01T00:00:00.010Z", closed.updatedAt());
    }

    @Test
    void legalFullChainCreatingActiveIdleClosed() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        Session active = manager.transition(session.id(), SessionStatus.ACTIVE);
        assertEquals(SessionStatus.ACTIVE, active.status());
        assertEquals("2026-01-01T00:00:00.010Z", active.updatedAt());

        Session idle = manager.transition(session.id(), SessionStatus.IDLE);
        assertEquals(SessionStatus.IDLE, idle.status());
        assertEquals("2026-01-01T00:00:00.020Z", idle.updatedAt());

        // idle→active 也合法
        Session backActive = manager.transition(session.id(), SessionStatus.ACTIVE);
        assertEquals(SessionStatus.ACTIVE, backActive.status());
        assertEquals("2026-01-01T00:00:00.030Z", backActive.updatedAt());

        Session closed = manager.transition(session.id(), SessionStatus.CLOSED);
        assertEquals(SessionStatus.CLOSED, closed.status());
        assertEquals("2026-01-01T00:00:00.040Z", closed.updatedAt());
    }

    @Test
    void creatingToIdleIsLegal() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        // creating→idle 也合法
        Session idleFromCreating = manager.transition(session.id(), SessionStatus.IDLE);
        assertEquals(SessionStatus.IDLE, idleFromCreating.status());
        assertEquals("2026-01-01T00:00:00.010Z", idleFromCreating.updatedAt());
    }

    @Test
    void illegalTransitionMessagesAreLiteralLowercaseIdChains() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();
        manager.transition(session.id(), SessionStatus.ACTIVE);

        // active→creating（同时验证小写 id 串，Review Focus 1）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.transition(session.id(), SessionStatus.CREATING));
        assertEquals("会话 active 不能切换到 creating", ex.getMessage());

        manager.transition(session.id(), SessionStatus.CLOSED);
        // closed→active
        IllegalArgumentException exClosed = assertThrows(IllegalArgumentException.class,
                () -> manager.transition(session.id(), SessionStatus.ACTIVE));
        assertEquals("会话 closed 不能切换到 active", exClosed.getMessage());
    }

    @Test
    void transitionUnknownSessionThrowsWithMessage() {
        SessionManager manager = new SessionManager();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.transition("nope", SessionStatus.ACTIVE));
        assertEquals("会话不存在: nope", ex.getMessage());
    }

    // ---------- CLI 会话选择与工作目录 ----------

    @Test
    void setCliSessionIdAndClear() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        Session withCli = manager.setCliSessionId(session.id(), "cli-abc");
        assertEquals("cli-abc", withCli.cliSessionId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.setCliSessionId(session.id(), ""));
        assertEquals("CLI 会话 ID 不能为空", ex.getMessage());

        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class,
                () -> manager.setCliSessionId(session.id(), null));
        assertEquals("CLI 会话 ID 不能为空", exNull.getMessage());

        Session cleared = manager.clearCliSessionId(session.id());
        assertNull(cleared.cliSessionId());
    }

    @Test
    void setWorkspaceDirClearsCliSessionIdAndSkipsSameValue() throws IOException {
        SupplierPair sp = counters();
        InMemoryStore store = new InMemoryStore(List.of());
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), store);
        Session session = manager.resolve(
                address("om-1", "oc-1", "ot-1", null), CliId.CLAUDE, "dev", "D:/ws-old").session();
        manager.setCliSessionId(session.id(), "cli-abc");

        int savesBeforeMove = store.saveCalls;
        Session moved = manager.setWorkspaceDir(session.id(), "D:/ws-new");
        assertEquals("D:/ws-new", moved.workspaceDir());
        assertNull(moved.cliSessionId()); // 对齐 TS 解构剔除 _previousCliSessionId
        assertEquals(session.status(), moved.status());
        assertNotEquals(session.updatedAt(), moved.updatedAt());
        assertTrue(store.saveCalls > savesBeforeMove, "换值应触发持久化");

        // 同值 → 返回原实例，updatedAt 不变，不触发 persist
        int savesBeforeSame = store.saveCalls;
        String before = moved.updatedAt();
        Session same = manager.setWorkspaceDir(session.id(), "D:/ws-new");
        assertSame(moved, same);
        assertEquals(before, same.updatedAt());
        assertEquals(savesBeforeSame, store.saveCalls, "同值不落盘");

        IllegalArgumentException exEmpty = assertThrows(IllegalArgumentException.class,
                () -> manager.setWorkspaceDir(session.id(), ""));
        assertEquals("工作目录不能为空", exEmpty.getMessage());

        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class,
                () -> manager.setWorkspaceDir(session.id(), null));
        assertEquals("工作目录不能为空", exNull.getMessage());
    }

    // ---------- persist 失败回滚 ----------

    @Test
    void resolvePersistFailureRollsBackAndRetries() throws IOException {
        SupplierPair sp = counters();
        InMemoryStore store = new InMemoryStore(List.of());
        store.failSave = true;
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), store);

        assertThrows(IOException.class,
                () -> manager.resolve(address("om-1", "oc-1", "ot-1", null)));
        assertEquals(0, manager.size());

        // 修复 store 后可重新 resolve 出同 key 新会话
        store.failSave = false;
        ResolvedSession retry = manager.resolve(address("om-1", "oc-1", "ot-1", null));
        assertTrue(retry.isNew());
        assertEquals(1, manager.size());
    }

    @Test
    void transitionPersistFailureKeepsPreviousState() throws IOException {
        SupplierPair sp = counters();
        InMemoryStore store = new InMemoryStore(List.of());
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), store);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        store.failSave = true;
        assertThrows(IOException.class,
                () -> manager.transition(session.id(), SessionStatus.ACTIVE));
        // map 中该 key 仍是迁移前状态对象（get 按 id 从 map 扫描）
        assertEquals(SessionStatus.CREATING, manager.get(session.id()).status());
        assertEquals(1, manager.size());
    }

    @Test
    void cliSelectionPersistFailureRollsBack() throws IOException {
        SupplierPair sp = counters();
        InMemoryStore store = new InMemoryStore(List.of());
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), store);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        store.failSave = true;
        assertThrows(IOException.class, () -> manager.setCliSessionId(session.id(), "cli-abc"));
        assertNull(manager.get(session.id()).cliSessionId());

        store.failSave = false;
        Session moved = manager.setCliSessionId(session.id(), "cli-abc"); // 修复后成功
        assertEquals("cli-abc", moved.cliSessionId());

        store.failSave = true;
        assertThrows(IOException.class, () -> manager.setWorkspaceDir(session.id(), "D:/ws-2"));
        // 工作目录回滚为原值，CLI 会话选择也保持
        assertEquals(session.workspaceDir(), manager.get(session.id()).workspaceDir());
        assertEquals("cli-abc", manager.get(session.id()).cliSessionId());
    }

    // ---------- open / size / get / persist 快照 ----------

    @Test
    void openRestoresSessionsWithRebuiltKeys() throws IOException {
        SupplierPair sp = counters();
        Session preset1 = new Session("s-1", "boss", "ot-1", "oc-1", CliId.CLAUDE,
                "D:/ws", SessionStatus.IDLE, "2026-01-01T00:00:00.000Z",
                "2026-01-01T00:00:00.000Z", "cli-1");
        Session preset2 = new Session("s-2", "dev", "ot-2", "oc-2", CliId.CODEX,
                "D:/ws2", SessionStatus.CLOSED, "2026-01-01T00:00:00.000Z",
                "2026-01-01T00:00:00.000Z", null);
        InMemoryStore store = new InMemoryStore(List.of(preset1, preset2));

        SessionManager manager = SessionManager.open(sp.now(), sp.createId(), store);
        assertEquals(2, manager.size());
        assertSame(preset1, manager.get("s-1"));
        assertSame(preset2, manager.get("s-2"));

        // 按 botId:chatId:threadId 重建 key：同地址 resolve 命中既有会话
        ResolvedSession resolved = manager.resolve(address("om-x", "oc-1", "ot-1", null),
                CliId.CLAUDE, "boss", "D:/ws");
        assertFalse(resolved.isNew());
        assertSame(preset1, resolved.session());
    }

    @Test
    void sizeAndGetScanById() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        assertEquals(0, manager.size());
        Session a = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();
        Session b = manager.resolve(address("om-2", "oc-2", "ot-2", null)).session();
        assertEquals(2, manager.size());
        assertSame(a, manager.get(a.id()));
        assertSame(b, manager.get(b.id()));
        assertNull(manager.get("unknown-id"));
    }

    @Test
    void persistSnapshotIsCopyOfMapContent() throws IOException {
        SupplierPair sp = counters();
        InMemoryStore store = new InMemoryStore(List.of());
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), store);
        manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();
        manager.resolve(address("om-2", "oc-2", "ot-2", null)).session();

        List<List<Session>> snapshots = store.savedSnapshots;
        assertEquals(2, snapshots.size());
        assertEquals(manager.size(), snapshots.get(1).size());
        // 快照是拷贝：改快照不影响 manager 内部
        snapshots.get(1).clear();
        assertEquals(2, manager.size());
    }

    // ---------- SessionStatus 序列化形态 ----------

    @Test
    void sessionStatusIdsAreLowercaseStrings() {
        assertEquals("creating", SessionStatus.CREATING.id());
        assertEquals("active", SessionStatus.ACTIVE.id());
        assertEquals("idle", SessionStatus.IDLE.id());
        assertEquals("closed", SessionStatus.CLOSED.id());
        assertEquals(SessionStatus.CREATING, SessionStatus.fromId("creating"));
        assertNull(SessionStatus.fromId("nope"));
    }

    @Test
    void sessionWithMethodsPreserveImmutableCopy() throws IOException {
        SupplierPair sp = counters();
        SessionManager manager = new SessionManager(sp.now(), sp.createId(), null);
        Session session = manager.resolve(address("om-1", "oc-1", "ot-1", null)).session();

        Session withStatus = session.withStatus(SessionStatus.ACTIVE, "t2");
        assertEquals(session.id(), withStatus.id());
        assertEquals(SessionStatus.ACTIVE, withStatus.status());
        assertEquals("t2", withStatus.updatedAt());
        assertEquals(SessionStatus.CREATING, session.status()); // 原实例不变

        Session withCli = withStatus.withCliSessionId("cli-1", "t3");
        assertEquals("cli-1", withCli.cliSessionId());

        Session withWs = withCli.withWorkspaceDir("D:/ws-x", "t4");
        assertEquals("D:/ws-x", withWs.workspaceDir());
        assertNull(withWs.cliSessionId()); // 换目录同时清空 CLI 会话选择
    }
}

package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.nio.file.Path;

/**
 * 话题会话状态机：(botId, chatId, threadId) → Session。对应 TS 版 src/core/session-manager.ts。
 * now/createId/store 均可注入（测试用）；now 默认输出 ISO 毫秒字符串（对齐 toISOString）。
 * persist 失败时回滚内存态再透传异常（对齐 TS 的 try/catch 回删/回写）。
 */
public final class SessionManager {
    private static final Map<SessionStatus, List<SessionStatus>> ALLOWED_TRANSITIONS = Map.of(
            SessionStatus.CREATING, List.of(SessionStatus.ACTIVE, SessionStatus.IDLE, SessionStatus.CLOSED),
            SessionStatus.ACTIVE, List.of(SessionStatus.IDLE, SessionStatus.CLOSED),
            SessionStatus.IDLE, List.of(SessionStatus.ACTIVE, SessionStatus.CLOSED),
            SessionStatus.CLOSED, List.of());

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Supplier<String> now;
    private final Supplier<String> createId;
    private final SessionStore store;

    public SessionManager() {
        this(IsoTime::now, () -> UUID.randomUUID().toString(), null);
    }

    public SessionManager(Supplier<String> now, Supplier<String> createId, SessionStore store) {
        this.now = now;
        this.createId = createId;
        this.store = store;
    }

    /** 对齐 static open()：从 store 恢复既有会话。 */
    public static SessionManager open(SessionStore store) throws IOException {
        return open(IsoTime::now, () -> UUID.randomUUID().toString(), store);
    }

    public static SessionManager open(Supplier<String> now, Supplier<String> createId,
            SessionStore store) throws IOException {
        SessionManager manager = new SessionManager(now, createId, store);
        if (store != null) {
            for (Session session : store.load()) {
                manager.sessions.put(
                        sessionKey(session.botId(), session.chatId(), session.threadId()), session);
            }
        }
        return manager;
    }

    public int size() {
        return sessions.size();
    }

    /** 按 sessionId 线性扫描（对齐 TS find），未知返回 null。 */
    public Session get(String sessionId) {
        for (Session session : sessions.values()) {
            if (session.id().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    public ResolvedSession resolve(MessageAddress message) throws IOException {
        return resolve(message, CliId.CLAUDE, "default",
                Path.of("").toAbsolutePath().toString());
    }

    public ResolvedSession resolve(MessageAddress message, CliId cliId, String botId,
            String workspaceDir) throws IOException {
        String threadId = topicIdOf(message);
        String key = sessionKey(botId, message.chatId(), threadId);
        Session existing = sessions.get(key);
        if (existing != null) {
            return new ResolvedSession(existing, false);
        }
        String nowIso = now.get();
        Session session = new Session(createId.get(), botId, threadId, message.chatId(),
                cliId, workspaceDir, SessionStatus.CREATING, nowIso, nowIso, null);
        sessions.put(key, session);
        try {
            persist();
        } catch (IOException e) {
            if (sessions.get(key) == session) {
                sessions.remove(key);
            }
            throw e;
        }
        return new ResolvedSession(session, true);
    }

    public Session transition(String sessionId, SessionStatus nextStatus) throws IOException {
        Session current = get(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (!ALLOWED_TRANSITIONS.get(current.status()).contains(nextStatus)) {
            throw new IllegalArgumentException(
                    "会话 " + current.status().id() + " 不能切换到 " + nextStatus.id());
        }
        Session updated = current.withStatus(nextStatus, now.get());
        return replaceTracked(current, updated);
    }

    public Session setCliSessionId(String sessionId, String cliSessionId) throws IOException {
        if (cliSessionId == null || cliSessionId.isEmpty()) {
            throw new IllegalArgumentException("CLI 会话 ID 不能为空");
        }
        return updateCliSelection(sessionId, cliSessionId);
    }

    public Session clearCliSessionId(String sessionId) throws IOException {
        return updateCliSelection(sessionId, null);
    }

    private Session updateCliSelection(String sessionId, String cliSessionId) throws IOException {
        Session current = get(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return replaceTracked(current, current.withCliSessionId(cliSessionId, now.get()));
    }

    public Session setWorkspaceDir(String sessionId, String workspaceDir) throws IOException {
        Session current = get(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        if (workspaceDir == null || workspaceDir.isEmpty()) {
            throw new IllegalArgumentException("工作目录不能为空");
        }
        if (current.workspaceDir().equals(workspaceDir)) {
            return current; // 对齐 TS：同值直接返回，不触发持久化
        }
        return replaceTracked(current, current.withWorkspaceDir(workspaceDir, now.get()));
    }

    /** 三处更新共用的「写入 + 失败回滚」骨架（对齐 TS 各方法重复的 try/catch）。 */
    private Session replaceTracked(Session current, Session updated) throws IOException {
        String key = sessionKey(updated.botId(), updated.chatId(), updated.threadId());
        sessions.put(key, updated);
        try {
            persist();
        } catch (IOException e) {
            if (sessions.get(key) == updated) {
                sessions.put(key, current);
            }
            throw e;
        }
        return updated;
    }

    private void persist() throws IOException {
        if (store != null) {
            store.save(new ArrayList<>(sessions.values()));
        }
    }

    /** 对齐 topicIdOf()：threadId || rootId || messageId（空串视为 falsy）。 */
    static String topicIdOf(MessageAddress message) {
        if (message.threadId() != null && !message.threadId().isEmpty()) {
            return message.threadId();
        }
        if (message.rootId() != null && !message.rootId().isEmpty()) {
            return message.rootId();
        }
        return message.messageId();
    }

    static String sessionKey(String botId, String chatId, String threadId) {
        return botId + ":" + chatId + ":" + threadId;
    }
}

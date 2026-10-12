package com.leftrr.agentscheduling.core;

import java.io.IOException;
import java.util.List;

/**
 * 会话持久化接口。对应 TS 版 src/core/session-store.ts 的 SessionStore
 * （TS Promise → Java 受检 IOException；实现 JsonSessionStore 在 Task 4）。
 */
public interface SessionStore {
    List<Session> load() throws IOException;
    void save(List<Session> sessions) throws IOException;
}

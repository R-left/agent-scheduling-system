package com.leftrr.agentscheduling.core;

import java.io.IOException;
import java.util.List;

/** 会话持久化接口。 */
public interface SessionStore {
    List<Session> load() throws IOException;
    void save(List<Session> sessions) throws IOException;
}

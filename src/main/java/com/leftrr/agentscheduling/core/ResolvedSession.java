package com.leftrr.agentscheduling.core;

/**
 * 对齐 TS 版 src/core/session-manager.ts 的 ResolvedSession 接口。
 */
public record ResolvedSession(Session session, boolean isNew) {}

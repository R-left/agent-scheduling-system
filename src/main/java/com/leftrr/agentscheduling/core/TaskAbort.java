package com.leftrr.agentscheduling.core;

import java.util.Map;

public final class TaskAbort {
    private TaskAbort() {
    }

    public static AbortTaskOutcome requestTaskAbort(
            Map<String, ActiveRun> activeRuns,
            String sessionId,
            String operatorOpenId) {
        ActiveRun active = activeRuns.get(sessionId);
        if (active == null) return AbortTaskOutcome.NOT_FOUND;
        if (operatorOpenId == null || operatorOpenId.isEmpty()) {
            return AbortTaskOutcome.FORBIDDEN;
        }
        // CAS 保证并发下恰好一个 STOPPED；cancelMode 只在真正取消成功后写入
        if (active.token().cancel()) {
            active.setCancelMode(CancelMode.STOP);
            return AbortTaskOutcome.STOPPED;
        }
        return AbortTaskOutcome.ALREADY_STOPPING;
    }
}

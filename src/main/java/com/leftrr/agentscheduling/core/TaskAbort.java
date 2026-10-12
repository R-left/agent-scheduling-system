package com.leftrr.agentscheduling.core;

import java.util.Map;

/**
 * 任务中止纯逻辑。对应 TS 版 src/core/task-abort.ts 的 requestTaskAbort。
 */
public final class TaskAbort {
    private TaskAbort() {
    }

    /**
     * 时序契约：cancelMode 在 {@code token.cancel()} 触发的全部取消监听器执行完毕之后
     * 才可见；取消回调（onCancel）内读取 {@link ActiveRun#cancelMode()} 恒为 null，
     * 禁止在回调中依赖它。
     */
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

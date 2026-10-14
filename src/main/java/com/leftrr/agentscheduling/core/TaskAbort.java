package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.dto.core.AbortTaskOutcome;
import com.leftrr.agentscheduling.dto.core.CancelMode;
import java.util.Map;

/**
 * 任务中止纯逻辑。
 */
public final class TaskAbort {
    private TaskAbort() {
    }

    /**
     * 时序坑：cancelMode 要等 token.cancel() 触发的全部取消回调跑完才可见，
     * onCancel 里读 ActiveRun.cancelMode() 恒为 null，别在回调里依赖它。
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
        // cancel() 自带原子性，并发下恰好一个 STOPPED；cancelMode 只在真取消成功后写
        if (active.token().cancel()) {
            active.setCancelMode(CancelMode.STOP);
            return AbortTaskOutcome.STOPPED;
        }
        return AbortTaskOutcome.ALREADY_STOPPING;
    }
}

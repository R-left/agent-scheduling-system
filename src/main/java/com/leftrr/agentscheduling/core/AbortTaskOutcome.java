package com.leftrr.agentscheduling.core;

/** 任务中止纯逻辑。对应 TS 版 src/core/task-abort.ts 的 requestTaskAbort。 */
public enum AbortTaskOutcome {
    STOPPED,
    ALREADY_STOPPING,
    NOT_FOUND,
    FORBIDDEN
}

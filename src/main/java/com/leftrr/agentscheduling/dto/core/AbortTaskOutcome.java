package com.leftrr.agentscheduling.dto.core;

/** 任务中止请求的结果。 */
public enum AbortTaskOutcome {
    STOPPED,
    ALREADY_STOPPING,
    NOT_FOUND,
    FORBIDDEN
}

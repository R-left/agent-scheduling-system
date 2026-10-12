package com.leftrr.agentscheduling.core;

/** 对齐 TS 版 src/core/task-progress.ts 的 TaskActivity；detail 为 null 表示 undefined。 */
public record TaskActivity(String toolName, String label, String detail, long durationMs, boolean failed) {}

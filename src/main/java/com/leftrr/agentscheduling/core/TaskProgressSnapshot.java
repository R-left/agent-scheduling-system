package com.leftrr.agentscheduling.core;

import java.util.List;

/**
 * 对齐 TS 版 src/core/task-progress.ts 的 TaskProgressSnapshot。
 * 可空分量 null 表示 TS 的 undefined（TS 仅在字段存在时写入对象字面量）。
 */
public record TaskProgressSnapshot(
        String current,
        String currentToolName,
        String currentDetail,
        long elapsedMs,
        int toolCount,
        int completedCount,
        List<TaskActivity> activities,
        Long contextUsedTokens,
        Long contextStartTokens,
        Long contextWindowTokens,
        Boolean startedNewSession) {}

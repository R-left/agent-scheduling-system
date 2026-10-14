package com.leftrr.agentscheduling.dto.core;

import java.util.List;


/**
 * 任务进度快照。
 * 可空分量 null 表示字段缺省（字段仅在有值时写入）。
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

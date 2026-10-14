package com.leftrr.agentscheduling.dto.cli;

/** 一次运行的统计（全可选字段 → 可空包装类型）。 */
public record CliRunStats(
        Integer durationMs,
        Integer turns,
        Long totalTokens,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Long contextUsedTokens,
        Long contextWindowTokens) {

    /** 全部字段均缺省时判定为空。 */
    public boolean isEmpty() {
        return durationMs == null && turns == null && totalTokens == null && inputTokens == null
                && outputTokens == null && cacheReadTokens == null
                && cacheCreationTokens == null && contextUsedTokens == null
                && contextWindowTokens == null;
    }
}

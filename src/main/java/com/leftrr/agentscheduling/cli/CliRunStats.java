package com.leftrr.agentscheduling.cli;

/** 对齐 TS CliRunStats（全可选字段 → 可空包装类型；字段顺序与 src/cli/types.ts 一致）。 */
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

    /** TS 以 `Object.values(stats).some(v => v !== undefined)` 判定 stats 是否存在。 */
    public boolean isEmpty() {
        return durationMs == null && turns == null && totalTokens == null && inputTokens == null
                && outputTokens == null && cacheReadTokens == null
                && cacheCreationTokens == null && contextUsedTokens == null
                && contextWindowTokens == null;
    }
}

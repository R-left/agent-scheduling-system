package com.leftrr.agentscheduling.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRunStatsTest {
    @Test
    void allNullFieldsIsEmpty() {
        // 对齐 TS `Object.values(stats).some(v => v !== undefined)` 取反：全 undefined → 空
        assertTrue(new CliRunStats(null, null, null, null, null, null, null, null).isEmpty());
    }

    @Test
    void anyNonNullFieldIsNotEmpty() {
        // 任一字段非 null 即非空（至少覆盖 3 个不同字段位置）
        assertFalse(new CliRunStats(0, null, null, null, null, null, null, null).isEmpty());
        assertFalse(new CliRunStats(null, null, 0L, null, null, null, null, null).isEmpty());
        assertFalse(new CliRunStats(null, null, null, null, null, null, null, 0L).isEmpty());
    }
}

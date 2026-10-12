package com.leftrr.agentscheduling.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应 TS 版时间字符串语义（brief Task 3 Step 1 清单）：
 * Node toISOString 恒 3 位毫秒；zod v4 z.iso.datetime() 仅正则校验。
 */
class IsoTimeTest {
    @Test
    void epochMilliZeroFormatsWithThreeDigitMillis() {
        assertEquals("1970-01-01T00:00:00.000Z", IsoTime.format(Instant.ofEpochMilli(0)));
    }

    @Test
    void formatKeepsMillisWhenInstantToStringWouldOmitThem() {
        // Instant.toString() 会省略 .000，格式化器不省——对齐 Node toISOString
        assertEquals("2026-10-13T01:02:03.000Z",
                IsoTime.format(Instant.parse("2026-10-13T01:02:03Z")));
    }

    @Test
    void nowMatchesIsoMillisPattern() {
        assertTrue(IsoTime.now().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void zodDateTimeAcceptsUtcWithOptionalFractionalSeconds() {
        assertTrue(IsoTime.isZodDateTime("2026-01-01T00:00:00Z"));
        assertTrue(IsoTime.isZodDateTime("2026-01-01T00:00:00.5Z"));
        assertTrue(IsoTime.isZodDateTime("2026-01-01T00:00:00.123456789Z"));
    }

    @Test
    void zodDateTimeRejectsOffsetMissingZAndNull() {
        // zod 默认 offset=false：带时区偏移不通过
        assertFalse(IsoTime.isZodDateTime("2026-01-01T00:00:00+08:00"));
        assertFalse(IsoTime.isZodDateTime("2026-01-01T00:00:00"));
        assertFalse(IsoTime.isZodDateTime(null));
    }

    @Test
    void zodDateTimeDoesNotValidateRanges() {
        // zod 仅正则校验，不验证月日时分取值范围——"2026-13-45T99:99:99Z" 也通过
        assertTrue(IsoTime.isZodDateTime("2026-13-45T99:99:99Z"));
    }
}

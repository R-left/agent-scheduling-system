package com.leftrr.agentscheduling.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * TS 时间字符串语义工具（计划设计裁决 3）。
 * Node toISOString 恒 3 位毫秒；Instant.toString() 会省略 .000，故用固定格式（M2 NativeSessions 先例）。
 * isZodDateTime 对齐 zod v4 z.iso.datetime() 默认行为：仅正则校验，不验证月日取值范围。
 */
public final class IsoTime {
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    // zod 默认（offset=false, precision=null）：必须 UTC 'Z'，秒后小数位可选任意位数
    private static final Pattern ZOD_DATETIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

    private IsoTime() {}

    public static String format(Instant instant) {
        return ISO_MILLIS.format(instant);
    }

    /** 对齐 new Date().toISOString()。 */
    public static String now() {
        return format(Instant.now());
    }

    /** 对齐 z.iso.datetime()：仅正则判定，不校验日期合法性（"2026-13-45T99:99:99Z" 也通过）。 */
    public static boolean isZodDateTime(String value) {
        return value != null && ZOD_DATETIME.matcher(value).matches();
    }
}

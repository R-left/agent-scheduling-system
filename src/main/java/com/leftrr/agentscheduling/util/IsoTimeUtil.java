package com.leftrr.agentscheduling.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 时间戳工具类
 */
public final class IsoTimeUtil {
    private static final DateTimeFormatter ISO_MILLIS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    // 只认 Z 结尾、秒后小数位可有可无的形态
    private static final Pattern ZOD_DATETIME_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

    private IsoTimeUtil() {}

    /** 按固定形态格式化。 */
    public static String format(Instant instant) {
        return ISO_MILLIS_FORMATTER.format(instant);
    }

    /** 当前时刻的固定形态时间串。 */
    public static String now() {
        return format(Instant.now());
    }

    /** 只看长相不较真：形态对就算过（"2026-13-45T99:99:99Z" 也通过），不查日期合法性。 */
    public static boolean isZodDateTime(String value) {
        return value != null && ZOD_DATETIME_PATTERN.matcher(value).matches();
    }
}

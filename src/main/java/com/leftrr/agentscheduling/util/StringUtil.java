package com.leftrr.agentscheduling.util;

/**
 * 字符串工具类
 */
public final class StringUtil {
    private StringUtil() {}

    /**
     * 剥离首尾空白
     * @param value 字符串
     * @return string
     */
    public static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmable(value.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmable(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * 空文本判定
     * @param c 文本字符
     * @return true/false
     */
    static boolean isTrimmable(char c) {
        return switch (c) {           // TAB
            case '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　',
                 '﻿' ->
                    true;
            default -> c >= ' ' && c <= ' ';
        };
    }
}

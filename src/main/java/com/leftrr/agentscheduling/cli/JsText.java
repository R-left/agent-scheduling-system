package com.leftrr.agentscheduling.cli;

/**
 * ECMAScript 兼容文本工具：String.prototype.trim 与 \s 的语义。
 * Java String.trim() 只剥 <= U+0020，ECMAScript trim 剥 WhiteSpace + LineTerminator 全集。
 */
public final class JsText {
    private JsText() {}

    /** ECMAScript trim。入参必为非 null（null 抛 NPE）。 */
    public static String jsTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJsWhiteSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhiteSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /** ECMAScript WhiteSpace + LineTerminator 全集（转义书写，勿改为字面字符）。 */
    static boolean isJsWhiteSpace(char c) {
        switch (c) {
            case '\t':           // TAB
            case '\n':           // LF
            case '\u000B':       // VT
            case '\u000C':       // FF
            case '\r':           // CR
            case ' ':            // SP
            case '\u00A0':       // NBSP
            case '\u1680':       // OGHAM SPACE MARK
            case '\u2028':       // LINE SEPARATOR
            case '\u2029':       // PARAGRAPH SEPARATOR
            case '\u202F':       // NARROW NBSP
            case '\u205F':       // MEDIUM MATHEMATICAL SPACE
            case '\u3000':       // IDEOGRAPHIC SPACE
            case '\uFEFF':       // ZERO WIDTH NO-BREAK SPACE (BOM)
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A'; // EN QUAD .. HAIR SPACE
        }
    }
}

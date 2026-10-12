package com.leftrr.agentscheduling.im;

/** 对齐 TS 版 src/im/lark.ts 的文本限额常量与 fitFeishuText。
 * 长度与切片按 Unicode code point 计（TS Array.from 语义），见计划设计裁决 5。 */
public final class FeishuText {
    public static final int FEISHU_TEXT_LIMIT = 3_000;      // lark.ts:54
    public static final int FEISHU_MENTION_LIMIT = 1_200;   // lark.ts:55
    public static final int FEISHU_COMMENT_LIMIT = 1_000;   // lark.ts:56

    static final String TRUNCATE_SUFFIX = "\n\n（内容过长，已截断。详细内容请写入文档或工作区文件。）";

    private FeishuText() {}

    public static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 按 code point 下标 [from, to) 切片；不劈代理对。 */
    public static String codePointSlice(String text, int from, int to) {
        int length = text.length();
        int begin = text.offsetByCodePoints(0, from);
        int end = (to >= codePointLength(text)) ? length : text.offsetByCodePoints(begin, to - from);
        return text.substring(begin, end);
    }

    /** 对齐 lark.ts:58-64。 */
    public static String fitFeishuText(String text, int maxLength) {
        int length = codePointLength(text);
        if (length <= maxLength) return text;
        String suffix = TRUNCATE_SUFFIX;
        int suffixLength = codePointLength(suffix);
        int keep = Math.max(0, maxLength - suffixLength);
        return codePointSlice(text, 0, keep) + suffix;
    }
}

package com.leftrr.agentscheduling.im;

/** 飞书文本限额。
 * 长度与切片按 code point，不劈代理对——和 CardText 的 code unit 口径不同，别混。 */
public final class FeishuText {
    public static final int FEISHU_TEXT_LIMIT = 3_000;
    public static final int FEISHU_MENTION_LIMIT = 1_200;
    public static final int FEISHU_COMMENT_LIMIT = 1_000;

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

    /** 超出 maxLength 时保留头部并追加截断提示。 */
    public static String fitFeishuText(String text, int maxLength) {
        int length = codePointLength(text);
        if (length <= maxLength) return text;
        String suffix = TRUNCATE_SUFFIX;
        int suffixLength = codePointLength(suffix);
        int keep = Math.max(0, maxLength - suffixLength);
        return codePointSlice(text, 0, keep) + suffix;
    }
}

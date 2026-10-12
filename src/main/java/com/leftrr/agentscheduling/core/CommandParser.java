package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 斜杠命令与 /claude、/codex 直达请求解析。
 * 对应 TS 版 src/core/command-parser.ts。正则逐字移植，统一加
 * UNICODE_CHARACTER_CLASS 以对齐 JS \s 对 NBSP 等 Unicode 空白的匹配。
 * trim 语义对齐 ECMAScript：{@link #jsTrim(String)} 剥离 NBSP( )、﻿ 等
 * Unicode 空白（Java String.trim() 只剥 ≤ U+0020，String.strip() 同样不含 NBSP）。
 */
public final class CommandParser {
    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern COMMAND_RE = Pattern.compile(
            "^(?:@.+?\\s+)?/(close|status|help|new|resume|team)\\s*$", UNICODE);
    private static final Pattern CD_RE = Pattern.compile(
            "^(?:@.+?\\s+)?/cd(?:\\s+([\\s\\S]+?))?\\s*$", UNICODE);
    private static final Pattern COMPACT_RE = Pattern.compile(
            "^(?:@.+?\\s+)?/compact(?:\\s+([\\s\\S]+?))?\\s*$", UNICODE);
    private static final Pattern CLI_REQUEST_RE = Pattern.compile(
            "^(?:@.+?\\s+)?/(claude|codex)(?:\\s+([\\s\\S]*))?$", UNICODE);

    private CommandParser() {
    }

    public static Optional<SlashCommand> parseCommand(String text) {
        String value = jsTrim(text);
        Matcher cd = CD_RE.matcher(value);
        if (cd.matches()) {
            return Optional.of(new SlashCommand.CdCommand(trimToNull(cd.group(1))));
        }
        Matcher compact = COMPACT_RE.matcher(value);
        if (compact.matches()) {
            return Optional.of(new SlashCommand.CompactCommand(trimToNull(compact.group(1))));
        }
        Matcher simple = COMMAND_RE.matcher(value);
        if (!simple.matches()) return Optional.empty();
        return Optional.of(new SlashCommand.SimpleCommand(
                SlashCommand.SimpleName.valueOf(simple.group(1).toUpperCase(Locale.ROOT))));
    }

    public static Optional<CliRequest> parseCliRequest(String text) {
        Matcher matcher = CLI_REQUEST_RE.matcher(jsTrim(text));
        if (!matcher.matches()) return Optional.empty();
        CliId cliId = CliId.fromId(matcher.group(1)).orElseThrow();
        String prompt = matcher.group(2) == null ? "" : jsTrim(matcher.group(2));
        return Optional.of(new CliRequest(cliId, prompt));
    }

    /**
     * 对齐 TS 版 {@code cdMatch[1]?.trim() || undefined}：捕获组缺失或内容全为空白时
     * 归一为 null（对应 TS 的 undefined），而非空串。
     */
    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = jsTrim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 按 ECMAScript White_Space 集剥离首尾空白（对齐 JS {@code String.prototype.trim}）：
     * {@code \t \n \u000B \f \r 空格      -          　 ﻿}。
     * 不用 {@code String.trim()}（只剥 ≤ U+0020）也不用 {@code String.strip()}
     * （Character.isWhitespace 同样不含 NBSP/﻿）。
     */
    private static String jsTrim(String value) {
        if (value == null) return null;
        int head = 0;
        int tail = value.length();
        while (head < tail && isJsWhiteSpace(value.charAt(head))) head++;
        while (tail > head && isJsWhiteSpace(value.charAt(tail - 1))) tail--;
        return head == 0 && tail == value.length() ? value : value.substring(head, tail);
    }

    private static boolean isJsWhiteSpace(char c) {
        return c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'
                || c == ' ' || c == ' ' || c == ' '
                || (c >= ' ' && c <= ' ')
                || c == ' ' || c == ' ' || c == ' '
                || c == ' ' || c == '　' || c == '﻿';
    }
}

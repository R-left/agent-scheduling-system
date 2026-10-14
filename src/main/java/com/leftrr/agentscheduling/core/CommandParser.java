package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.util.StringUtil;
import com.leftrr.agentscheduling.dto.cli.CliId;
import com.leftrr.agentscheduling.dto.core.CliRequest;
import com.leftrr.agentscheduling.dto.core.SlashCommand;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 斜杠命令与 /claude、/codex 直达请求解析。
 * 正则统一开 UNICODE_CHARACTER_CLASS，让 \s 认得 NBSP 等 Unicode 空白；
 * trim 用 StringUtil.trim（Java 的 trim/strip 剥不掉 NBSP、U+FEFF）。
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
        String value = StringUtil.trim(text);
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
        Matcher matcher = CLI_REQUEST_RE.matcher(StringUtil.trim(text));
        if (!matcher.matches()) return Optional.empty();
        CliId cliId = CliId.fromId(matcher.group(1)).orElseThrow();
        String prompt = matcher.group(2) == null ? "" : StringUtil.trim(matcher.group(2));
        return Optional.of(new CliRequest(cliId, prompt));
    }

    /** 捕获组缺失或内容全空白归一为 null（而非空串）。 */
    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = StringUtil.trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }
}

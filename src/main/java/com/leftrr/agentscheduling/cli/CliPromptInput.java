package com.leftrr.agentscheduling.cli;

import java.util.Locale;

/**
 * 对齐 TS CliPromptInput（'argument' | 'stdin'）。
 * Windows 上 prompt 必须走 stdin（避免 cmd 对命令行参数转义/乱码），其他平台直接走参数。
 */
public enum CliPromptInput {
    ARGUMENT, STDIN;

    /** 对齐 promptInputForPlatform(platform)：win32 → STDIN，其余 → ARGUMENT。 */
    public static CliPromptInput forPlatform(String platform) {
        return "win32".equals(platform) ? STDIN : ARGUMENT;
    }

    /** 当前运行平台对应的取值。 */
    public static CliPromptInput current() {
        return forPlatform(currentPlatform());
    }

    /**
     * process.platform 的等价判定：Java 侧只区分 win32 与其他（本项目其余平台行为一致）。
     */
    public static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("windows") ? "win32" : "other";
    }
}

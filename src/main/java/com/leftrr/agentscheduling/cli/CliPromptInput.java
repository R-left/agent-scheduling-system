package com.leftrr.agentscheduling.cli;

import java.util.Locale;

/**
 * prompt 传递方式：命令行参数或 stdin。
 * Windows 上 prompt 必须走 stdin（避免 cmd 对命令行参数转义/乱码），其他平台直接走参数。
 */
public enum CliPromptInput {
    ARGUMENT, STDIN;

    /** win32 → STDIN，其余 → ARGUMENT。 */
    public static CliPromptInput forPlatform(String platform) {
        return "win32".equals(platform) ? STDIN : ARGUMENT;
    }

    /** 当前运行平台对应的取值。 */
    public static CliPromptInput current() {
        return forPlatform(currentPlatform());
    }

    /**
     * 平台判定：只区分 win32 与其他（本项目其余平台行为一致）。
     */
    public static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("windows") ? "win32" : "other";
    }
}

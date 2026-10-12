package com.leftrr.agentscheduling.cli;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliPromptInputTest {
    @Test
    void stdinOnlyOnWin32() {
        // Windows 上 prompt 必须走 stdin，其他平台（含未知平台）走参数
        assertEquals(CliPromptInput.STDIN, CliPromptInput.forPlatform("win32"));
        assertEquals(CliPromptInput.ARGUMENT, CliPromptInput.forPlatform("linux"));
        assertEquals(CliPromptInput.ARGUMENT, CliPromptInput.forPlatform("darwin"));
        assertEquals(CliPromptInput.ARGUMENT, CliPromptInput.forPlatform(null));
    }

    @Test
    void currentPlatformDerivesFromOsName() {
        // 期望值按 os.name 推导而非硬编码，避免未来跨平台跑挂
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String expected = os.contains("windows") ? "win32" : "other";
        assertEquals(expected, CliPromptInput.currentPlatform());
    }

    @Test
    void currentFollowsCurrentPlatform() {
        assertEquals(CliPromptInput.forPlatform(CliPromptInput.currentPlatform()),
                CliPromptInput.current());
    }
}

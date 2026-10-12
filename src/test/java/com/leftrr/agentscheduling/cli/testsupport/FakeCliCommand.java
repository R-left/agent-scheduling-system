package com.leftrr.agentscheduling.cli.testsupport;

import java.util.ArrayList;
import java.util.List;

/** 构造「当前 JVM 启动 FakeCli」的命令行（测试注入 CliProcess/TestAdapter 用）。 */
public final class FakeCliCommand {
    private FakeCliCommand() {}

    public static List<String> command(String... modes) {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        // 真实 claude/codex CLI 均按 UTF-8 输出；中文 Windows 上子 JVM 的 System.out 默认走
        // 本机编码（GBK），父进程按 UTF-8 读取会得到 U+FFFD 乱码，故在此固定子进程流编码。
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path", ""));
        command.add(FakeCli.class.getName());
        command.addAll(List.of(modes));
        return command;
    }
}

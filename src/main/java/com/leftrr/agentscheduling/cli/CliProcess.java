package com.leftrr.agentscheduling.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨平台 CLI 子进程 spawn 与进程树级 kill，对齐 TS spawn-cli.ts。
 * Windows 下 claude/codex 常以 .cmd 批处理形式存在，必须走 shell（cmd）才能找到并启动；
 * prompt 已由适配器改为 stdin 传递，命令行里只剩无空格的标志参数，shell 拼接不会破坏它们。
 * kill 用 taskkill /t /f 连进程树一起清，避免 cmd 外壳被杀后 claude.exe/codex.exe 变孤儿。
 */
public final class CliProcess {
    private CliProcess() {}

    public static boolean isWindows() {
        return "win32".equals(CliPromptInput.currentPlatform());
    }

    /** Node spawn(shell:true) 在 Windows 的等价命令行拼接。参数含空白或为空时加引号。 */
    static String windowsShellCommandLine(String command, List<String> args) {
        List<String> parts = new ArrayList<>();
        parts.add(command);
        parts.addAll(args);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteForCmd(parts.get(i)));
        }
        return sb.toString();
    }

    private static String quoteForCmd(String value) {
        if (value != null && !value.isEmpty()
                && value.chars().noneMatch(c -> c == ' ' || c == '\t')) {
            return value;
        }
        String safe = value == null ? "" : value.replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    static List<String> spawnCommand(String command, List<String> args) {
        if (isWindows()) {
            return List.of("cmd", "/d", "/s", "/c", windowsShellCommandLine(command, args));
        }
        List<String> all = new ArrayList<>();
        all.add(command);
        all.addAll(args);
        return all;
    }

    /** 对齐 spawnCli()。cwd 必传（TS 调用点均显式传 cwd）。 */
    public static Process spawn(String command, List<String> args, String cwd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spawnCommand(command, args));
        pb.directory(new File(cwd));
        return pb.start();
    }

    /** taskkill 参数构造（纯函数，测试用）。 */
    static List<String> taskkillArgs(long pid) {
        return List.of("/pid", String.valueOf(pid), "/t", "/f");
    }

    /**
     * 对齐 killCli()：Windows 用 taskkill /t /f 杀整棵进程树（fire-and-forget，不等待）；
     * 其他平台 destroy()（SIGTERM 语义）。
     *
     * 注意：{@link #taskkillArgs(long)} 只含参数部分（纯函数，CliProcessTest 钉住形状），
     * 程序名 "taskkill" 必须在此拼为 ProcessBuilder 首元素——否则首元素 "/pid" 会被当作
     * 可执行文件（CreateProcess error=2），IOException 回退 destroy() 只杀 cmd 外壳，
     * 孙进程变孤儿并握住 stdout 管道，调用方 EOF 晚到数秒（Task 4 超时/取消测试暴露）。
     * 输出重定向 DISCARD（对齐 TS stdio:'ignore'），不产生可能被 GC 关闭的未读管道。
     */
    public static void kill(Process child) {
        if (isWindows()) {
            try {
                List<String> command = new ArrayList<>();
                command.add("taskkill");
                command.addAll(taskkillArgs(child.pid()));
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException e) {
                child.destroy(); // taskkill 起不来时退回直接杀，尽力清理
            }
        } else {
            child.destroy();
        }
    }
}

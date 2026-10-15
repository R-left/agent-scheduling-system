package com.leftrr.agentscheduling.cli;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨平台 CLI 子进程 spawn 与进程树级 kill。
 * Windows 下 claude/codex 常是 .cmd 批处理，必须经 cmd 启动；prompt 走 stdin，
 * 命令行里的标志参数经 quoteForCmd 转义（含空白或双引号的都会包引号），shell 拼接不会破坏它们。
 */
public final class CliProcess {
    private CliProcess() {}

    public static boolean isWindows() {
        return "win32".equals(CliPromptInput.currentPlatform());
    }

    /** Windows shell（cmd）命令行拼接。参数含空白、双引号或为空时加引号。 */
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

    /**
     * 含空白或双引号的参数必须包引号并转义内部引号（\"）。只看空格是坑：
     * cmd 会把未加引号参数里的裸双引号整个剥掉——claude 的 --mcp-config 内联
     * JSON、codex 的 -c TOML 值都含引号，裸传到 CLI 手里就成了没引号的残串，
     * JSON/TOML 解析失败（claude 还会回退把整串当配置文件路径找文件）。
     */
    private static String quoteForCmd(String value) {
        if (value != null && !value.isEmpty()
                && value.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) {
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

    /** cwd 必传。 */
    public static Process spawn(String command, List<String> args, String cwd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spawnCommand(command, args));
        pb.directory(new File(cwd));
        return pb.start();
    }

    /** taskkill 参数构造（纯函数）。 */
    static List<String> taskkillArgs(long pid) {
        return List.of("/pid", String.valueOf(pid), "/t", "/f");
    }

    /**
     * Windows 用 taskkill /t /f 杀整棵进程树（不等它结束）；其他平台 destroy()。
     * 大坑：程序名 "taskkill" 必须是 ProcessBuilder 列表首元素——只传参数列表的话
     * 首元素 "/pid" 会被当成可执行文件（CreateProcess error=2），IOException 回退
     * destroy() 只杀 cmd 外壳，孙进程变孤儿还握着 stdout 管道，EOF 晚到数秒。
     * 输出重定向 DISCARD，避免未读管道被 GC 关闭。
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

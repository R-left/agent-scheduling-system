package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.testsupport.FakeCliCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CliProcessTest {

    // ---------- windowsShellCommandLine：Node spawn(shell:true) 的等价拼接 ----------

    @Test
    void 无空格参数不加引号() {
        assertEquals("claude --resume abc-1 -p",
                CliProcess.windowsShellCommandLine("claude", List.of("--resume", "abc-1", "-p")));
    }

    @Test
    void 含空格参数加引号() {
        assertEquals("java -cp \"a b\" X",
                CliProcess.windowsShellCommandLine("java", List.of("-cp", "a b", "X")));
    }

    @Test
    void 空参数加引号() {
        assertEquals("cmd \"\"",
                CliProcess.windowsShellCommandLine("cmd", List.of("")));
    }

    @Test
    void 含空格参数的内部引号转义() {
        // 内部 `"` 转义为 `\"`（quoteForCmd 在需要引号的参数上同步转义内部引号）
        assertEquals("cmd \"a b\\\"c\"",
                CliProcess.windowsShellCommandLine("cmd", List.of("a b\"c")));
    }

    // ---------- spawnCommand：Windows 走 cmd 外壳，其他平台原样 ----------

    @Test
    void windows下spawnCommand包装cmd外壳() {
        assumeTrue(CliProcess.isWindows());
        assertEquals(List.of("cmd", "/d", "/s", "/c", "claude --resume abc-1"),
                CliProcess.spawnCommand("claude", List.of("--resume", "abc-1")));
    }

    @Test
    void 非windows下spawnCommand原样返回() {
        assumeFalse(CliProcess.isWindows());
        assertEquals(List.of("claude", "--resume", "abc-1"),
                CliProcess.spawnCommand("claude", List.of("--resume", "abc-1")));
    }

    // ---------- taskkillArgs ----------

    @Test
    void taskkillArgs为杀进程树四参数() {
        assertEquals(List.of("/pid", "1234", "/t", "/f"), CliProcess.taskkillArgs(1234));
    }

    // ---------- 集成：真实子进程 spawn + 进程树 kill ----------

    @Test
    void spawn后kill能终止进程树(@TempDir Path tmpDir) throws Exception {
        assumeTrue(CliProcess.isWindows());
        // FakeCliCommand.command() 返回完整列表：[java, -cp, <cp>, FakeCli, hang:30000]
        // spawn(command, args, cwd) 第一参是命令、其余是参数列表 → 拆成 command + rest
        List<String> full = FakeCliCommand.command("hang:30000");
        Process child = CliProcess.spawn(full.get(0), full.subList(1, full.size()), tmpDir.toString());
        assertTrue(child.isAlive(), "hang 模式下子进程应存活");
        // 杀树前等 cmd 外壳 spawn 出 java 孙进程（descendants 非空），把 /t 树级语义真正钉住：
        // 若只杀 cmd 外壳，孙进程将存活到自然退出，下方后代断言即失败
        List<Long> treePids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            child.descendants().forEach(handle -> treePids.add(handle.pid()));
            if (!treePids.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertFalse(treePids.isEmpty(), "10s 内应观测到孙进程（cmd 外壳 spawn 的 java），否则无法验证树级 kill");
        CliProcess.kill(child);
        // Windows 上 taskkill /t /f 杀进程树；给足超时避免偶发慢
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "kill 后子进程应在超时内退出");
        // 被 taskkill 强杀，退出码非 0
        int exit = child.waitFor();
        assertNotEquals(0, exit, "被杀进程退出码应非 0，实际: " + exit);
        // 所有后代进程全部不再存活（给 taskkill 短暂收尾窗口再终判）
        for (int i = 0; i < 50; i++) {
            boolean anyAlive = false;
            for (long pid : treePids) {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) {
                break;
            }
            Thread.sleep(100);
        }
        for (long pid : treePids) {
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "孙进程 " + pid + " 应被进程树级 kill 清理");
        }
    }

    @Test
    void spawn非windows下kill用destroy(@TempDir Path tmpDir) throws Exception {
        assumeFalse(CliProcess.isWindows());
        List<String> full = FakeCliCommand.command("hang:5000");
        Process child = CliProcess.spawn(full.get(0), full.subList(1, full.size()), tmpDir.toString());
        assertTrue(child.isAlive());
        CliProcess.kill(child);
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "destroy 后子进程应在超时内退出");
    }

    @Test
    void spawn的cwd目录生效(@TempDir Path tmpDir) throws Exception {
        List<String> full = FakeCliCommand.command("cwd");
        Process child = CliProcess.spawn(full.get(0), full.subList(1, full.size()), tmpDir.toString());
        // 先读完 stdout（避免管道阻塞）再等退出
        byte[] out = child.getInputStream().readAllBytes();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "cwd 模式应很快退出");
        assertEquals(0, child.exitValue(), "cwd 模式应正常退出，stderr: "
                + new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(new String(out, StandardCharsets.UTF_8));
        // user.dir 与 @TempDir 可能存在大小写/分隔符差异，比较前都规范化
        String actual = normalize(json.get("cwd").asText());
        String expected = normalize(tmpDir.toString());
        if (CliProcess.isWindows()) {
            // Windows 文件系统不区分大小写，规范化后仍可能存在大小写差异
            assertTrue(expected.equalsIgnoreCase(actual),
                    "子进程 cwd 应等于临时目录（忽略大小写比较），actual=" + actual + " expected=" + expected);
        } else {
            assertEquals(expected, actual, "子进程 cwd 应等于临时目录");
        }
    }

    private static String normalize(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }
}

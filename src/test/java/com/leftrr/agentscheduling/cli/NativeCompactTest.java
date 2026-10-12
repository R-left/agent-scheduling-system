package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.testsupport.FakeCliCommand;
import com.leftrr.agentscheduling.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativeCompact 行为清单（brief Task 8 共 14 组用例）。
 * 统一用 StubAdapter 固定 buildCompactPlan 返回的 plan（plan 内为当前 JVM 启动 FakeCli 的命令），
 * FakeCli 扮演子进程；timeoutMs 用 Options 短值注入。文案断言逐字对齐 TS native-compact.ts。
 */
class NativeCompactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong FIXTURE_SEQ = new AtomicLong();
    private static final String CLAUDE_DISPLAY = "Claude Code";
    private static final String CODEX_DISPLAY = "Codex";
    private static final String PROMPT = "/compact 整理一下上下文";
    private static final String SESSION_ID = "sess-compact-1";
    private static final String NOT_ENOUGH_MESSAGE = "当前上下文还不需要整理。继续使用一段时间后再试。";

    @TempDir
    Path tmpDir;

    // ---------- 测试替身 ----------

    /** 固定 buildCompactPlan 返回值的最小适配器（plan 由各测试按 fixture 构造）。 */
    private static final class StubAdapter implements CliAdapter {
        private final CliId id;
        private final String displayName;
        private final CliCompactPlan plan;

        StubAdapter(CliId id, String displayName, CliCompactPlan plan) {
            this.id = id;
            this.displayName = displayName;
            this.plan = plan;
        }

        @Override public CliId id() { return id; }
        @Override public String command() { return FakeCliCommand.command().get(0); }
        @Override public String displayName() { return displayName; }
        @Override public List<String> buildArgs(String prompt, CliPromptInput promptInput) {
            return List.of();
        }
        @Override public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
            return List.of();
        }
        @Override public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
            return plan;
        }
        @Override public List<CliEvent> parseEvents(String line) {
            return List.of();
        }
    }

    // ---------- 测试工具 ----------

    private Path fixture(String... lines) throws IOException {
        Path file = tmpDir.resolve("fixture-" + FIXTURE_SEQ.incrementAndGet() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    /** Claude 分支 plan：命令即 FakeCli 完整命令行，prompt 走 stdin。 */
    private CliCompactPlan claudePlan(String... modes) {
        List<String> full = FakeCliCommand.command(modes);
        return new CliCompactPlan.ClaudeStreamJson(full.get(0), full.subList(1, full.size()), PROMPT);
    }

    /** Codex 分支 plan：FakeCli app-server 脚本（绝对路径，app-server: 用 substring 取参）。 */
    private CliCompactPlan codexPlan(Path script) {
        List<String> full = FakeCliCommand.command("app-server:" + script);
        return new CliCompactPlan.CodexAppServer(full.get(0), full.subList(1, full.size()), SESSION_ID);
    }

    private Path writeScript(ObjectNode script) throws IOException {
        Path file = tmpDir.resolve("script-" + FIXTURE_SEQ.incrementAndGet() + ".json");
        Files.writeString(file, MAPPER.writeValueAsString(script), StandardCharsets.UTF_8);
        return file;
    }

    private NativeCompact.Result compact(CliAdapter adapter, CancellationToken cancellation,
                                         Long timeoutMs) throws Exception {
        return NativeCompact.compactCliSession(new NativeCompact.Options(
                adapter, SESSION_ID, tmpDir.toString(), null, cancellation, timeoutMs));
    }

    private static List<JsonNode> readRecord(Path recordFile) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        for (String line : Files.readAllLines(recordFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                messages.add(MAPPER.readTree(line));
            }
        }
        return messages;
    }

    /** 防泄漏回归断言用：直接数 token 内残留的监听器（listener 列表为私有实现细节）。 */
    private static int listenerCount(CancellationToken token) throws Exception {
        Field field = CancellationToken.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return ((List<?>) field.get(token)).size();
    }

    // ---------- Claude 分支 ----------

    // ---------- 1. 成功：boundary + stdin 恰为 prompt + \n ----------

    @Test
    void 成功返回compacted且stdin恰为prompt加换行() throws Exception {
        Path record = tmpDir.resolve("stdin-record.jsonl");
        Path fx = fixture("[{\"type\":\"system\",\"subtype\":\"compact_boundary\"},"
                + "{\"type\":\"result\",\"result\":\"done\"}]");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("stdin-record:" + record.getFileName() + ":" + fx.getFileName()));

        NativeCompact.Result result = compact(adapter, null, null);

        assertEquals(SESSION_ID, result.sessionId());
        assertTrue(result.compacted());
        assertNull(result.message());
        assertEquals(PROMPT + "\n", Files.readString(record, StandardCharsets.UTF_8),
                "写入 stdin 的内容应恰为 prompt + \\n");
    }

    // ---------- 2. 无 boundary + Not enough messages ----------

    @Test
    void 无boundary且NotEnoughMessages返回未整理提示() throws Exception {
        Path fx = fixture("{\"type\":\"result\",\"result\":\"Not enough messages to compact.\"}");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("emit:" + fx));

        NativeCompact.Result result = compact(adapter, null, null);

        assertFalse(result.compacted());
        assertEquals(NOT_ENOUGH_MESSAGE, result.message());
    }

    // ---------- 3. 无 boundary 且其他 result ----------

    @Test
    void 无boundary且其他result抛没有返回整理完成事件() throws Exception {
        Path fx = fixture("{\"type\":\"result\",\"result\":\"没有完成\"}");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("emit:" + fx));

        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, null));
        assertEquals("Claude Code 没有返回上下文整理完成事件", e.getMessage());
    }

    // ---------- 4. result is_error ----------

    @Test
    void result为isError时错误文本作为异常消息() throws Exception {
        Path fx = fixture("{\"type\":\"result\",\"is_error\":true,\"result\":\"整理失败\"}");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("emit:" + fx));

        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, null));
        assertEquals("整理失败", e.getMessage());
    }

    @Test
    void result为isError且非文本时用默认失败文案() throws Exception {
        Path fx = fixture("{\"type\":\"result\",\"is_error\":true,\"result\":123}");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("emit:" + fx));

        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, null));
        assertEquals("Claude Code 原生上下文整理失败", e.getMessage());
    }

    // ---------- 5. 退出码非 0 ----------

    @Test
    void 退出码非0时异常用stderr内容() {
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("stderr:整理出错:1"));
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, null));
        assertEquals("整理出错", e.getMessage());
    }

    @Test
    void 退出码非0且stderr为空时用ClaudeCode退出文案() throws Exception {
        // emit-fail 按 ":" 切分参数，须用相对文件名（FakeCli 的 cwd 即 runner 传入的 tmpDir）
        Path fx = tmpDir.resolve("empty-" + FIXTURE_SEQ.incrementAndGet() + ".jsonl");
        Files.writeString(fx, "", StandardCharsets.UTF_8);
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("emit-fail:" + fx.getFileName() + ":1"));

        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, null));
        assertEquals("Claude Code 退出，状态码 1", e.getMessage());
    }

    // ---------- 6. 超时 ----------

    @Test
    void 超时抛上下文整理超时且不等满子进程休眠时长() {
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY, claudePlan("hang:5000"));
        long start = System.nanoTime();
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, 400L));
        assertEquals(CLAUDE_DISPLAY + " 上下文整理超时", e.getMessage());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5000, "应在超时阈值附近返回而非等满 5s，实际 " + elapsedMs + "ms");
    }

    // ---------- 7. 取消 ----------

    @Test
    void 预取消抛已取消且子进程从未spawn() throws Exception {
        Path record = tmpDir.resolve("stdin-record-precancel.jsonl");
        Path fx = fixture("[{\"type\":\"system\",\"subtype\":\"compact_boundary\"}]");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("stdin-record:" + record.getFileName() + ":" + fx.getFileName()));
        var token = new CancellationToken();
        token.cancel();

        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, token, null));
        assertEquals(CLAUDE_DISPLAY + " 上下文整理已取消", e.getMessage());
        assertFalse(Files.exists(record), "预取消时子进程不应被 spawn");
    }

    @Test
    void 运行中取消抛已取消且尽快返回() {
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY, claudePlan("hang:5000"));
        var token = new CancellationToken();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        });
        canceller.start();
        long start = System.nanoTime();
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, token, null));
        assertEquals(CLAUDE_DISPLAY + " 上下文整理已取消", e.getMessage());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5000, "取消后应尽快返回而非等满 5s，实际 " + elapsedMs + "ms");
    }

    // ---------- 8. result 带空白仍走 Not enough messages 分支 ----------

    @Test
    void result带首尾空白仍触发未整理提示() throws Exception {
        Path fx = fixture("{\"type\":\"result\",\"result\":\"  Not enough messages to compact.  \"}");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY,
                claudePlan("emit:" + fx));

        NativeCompact.Result result = compact(adapter, null, null);
        assertFalse(result.compacted(), "jsTrim 后相等即触发第 2 条分支");
        assertEquals(NOT_ENOUGH_MESSAGE, result.message());
    }

    // ---------- 9. 通知计数：取消监听器 remove 防泄漏回归 ----------

    /**
     * per-run token 下天然满足；此断言保护实现确实在收尾 remove：
     * 成功结束后 token 内不得残留监听器；预取消路径入口即拒绝、从未注册。
     */
    @Test
    void 成功结束后与预取消后取消监听器均被移除() throws Exception {
        Path fx = fixture("[{\"type\":\"system\",\"subtype\":\"compact_boundary\"},"
                + "{\"type\":\"result\",\"result\":\"done\"}]");
        StubAdapter adapter = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY, claudePlan("emit:" + fx));

        var token = new CancellationToken();
        compact(adapter, token, null);
        assertEquals(0, listenerCount(token), "成功结束后应 remove 取消监听器，防泄漏");

        var preCancelled = new CancellationToken();
        preCancelled.cancel();
        StubAdapter hanging = new StubAdapter(CliId.CLAUDE, CLAUDE_DISPLAY, claudePlan("hang:5000"));
        assertThrows(CliExecutionException.class, () -> compact(hanging, preCancelled, null));
        assertEquals(0, listenerCount(preCancelled), "预取消路径不应残留监听器");
    }

    // ---------- Codex 分支（CodexAppServer，FakeCli app-server 脚本）----------

    // ---------- 10. 成功：contextCompaction 通知 ----------

    @Test
    void codex收到contextCompaction后返回compacted() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        script.putArray("compactNotifications").add(
                "{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"contextCompaction\"}}}");

        NativeCompact.Result result = compact(
                new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script))), null, null);
        assertEquals(SESSION_ID, result.sessionId());
        assertTrue(result.compacted());
        assertNull(result.message());
    }

    // ---------- 11. 协议序列 ----------

    @Test
    void codex协议序列initialize_initialized_resume_compactStart() throws Exception {
        Path record = tmpDir.resolve("record-protocol.jsonl");
        ObjectNode script = MAPPER.createObjectNode();
        script.put("record", record.toString());
        script.putArray("compactNotifications").add(
                "{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"contextCompaction\"}}}");

        compact(new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script))), null, null);

        List<JsonNode> received = readRecord(record);
        assertEquals(4, received.size(), "应恰好收到 initialize、initialized、thread/resume、thread/compact/start");

        JsonNode initialize = received.get(0);
        assertEquals(1, initialize.path("id").asInt());
        assertEquals("initialize", initialize.path("method").asText());
        JsonNode clientInfo = initialize.path("params").path("clientInfo");
        assertEquals("agent_os", clientInfo.path("name").asText());
        assertEquals("Agent OS", clientInfo.path("title").asText());
        assertEquals("0.1.0", clientInfo.path("version").asText());

        JsonNode initialized = received.get(1);
        assertEquals("initialized", initialized.path("method").asText());
        assertFalse(initialized.has("id"), "initialized 是通知，不带 id");

        JsonNode resume = received.get(2);
        assertEquals(2, resume.path("id").asInt());
        assertEquals("thread/resume", resume.path("method").asText());
        JsonNode resumeParams = resume.path("params");
        assertEquals(1, resumeParams.size(), "thread/resume params 恰为 {threadId}");
        assertEquals(SESSION_ID, resumeParams.path("threadId").asText());

        JsonNode start = received.get(3);
        assertEquals(3, start.path("id").asInt());
        assertEquals("thread/compact/start", start.path("method").asText());
        JsonNode startParams = start.path("params");
        assertEquals(1, startParams.size(), "thread/compact/start params 恰为 {threadId}");
        assertEquals(SESSION_ID, startParams.path("threadId").asText());
    }

    // ---------- 12. thread/resume 出错 ----------

    @Test
    void codexResumeError异常用error消息() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        script.putObject("resumeError").put("message", "resume 炸了");

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                compact(new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script))),
                        null, null));
        assertEquals("resume 炸了", e.getMessage());
    }

    @Test
    void codexError无message时回退默认文案() throws Exception {
        // FakeCli 的 resumeError 仅在有 message 时回错误；error 无 message 的回退分支
        // 经 compactNotifications 逐字打印的裸 error 行触达（语义同线程错误应答）
        ObjectNode script = MAPPER.createObjectNode();
        script.putArray("compactNotifications").add("{\"id\":9,\"error\":{\"code\":-1}}");

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                compact(new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script))),
                        null, null));
        assertEquals("原生上下文整理失败", e.getMessage());
    }

    // ---------- 13. 提前退出 ----------

    @Test
    void codex提前退出报状态码() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        script.put("exitAfterInit", 2);

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                compact(new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script))),
                        null, null));
        assertEquals("Codex app-server 提前退出，状态码 2", e.getMessage());
    }

    // ---------- 14. 超时/取消（同 Claude 分支文案）----------

    @Test
    void codex超时抛上下文整理超时() throws Exception {
        // 空脚本：compact/start 应答后 FakeCli 挂起等下一行输入，永不定局 → 触发超时
        ObjectNode script = MAPPER.createObjectNode();
        StubAdapter adapter = new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script)));
        long start = System.nanoTime();
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, null, 400L));
        assertEquals(CODEX_DISPLAY + " 上下文整理超时", e.getMessage());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5000, "应在超时阈值附近返回，实际 " + elapsedMs + "ms");
    }

    @Test
    void codex运行中取消抛已取消() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        StubAdapter adapter = new StubAdapter(CliId.CODEX, CODEX_DISPLAY, codexPlan(writeScript(script)));
        var token = new CancellationToken();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        });
        canceller.start();
        long start = System.nanoTime();
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> compact(adapter, token, null));
        assertEquals(CODEX_DISPLAY + " 上下文整理已取消", e.getMessage());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5000, "取消后应尽快返回，实际 " + elapsedMs + "ms");
    }
}

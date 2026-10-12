package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.testsupport.FakeCliCommand;
import com.leftrr.agentscheduling.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CliRunner 通用运行器行为清单（brief 13 用例）。子进程一律 FakeCli。
 * 文案断言逐字对齐 Review Focus 第 9 条；终态判定优先级对齐 Review Focus 第 7 条。
 */
class CliRunnerTest {

    private static final String DISPLAY_NAME = "测试CLI";
    private static final String PROMPT = "请用一句话回答：1+1等于几？";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong FIXTURE_SEQ = new AtomicLong();

    @TempDir
    Path tmpDir;

    // ---------- TestAdapter：java 启动 FakeCli + Jackson 把 fixture 行转回事件 ----------

    /**
     * command() 返回 java 可执行；buildArgs 返回 FakeCli 完整命令去掉首元素后的参数部分
     * （FakeCliCommand.command() 首元素是可执行本身，CliProcess.spawn 的第一参已含它）。
     * parseEvents 把 FakeCli 输出行转回 CliEvent：fixture 行即最终事件 JSON 形状，
     * 数组行展开为多个事件；无法解析的行返回空列表（对齐 CliAdapter 契约）。
     */
    private static final class TestAdapter implements CliAdapter {
        private final String[] modes;

        TestAdapter(String... modes) {
            this.modes = modes;
        }

        @Override
        public CliId id() {
            return CliId.CLAUDE;
        }

        @Override
        public String command() {
            return FakeCliCommand.command(modes).get(0);
        }

        @Override
        public String displayName() {
            return DISPLAY_NAME;
        }

        @Override
        public List<String> buildArgs(String prompt, CliPromptInput promptInput) {
            List<String> full = FakeCliCommand.command(modes);
            return full.subList(1, full.size());
        }

        @Override
        public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
            String[] resumeModes = new String[modes.length + 1];
            System.arraycopy(modes, 0, resumeModes, 0, modes.length);
            resumeModes[modes.length] = "resume:" + sessionId;
            List<String> full = FakeCliCommand.command(resumeModes);
            return full.subList(1, full.size());
        }

        @Override
        public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
            return null; // runner 不消费该能力
        }

        @Override
        public List<CliEvent> parseEvents(String line) {
            try {
                JsonNode node = MAPPER.readTree(line);
                List<CliEvent> events = new ArrayList<>();
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        CliEvent event = mapEvent(item);
                        if (event != null) {
                            events.add(event);
                        }
                    }
                } else {
                    CliEvent event = mapEvent(node);
                    if (event != null) {
                        events.add(event);
                    }
                }
                return events;
            } catch (IOException e) {
                return List.of(); // 无法解析的行返回空列表，绝不抛
            }
        }

        private static CliEvent mapEvent(JsonNode node) {
            String type = node.path("type").asText(null);
            if (type == null) {
                // echo-stdin 输出无 type 字段：{"stdin":"..."} 转回 result 事件（stdin 全文即 answer）
                if (node.has("stdin")) {
                    return new CliEvent.ResultEvent(node.get("stdin").asText(), null, null);
                }
                return null;
            }
            return switch (type) {
                case "session" -> new CliEvent.SessionEvent(node.path("sessionId").asText(null));
                case "tool_call" -> new CliEvent.ToolCallEvent(
                        node.path("toolUseId").asText(null),
                        node.path("toolName").asText(null),
                        node.path("input"));
                case "tool_end" -> new CliEvent.ToolEndEvent(
                        node.path("toolUseId").asText(null),
                        node.path("failed").asBoolean(false));
                case "stats" -> new CliEvent.StatsEvent(parseStats(node.get("stats")));
                case "result" -> new CliEvent.ResultEvent(
                        node.path("answer").asText(null),
                        node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null,
                        node.hasNonNull("stats") ? parseStats(node.get("stats")) : null);
                case "error" -> new CliEvent.ErrorEvent(
                        node.path("message").asText(null),
                        node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null);
                default -> null;
            };
        }

        private static CliRunStats parseStats(JsonNode stats) {
            if (stats == null || stats.isNull()) {
                return null;
            }
            return new CliRunStats(
                    stats.hasNonNull("durationMs") ? stats.get("durationMs").asInt() : null,
                    stats.hasNonNull("turns") ? stats.get("turns").asInt() : null,
                    stats.hasNonNull("totalTokens") ? stats.get("totalTokens").asLong() : null,
                    stats.hasNonNull("inputTokens") ? stats.get("inputTokens").asLong() : null,
                    stats.hasNonNull("outputTokens") ? stats.get("outputTokens").asLong() : null,
                    stats.hasNonNull("cacheReadTokens") ? stats.get("cacheReadTokens").asLong() : null,
                    stats.hasNonNull("cacheCreationTokens") ? stats.get("cacheCreationTokens").asLong() : null,
                    stats.hasNonNull("contextUsedTokens") ? stats.get("contextUsedTokens").asLong() : null,
                    stats.hasNonNull("contextWindowTokens") ? stats.get("contextWindowTokens").asLong() : null);
        }
    }

    // ---------- 测试工具 ----------

    private Path fixture(String... lines) throws IOException {
        Path file = tmpDir.resolve("fixture-" + UUID.randomUUID() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    private CliRunResult run(TestAdapter adapter, CancellationToken cancellation,
                             Long timeoutMs, Consumer<CliEvent> onEvent) throws Exception {
        return CliRunner.run(new CliRunner.Options(
                adapter, PROMPT, tmpDir.toString(), null, cancellation, timeoutMs, onEvent));
    }

    private CliRunResult run(TestAdapter adapter) throws Exception {
        return run(adapter, null, null, null);
    }

    // ---------- 1. 全链路 ----------

    @Test
    void 全链路事件流聚合为最终结果() throws Exception {
        Path f = fixture("[{\"type\":\"session\",\"sessionId\":\"s1\"},"
                + "{\"type\":\"tool_call\",\"toolUseId\":\"t1\",\"toolName\":\"request_clarification\",\"input\":{}},"
                + "{\"type\":\"result\",\"answer\":\"答案\",\"sessionId\":\"s1\",\"stats\":{\"totalTokens\":10}}]");
        List<CliEvent> seen = new ArrayList<>();
        CliRunResult result = run(new TestAdapter("emit:" + f), null, null, seen::add);
        assertEquals("答案", result.answer());
        assertEquals("s1", result.sessionId());
        assertNotNull(result.stats());
        assertEquals(10L, result.stats().totalTokens());
        assertNotNull(result.toolCalls());
        assertEquals(1, result.toolCalls().size());
        assertEquals("t1", result.toolCalls().get(0).toolUseId());
        assertEquals("request_clarification", result.toolCalls().get(0).toolName());
        assertEquals(List.of("SessionEvent", "ToolCallEvent", "ResultEvent"),
                seen.stream().map(event -> event.getClass().getSimpleName()).toList());
    }

    // ---------- 2. stats 晚到补挂 ----------

    @Test
    void stats事件晚到补挂进最终结果() throws Exception {
        Path f = fixture(
                "{\"type\":\"result\",\"answer\":\"答案\"}",
                "{\"type\":\"stats\",\"stats\":{\"totalTokens\":7}}");
        CliRunResult result = run(new TestAdapter("emit:" + f));
        assertEquals("答案", result.answer());
        assertNotNull(result.stats());
        assertEquals(7L, result.stats().totalTokens());
    }

    // ---------- 3. tool_call/tool_end 配对 ----------

    /**
     * 对齐 TS runner.ts（`event.type === 'tool_end' && event.failed` 才 delete）与
     * Review Focus 7「tool_call 与失败 tool_end 配对增删」：仅 failed=true 的 tool_end
     * 配对移除；failed=false 的 tool_end 不移除（brief 用例 3 前半句与 TS 冲突，以 TS 为准）。
     */
    @Test
    void 失败的tool_end配对移除而成功的保留() throws Exception {
        Path failedEnd = fixture("[{\"type\":\"tool_call\",\"toolUseId\":\"t1\",\"toolName\":\"x\",\"input\":{}},"
                + "{\"type\":\"tool_end\",\"toolUseId\":\"t1\",\"failed\":true},"
                + "{\"type\":\"result\",\"answer\":\"ok\"}]");
        CliRunResult failedResult = run(new TestAdapter("emit:" + failedEnd));
        assertNull(failedResult.toolCalls(), "failed=true 的 tool_end 应配对移除，toolCalls 为 null");

        Path okEnd = fixture("[{\"type\":\"tool_call\",\"toolUseId\":\"t1\",\"toolName\":\"x\",\"input\":{}},"
                + "{\"type\":\"tool_end\",\"toolUseId\":\"t1\",\"failed\":false},"
                + "{\"type\":\"result\",\"answer\":\"ok\"}]");
        CliRunResult okResult = run(new TestAdapter("emit:" + okEnd));
        assertNotNull(okResult.toolCalls(), "failed=false 的 tool_end 不移除，条目保留");
        assertEquals(1, okResult.toolCalls().size());
        assertEquals("t1", okResult.toolCalls().get(0).toolUseId());
    }

    // ---------- 4. tool_call 无配对 ----------

    @Test
    void 未配对的tool_call保留在toolCalls() throws Exception {
        Path f = fixture("[{\"type\":\"tool_call\",\"toolUseId\":\"t1\",\"toolName\":\"x\",\"input\":{}},"
                + "{\"type\":\"result\",\"answer\":\"ok\"}]");
        CliRunResult result = run(new TestAdapter("emit:" + f));
        assertNotNull(result.toolCalls());
        assertEquals(1, result.toolCalls().size());
        assertEquals("t1", result.toolCalls().get(0).toolUseId());
    }

    // ---------- 5. error 事件 ----------

    @Test
    void error事件文案作为异常消息抛出() throws Exception {
        Path f = fixture("{\"type\":\"error\",\"message\":\"坏了\"}");
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> run(new TestAdapter("emit:" + f)));
        assertEquals("坏了", e.getMessage());
    }

    // ---------- 6. 退出码非 0 + stderr 有内容 ----------

    @Test
    void 退出码非0且stderr有内容时异常用stderr文案() {
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> run(new TestAdapter("stderr:出错了:2")));
        assertEquals("出错了", e.getMessage());
    }

    // ---------- 7. 退出码非 0 + stderr 空 ----------

    @Test
    void 退出码非0且stderr为空时异常用displayName与状态码() throws Exception {
        // emit-fail 模式按 ":" 切分参数，绝对路径（C:\...）会破坏切分；
        // FakeCli 的工作目录即 runner 传入的 cwd（tmpDir），故用相对文件名
        Path f = tmpDir.resolve("empty-" + UUID.randomUUID() + ".jsonl");
        Files.writeString(f, "", StandardCharsets.UTF_8);
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> run(new TestAdapter("emit-fail:" + f.getFileName() + ":3")));
        assertEquals(DISPLAY_NAME + " 退出，状态码 3", e.getMessage());
    }

    // ---------- 8. 无 result ----------

    @Test
    void 正常退出但无result时抛没有返回最终结果() throws Exception {
        Path f = fixture();
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> run(new TestAdapter("emit:" + f)));
        assertEquals(DISPLAY_NAME + " 没有返回最终结果", e.getMessage());
    }

    // ---------- 9. 超时 ----------

    @Test
    void 超时抛执行超时且不等满子进程休眠时长() {
        long start = System.nanoTime();
        CliExecutionException e = assertThrows(CliExecutionException.class,
                () -> run(new TestAdapter("hang:5000"), null, 500L, null));
        assertEquals(DISPLAY_NAME + " 执行超时", e.getMessage());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // elapsed 远小于 5s 同时也是进程树级 kill 的回归保护：
        // 若 taskkill 只杀了 cmd 外壳而孤儿孙进程存活，stdout 管道会握到孤儿自然退出（约 5s）
        assertTrue(elapsedMs < 5000, "应在超时阈值附近返回而非等满 5s，实际 " + elapsedMs + "ms");
    }

    // ---------- 10. 取消 ----------

    @Test
    void 取消抛执行已取消且回调只负责杀进程() {
        // 取消回调内不读 ActiveRun.cancelMode（回调执行时它必为 null）——代码评审项：
        // CliRunner 注册的回调仅调用 CliProcess.kill(child)，无任何 cancelMode 读取
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
                () -> run(new TestAdapter("hang:5000"), token, null, null));
        assertEquals(DISPLAY_NAME + " 执行已取消", e.getMessage());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5000, "取消后应尽快返回而非等满 5s，实际 " + elapsedMs + "ms");
    }

    // ---------- 11. env 超时不可注入：直测 timeoutMs 缺省默认值路径 ----------

    @Test
    void timeoutMs缺省走默认值路径不误杀快速子进程() throws Exception {
        Path f = fixture("{\"type\":\"result\",\"answer\":\"答案\"}");
        // timeoutMs=null → CliTimeouts.fromEnv/DEFAULT_TIMEOUT_MS 路径（env 本身不可注入，CliTimeouts 已在 Task 2 覆盖）
        CliRunResult result = run(new TestAdapter("emit:" + f), null, null, null);
        assertEquals("答案", result.answer());
    }

    // ---------- 12. stdin 模式 prompt 写入 ----------

    @Test
    void stdin模式prompt全文写入子进程并回到answer() throws Exception {
        // runner 内部 promptInput = CliPromptInput.current() 不可注入，Windows 上恒为 STDIN；
        // ARGUMENT 分支不经过 runner，由适配器层 buildArgs(prompt, ARGUMENT) 单元测试覆盖（Task 5/6）
        assumeTrue(CliPromptInput.current() == CliPromptInput.STDIN, "本用例仅覆盖 STDIN 分支");
        CliRunResult result = run(new TestAdapter("echo-stdin"));
        assertNotNull(result.answer());
        assertTrue(result.answer().contains(PROMPT), "answer 应含 prompt 全文，实际: " + result.answer());
    }

    // ---------- 13. session 观测 ----------

    @Test
    void result缺sessionId时回填已观测的sessionId() throws Exception {
        Path f = fixture(
                "{\"type\":\"session\",\"sessionId\":\"obs-1\"}",
                "{\"type\":\"result\",\"answer\":\"答案\"}");
        CliRunResult result = run(new TestAdapter("emit:" + f));
        assertEquals("obs-1", result.sessionId());
    }
}

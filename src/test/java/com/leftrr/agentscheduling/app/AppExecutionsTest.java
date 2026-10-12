package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliCompactPlan;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.CliPromptInput;
import com.leftrr.agentscheduling.cli.CliRunResult;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 3：executeCli 转发形态测试（计划裁决 14：fake adapter + 真 CliRunner 跑最小事件流，
 * 复用 M2 的 FakeCli 子进程替身与 FakeCliCommand 注入方式）。
 * 对齐 TS 版 src/app/cli-execution.ts（21 行）：adapter/prompt/cwd/sessionId/onEvent 逐参转发，
 * timeoutMs 固定传 null（Java Options 独有字段，走 CliTimeouts.fromEnv 兜底，与 TS runner
 * 内部读 env 的行为一致——env 本身不可注入，超时兜底已由 CliTimeoutsTest 覆盖）。
 */
class AppExecutionsTest {

    private static final String PROMPT = "请用一句话回答：1+1等于几？";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmpDir;

    /**
     * 探针适配器：java 启动 FakeCli（emit/cwd 模式），并记录 buildArgs/buildResumeArgs
     * 收到的转发参数供断言。与 CliRunnerTest 的 TestAdapter 同构，但不追加 resume 模式
     * （FakeCli emit 幂等，无需区分新旧会话的子进程行为，只需记录转发参数）。
     */
    private static final class ProbeAdapter implements CliAdapter {
        final List<String> newSessionPrompts = new ArrayList<>();
        final List<String> resumePrompts = new ArrayList<>();
        final List<String> resumeSessionIds = new ArrayList<>();
        private final String[] modes;

        ProbeAdapter(String... modes) {
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
            return "测试CLI";
        }

        @Override
        public List<String> buildArgs(String prompt, CliPromptInput promptInput) {
            newSessionPrompts.add(prompt);
            return argsOf();
        }

        @Override
        public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
            resumePrompts.add(prompt);
            resumeSessionIds.add(sessionId);
            return argsOf();
        }

        @Override
        public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
            return null; // executeCli 不消费该能力
        }

        @Override
        public List<CliEvent> parseEvents(String line) {
            try {
                JsonNode node = MAPPER.readTree(line);
                // cwd 模式输出 {"cwd":"<user.dir>"}：子进程工作目录即转发给 runner 的 cwd
                if (node.has("cwd")) {
                    return List.of(new CliEvent.ResultEvent(node.get("cwd").asText(), null, null));
                }
                if ("result".equals(node.path("type").asText(null))) {
                    return List.of(new CliEvent.ResultEvent(
                            node.path("answer").asText(null),
                            node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null,
                            null));
                }
                return List.of();
            } catch (IOException e) {
                return List.of(); // 无法解析的行返回空列表（对齐 CliAdapter 契约）
            }
        }

        /** FakeCliCommand 首元素是可执行本身，CliProcess.spawn 的首参已含它，转发余下参数。 */
        private List<String> argsOf() {
            List<String> full = FakeCliCommand.command(modes);
            return full.subList(1, full.size());
        }
    }

    private Path fixture(String... lines) throws IOException {
        Path file = tmpDir.resolve("fixture-" + UUID.randomUUID() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file;
    }

    // ---------- 最小事件流：result 事件聚合为最终结果，prompt 与 onEvent 透传 ----------

    @Test
    void 最小事件流聚合为结果且prompt与onEvent逐参转发() throws Exception {
        Path f = fixture("{\"type\":\"result\",\"answer\":\"答案\",\"sessionId\":\"s1\"}");
        ProbeAdapter adapter = new ProbeAdapter("emit:" + f);
        List<CliEvent> seen = new ArrayList<>();
        // cancellation 传未取消的新 token：executeCli 只转发，不感知取消语义
        CliRunResult result = AppExecutions.executeCli(adapter, PROMPT, tmpDir.toString(),
                null, new CancellationToken(), seen::add);
        assertEquals("答案", result.answer());
        assertEquals("s1", result.sessionId());
        assertEquals(List.of(PROMPT), adapter.newSessionPrompts, "prompt 应原样转发给适配器");
        assertEquals(List.of("ResultEvent"),
                seen.stream().map(event -> event.getClass().getSimpleName()).toList(),
                "onEvent 应逐事件收到回调");
        assertEquals(List.of(), adapter.resumeSessionIds, "sessionId 为 null 应走新会话分支");
    }

    // ---------- sessionId 非 null 走 resume 构造；cwd 即 workspaceDir ----------

    @Test
    void sessionId转发给resume构造且子进程工作目录为workspaceDir() throws Exception {
        ProbeAdapter adapter = new ProbeAdapter("cwd");
        CliRunResult result = AppExecutions.executeCli(adapter, PROMPT, tmpDir.toString(),
                "sess-9", null, null);
        assertEquals(List.of("sess-9"), adapter.resumeSessionIds, "sessionId 应原样转发");
        assertEquals(List.of(PROMPT), adapter.resumePrompts, "resume 分支 prompt 应原样转发");
        // FakeCli cwd 模式回显子进程 user.dir，应等于转发给 runner 的 workspaceDir
        // （toRealPath 规避 Windows 盘符大小写与短路径差异）
        assertTrue(result.answer() != null && !result.answer().isEmpty());
        assertEquals(tmpDir.toRealPath(), Path.of(result.answer()).toRealPath());
    }
}

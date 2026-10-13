package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliCompactPlan;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliExecutionException;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.CliPromptInput;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.CliRunStats;
import com.leftrr.agentscheduling.cli.testsupport.FakeCliCommand;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.ResolvedSession;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 8：runProductDocumentComment 测试（brief Step 1）。
 * 对齐 TS 版 src/app/product-comment-runner.ts（80 行）：三守卫 throw 逐字（失效/仍在执行/
 * CLI 会话不存在）、本地产品方案 throw 逐字且 finally 清理、成功路径 setCliSessionId 与
 * contextWindows 回写、replyToDocumentComment 回写（answer 空串/null 兜底文案——裁决 12）、
 * prompt 逐字（含 replyId 段有无两分支）、执行期 transition(active)+activeRuns.set（ownerOpenId
 * 取 flow）、finally 身份清理（token 引用相等才删）与 markSessionIdle。
 * CLI 全流程走 M2 FakeCli 子进程替身 + 探针适配器（ClarificationRunnerTest.ProbeAdapter 同构，
 * 改在 buildResumeArgs 记录 prompt——本场景会话恒带 cliSessionId 走续跑路径）；
 * adapter 经包私有重载注入（生产路径组装见实现），公开入口仅用于守卫 throw 用例
 * （守卫先于适配器解析，不会拉起真 CLI）。
 */
class ProductCommentRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 替身 ----------

    /** 记录 replyToDocumentComment 调用的 fake Bot。 */
    private static final class FakeBot implements Bot {
        final List<IncomingDocumentComment> replyComments = new ArrayList<>();
        final List<String> replyTexts = new ArrayList<>();

        @Override
        public BotIdentity getIdentity() {
            return null;
        }

        @Override
        public String reply(String messageId, String text, boolean replyInThread) {
            return null;
        }

        @Override
        public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
            return null;
        }

        @Override
        public String replyMention(String messageId, BotIdentity target, String text,
                boolean replyInThread) {
            return null;
        }

        @Override
        public void updateCard(String messageId, JsonNode card) {
        }

        @Override
        public void subscribeToDocumentComments() {
        }

        @Override
        public void replyToDocumentComment(IncomingDocumentComment comment, String text) {
            replyComments.add(comment);
            replyTexts.add(text);
        }

        @Override
        public void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active) {
        }

        @Override
        public String downloadResource(String messageId, String fileKey, String type,
                String saveDir, String fileName) {
            return null;
        }
    }

    /**
     * 探针适配器：java 启动 FakeCli，最小事件流解析（result 一类），
     * 记录转发给 buildResumeArgs/buildArgs 的 prompt 供逐字断言。
     */
    private static final class ProbeAdapter implements CliAdapter {
        final List<String> prompts = new ArrayList<>();
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
            prompts.add(prompt);
            List<String> full = FakeCliCommand.command(modes);
            return full.subList(1, full.size());
        }

        @Override
        public List<String> buildResumeArgs(String prompt, String sessionId,
                CliPromptInput promptInput) {
            prompts.add(prompt);
            List<String> full = FakeCliCommand.command(modes);
            return full.subList(1, full.size());
        }

        @Override
        public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
            return null;
        }

        @Override
        public List<CliEvent> parseEvents(String line) {
            try {
                JsonNode node = MAPPER.readTree(line);
                if ("result".equals(node.path("type").asText(null))) {
                    return List.of(new CliEvent.ResultEvent(
                            node.path("answer").asText(null),
                            node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null,
                            statsOf(node.get("stats"))));
                }
                return List.of();
            } catch (IOException e) {
                return List.of(); // 无法解析的行返回空列表（对齐 CliAdapter 契约）
            }
        }

        private static CliRunStats statsOf(JsonNode node) {
            if (node == null || !node.isObject()) {
                return null;
            }
            return new CliRunStats(
                    node.hasNonNull("durationMs") ? node.get("durationMs").asInt() : null,
                    node.hasNonNull("turns") ? node.get("turns").asInt() : null,
                    null, null, null, null, null, null,
                    node.hasNonNull("contextWindowTokens")
                            ? node.get("contextWindowTokens").asLong() : null);
        }
    }

    /** 装配产物：运行时 + fake bot + 空闲且带 cliSessionId 的会话 + 探针适配器。 */
    private record RunFixture(AppRuntime runtime, SessionManager sessions, FakeBot bot,
            BotRegistry.BotConfig config, Session session, ProbeAdapter adapter) {
    }

    @TempDir
    Path tmpDir;

    /** 装配：主会话 IDLE 且已带 CLI 会话 ID（三守卫全过的唯一形态）。 */
    private RunFixture fixture(String... adapterModes) throws IOException {
        BotRegistry.BotConfig config = new BotRegistry.BotConfig("bot-1", "app-1", "secret",
                CliId.CLAUDE, "产品经理", List.of("lark-drive"), null, tmpDir.toString(), 3);
        SessionManager sessions = new SessionManager();
        AppRuntime runtime = new AppRuntime(sessions, new TeamRegistry("bot-1", List.of(config)));
        ResolvedSession resolved = sessions.resolve(
                new MessageAddress("om_msg_1", "oc_chat_1", "om_thread_1", "om_thread_1"),
                CliId.CLAUDE, "bot-1", tmpDir.toString());
        String sessionId = resolved.session().id();
        sessions.setCliSessionId(sessionId, "cli-sess-0");
        sessions.transition(sessionId, SessionStatus.IDLE);
        Session session = sessions.get(sessionId);

        FakeBot bot = new FakeBot();
        ProbeAdapter adapter = new ProbeAdapter(adapterModes);
        return new RunFixture(runtime, sessions, bot, config, session, adapter);
    }

    private ProductSpec.ProductSpecFlow larkFlow(RunFixture f, String taskId, String sessionId)
            throws IOException {
        return f.runtime().productSpecFlows().create(new ProductSpec.CreateFlowOptions(
                taskId, "bot-1", sessionId, "ou_owner", null, null,
                new ProductSpec.ProductSpecRequest.Lark("lark-doc", "产品方案", "概述",
                        "https://example.feishu.cn/docx/abc123")));
    }

    private ProductSpec.ProductSpecFlow localFlow(RunFixture f, String taskId, String sessionId)
            throws IOException {
        return f.runtime().productSpecFlows().create(new ProductSpec.CreateFlowOptions(
                taskId, "bot-1", sessionId, "ou_owner", null, null,
                new ProductSpec.ProductSpecRequest.Local("local", "产品方案", "概述",
                        "spec/product-spec.md", "spec/tickets")));
    }

    private static IncomingDocumentComment comment(String replyId) {
        return new IncomingDocumentComment("ev-1", "docTok", "docx", "cmt-1", replyId,
                "ou_commenter", null, true);
    }

    private static ProductCommentRunner.ProductDocumentComment options(RunFixture f,
            ProductSpec.ProductSpecFlow flow, IncomingDocumentComment comment) {
        return new ProductCommentRunner.ProductDocumentComment(
                f.runtime(), f.bot(), flow, comment);
    }

    /** 把 CLI 输出事件行写入临时 fixture 文件，返回 emit 用路径字符串。 */
    private String cliOutput(String... lines) throws IOException {
        Path file = tmpDir.resolve("cli-" + UUID.randomUUID() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file.toString();
    }

    /** TS documentCommentPrompt 数组项按序拼接（replyId 段按有无二选一）。 */
    private static String expectedPrompt(String replyIdSegment) {
        List<String> segments = new ArrayList<>();
        segments.add("用户在待确认的飞书产品方案中通过评论明确提及了你。");
        segments.add("文档 URL：https://example.feishu.cn/docx/abc123");
        segments.add("文档类型：docx");
        segments.add("评论 ID：cmt-1");
        if (replyIdSegment != null) {
            segments.add(replyIdSegment);
        }
        segments.add("使用 lark-drive 读取这一条评论、完整回复和正文位置，再使用 lark-doc 精确修改原文档。");
        segments.add("修改成功后，最终回答只写一段给评论者看的简短说明，讲清楚具体改了什么。Agent OS 会把最终回答写回原评论。");
        segments.add("不要调用评论回复或解决接口，评论是否解决由用户复查后决定。");
        segments.add("不要调用 request_spec_approval，不要生成新的确认卡；原待确认卡继续有效。");
        return String.join("\n\n", segments);
    }

    // ---------- 守卫一：会话缺失/已关闭 → throw 逐字 ----------

    @Test
    void 会话缺失时抛出逐字文案() throws Exception {
        RunFixture f = fixture();
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-missing", "missing-session");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null))));
        assertEquals("评论对应的产品会话已经失效", e.getMessage());
        assertTrue(f.bot().replyTexts.isEmpty(), "不应回写评论");
    }

    @Test
    void 会话已关闭时抛出逐字文案() throws Exception {
        RunFixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.CLOSED);
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-closed", f.session().id());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null))));
        assertEquals("评论对应的产品会话已经失效", e.getMessage());
    }

    // ---------- 守卫二：会话非空闲 → throw 逐字 ----------

    @Test
    void 会话仍在执行时抛出逐字文案() throws Exception {
        RunFixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.ACTIVE);
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-busy", f.session().id());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null))));
        assertEquals("评论对应的产品会话仍在执行其他任务", e.getMessage());
        assertTrue(f.bot().replyTexts.isEmpty());
    }

    // ---------- 守卫三：CLI 会话不存在 → throw 逐字 ----------

    @Test
    void CLI会话不存在时抛出逐字文案() throws Exception {
        RunFixture f = fixture();
        f.sessions().clearCliSessionId(f.session().id());
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-nocli", f.session().id());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null))));
        assertEquals("评论对应的产品 CLI 会话不存在", e.getMessage());
    }

    // ---------- lark-doc 守卫：本地产品方案 → throw 逐字 + finally 清理 ----------

    @Test
    void 本地产品方案抛出逐字文案且finally仍清理() throws Exception {
        RunFixture f = fixture();
        ProductSpec.ProductSpecFlow flow = localFlow(f, "task-local", f.session().id());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null)), f.session(), f.adapter()));
        assertEquals("本地产品方案不能处理飞书文档评论", e.getMessage());
        // prompt 构造在 try 内求值：throw 后 finally 仍清理 activeRuns 并标记会话空闲
        assertNull(f.runtime().activeRuns().get(f.session().id()), "finally 应清理 activeRun");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status(),
                "finally 应把已切 active 的会话标记回空闲");
        assertTrue(f.bot().replyTexts.isEmpty());
    }

    // ---------- 成功路径：回写 + 回复 + prompt 逐字（无 replyId 段） ----------

    @Test
    void 成功路径回写状态并按answer回写评论() throws Exception {
        RunFixture f = fixture("emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"已把验收标准改为三项\",\"sessionId\":\"cli-sess-9\","
                        + "\"stats\":{\"contextWindowTokens\":123456}}"));
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-ok", f.session().id());
        IncomingDocumentComment comment = comment(null);

        ProductCommentRunner.runProductDocumentComment(
                options(f, flow, comment), f.session(), f.adapter());

        assertEquals("cli-sess-9", f.sessions().get(f.session().id()).cliSessionId(),
                "CLI 会话 ID 应回写");
        assertEquals(123456L, f.runtime().contextWindows().get(f.session().id()),
                "上下文窗口应回写");
        assertEquals(List.of(comment), f.bot().replyComments, "应回写原评论");
        assertEquals(List.of("已把验收标准改为三项"), f.bot().replyTexts, "回写文本应为最终回答");
        assertEquals(List.of(expectedPrompt(null)), f.adapter().prompts,
                "prompt 应逐字等于九段拼接产物（无 replyId 段）");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status(),
                "收尾后应标记会话空闲");
        assertNull(f.runtime().activeRuns().get(f.session().id()),
                "token 引用相等的 activeRun 应被清理");
    }

    // ---------- prompt 含 replyId 段分支 ----------

    @Test
    void prompt在带replyId时插入触发回复段() throws Exception {
        RunFixture f = fixture("emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"已修改\",\"sessionId\":\"cli-sess-r\"}"));
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-reply", f.session().id());

        ProductCommentRunner.runProductDocumentComment(
                options(f, flow, comment("repl-1")), f.session(), f.adapter());

        assertEquals(List.of(expectedPrompt("触发回复 ID：repl-1")), f.adapter().prompts,
                "prompt 应含「触发回复 ID」段");
    }

    // ---------- 兜底文案：answer 空串 / null → 已按评论更新原文档，请复查。 ----------
    // CliRunner 对空 answer 的 result 事件不落 finalResult（runner.ts:111-113 同款真值过滤），
    // 子进程替身路径无法交付空 answer，兜底为防御分支——经 observeAndReply 缝注入构造结果直达。

    @Test
    void answer为空串时回写兜底文案() throws Exception {
        RunFixture f = fixture();
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-empty", f.session().id());

        ProductCommentRunner.observeAndReply(options(f, flow, comment(null)), f.session(),
                new CliRunResult("", "cli-sess-e", null, null));

        assertEquals(List.of("已按评论更新原文档，请复查。"), f.bot().replyTexts,
                "空串 answer 应回写兜底文案（裁决 12）");
        assertEquals("cli-sess-e", f.sessions().get(f.session().id()).cliSessionId(),
                "sessionId 与 answer 独立，照常回写");
    }

    @Test
    void answer缺失时回写兜底文案() throws Exception {
        RunFixture f = fixture();
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-null", f.session().id());

        ProductCommentRunner.observeAndReply(options(f, flow, comment(null)), f.session(),
                new CliRunResult(null, "cli-sess-n", null, null));

        assertEquals(List.of("已按评论更新原文档，请复查。"), f.bot().replyTexts,
                "null answer 应回写兜底文案（裁决 12）");
    }

    // ---------- contextWindowTokens 为 0 不回写（裁决 12） ----------

    @Test
    void stats上下文窗口为0时不回写contextWindows() throws Exception {
        RunFixture f = fixture("emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"已修改\",\"sessionId\":\"cli-sess-0\","
                        + "\"stats\":{\"contextWindowTokens\":0}}"));
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-zero", f.session().id());

        ProductCommentRunner.runProductDocumentComment(
                options(f, flow, comment(null)), f.session(), f.adapter());

        assertEquals("cli-sess-0", f.sessions().get(f.session().id()).cliSessionId(),
                "sessionId 与 stats 独立，照常回写");
        assertTrue(f.runtime().contextWindows().isEmpty(), "contextWindowTokens 为 0 不回写");
    }

    // ---------- 执行期：transition(active) + activeRuns.set（ownerOpenId 取 flow） ----------

    @Test
    void 执行期切active并登记ownerOpenId的activeRun() throws Exception {
        // emit-hang：先立即吐结果事件、再挂 3s（emit 文件用相对名，FakeCli 按子进程 cwd=工作目录解析）
        Files.writeString(tmpDir.resolve("cli-emit-run.jsonl"),
                "{\"type\":\"result\",\"answer\":\"已修改\",\"sessionId\":\"cli-sess-h\"}",
                StandardCharsets.UTF_8);
        RunFixture f = fixture("emit-hang:cli-emit-run.jsonl:3000");
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-run", f.session().id());
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null)), f.session(), f.adapter());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        worker.start();

        ActiveRun observed = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            observed = f.runtime().activeRuns().get(f.session().id());
            if (observed != null) {
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(observed != null, "执行期应登记 activeRun");
        assertEquals("ou_owner", observed.ownerOpenId(), "ownerOpenId 应取 flow.ownerOpenId");
        assertEquals(SessionStatus.ACTIVE, f.sessions().get(f.session().id()).status(),
                "执行期会话应处于 active");

        worker.join(30_000);
        assertFalse(worker.isAlive(), "执行应收尾完成");
        assertNull(failure.get(), "不应有意外异常: " + failure.get());
        assertNull(f.runtime().activeRuns().get(f.session().id()), "收尾应清理 activeRun");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status());
    }

    // ---------- finally 身份清理：token 引用不等不误删 ----------

    @Test
    void activeRun身份不匹配时不清理() throws Exception {
        Files.writeString(tmpDir.resolve("cli-emit-mismatch.jsonl"),
                "{\"type\":\"result\",\"answer\":\"已修改\",\"sessionId\":\"cli-sess-m\"}",
                StandardCharsets.UTF_8);
        RunFixture f = fixture("emit-hang:cli-emit-mismatch.jsonl:3000");
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-mismatch", f.session().id());
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null)), f.session(), f.adapter());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        worker.start();

        // 等执行中的 activeRun 出现后用占位者顶替（另一 token，引用不等）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && f.runtime().activeRuns().get(f.session().id()) == null) {
            Thread.sleep(20);
        }
        ActiveRun placeholder = new ActiveRun(new CancellationToken(), "ou_other");
        f.runtime().activeRuns().put(f.session().id(), placeholder);

        worker.join(30_000);
        assertFalse(worker.isAlive());
        assertSame(placeholder, f.runtime().activeRuns().get(f.session().id()),
                "身份不匹配的 activeRun 应保留");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status(),
                "markSessionIdle 仍应执行");
    }

    // ---------- CLI 失败：异常上抛但 finally 仍清理 ----------

    @Test
    void CLI失败时异常上抛且finally仍清理() throws Exception {
        RunFixture f = fixture("stderr:引擎崩溃");
        ProductSpec.ProductSpecFlow flow = larkFlow(f, "task-fail", f.session().id());

        assertThrows(CliExecutionException.class,
                () -> ProductCommentRunner.runProductDocumentComment(
                        options(f, flow, comment(null)), f.session(), f.adapter()));

        assertTrue(f.runtime().activeRuns().isEmpty(), "失败路径 finally 应清理 activeRun");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status(),
                "失败路径 finally 应标记会话空闲");
        assertTrue(f.bot().replyTexts.isEmpty(), "失败路径不回写评论");
    }
}

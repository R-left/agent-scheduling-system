package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliCompactPlan;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.CliPromptInput;
import com.leftrr.agentscheduling.cli.CliRunStats;
import com.leftrr.agentscheduling.cli.testsupport.FakeCliCommand;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.ResolvedSession;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 6：continueClarificationFlow 测试（brief Step 1）。
 * 对齐 TS 版 src/app/clarification-runner.ts（228 行）：session 缺失/进度卡 null throw 逐字、
 * 成功路径 setCliSessionId 与 contextWindows 回写（stats 0 不回写——裁决 12）、
 * 心跳 push 与 finally shutdownNow、分支 A/B/C 三正例（A 命中即 return）、
 * 长答案续传分片、catch 取消/失败两版收尾卡、finally activeRuns 身份清理与 markSessionIdle。
 * CLI 全流程走 M2 FakeCli 子进程替身 + 探针适配器（AppExecutionsTest.ProbeAdapter 同构，
 * 附加 tool_call/stats 解析）；adapter/heartbeat 经包私有重载注入（生产路径组装见实现，
 * 裁决 7），心跳以「运行期至少一次 push + shutdownNow」钉住，不做毫秒级时序断言（最不脆弱）。
 */
class ClarificationRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 替身 ----------

    /** 记录 replyCard/updateCard/reply/replyMention 的 fake Bot；replyCard 可注入 null。 */
    private static final class FakeBot implements Bot {
        String cardMessageId = "om-card-1";
        final List<JsonNode> updatedCards = new ArrayList<>();
        final List<String> replies = new ArrayList<>();
        String mentionText;
        BotIdentity mentionTarget;
        String mentionToMessageId;
        boolean mentionReplyInThread;
        int mentionCalls;
        String progressCardToMessageId;
        JsonNode progressCard;
        boolean progressReplyInThread;

        @Override
        public BotIdentity getIdentity() {
            return null;
        }

        @Override
        public String reply(String messageId, String text, boolean replyInThread) {
            replies.add(text);
            return "om-reply-" + replies.size();
        }

        @Override
        public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
            progressCardToMessageId = messageId;
            progressCard = card;
            progressReplyInThread = replyInThread;
            return cardMessageId;
        }

        @Override
        public String replyMention(String messageId, BotIdentity target, String text,
                boolean replyInThread) {
            mentionCalls++;
            mentionToMessageId = messageId;
            mentionTarget = target;
            mentionText = text;
            mentionReplyInThread = replyInThread;
            return "om-mention-1";
        }

        @Override
        public void updateCard(String messageId, JsonNode card) {
            updatedCards.add(card);
        }

        @Override
        public void subscribeToDocumentComments() {
        }

        @Override
        public void replyToDocumentComment(IncomingDocumentComment comment, String text) {
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

    /** 卡片 header 模板色（TaskCards 状态样式：running=blue/success=green/failed=red/cancelled=grey）。 */
    private static String headerTemplate(JsonNode card) {
        return card.get("header").get("template").asText();
    }

    /**
     * 探针适配器：java 启动 FakeCli，最小事件流解析（result/tool_call 两类），
     * 记录转发给 buildArgs 的 prompt 供断言。与 AppExecutionsTest.ProbeAdapter 同构。
     */
    private static final class ProbeAdapter implements CliAdapter {
        final List<String> newSessionPrompts = new ArrayList<>();
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
            // FakeCliCommand 首元素是可执行本身，CliProcess.spawn 的首参已含它，转发余下参数
            List<String> full = FakeCliCommand.command(modes);
            return full.subList(1, full.size());
        }

        @Override
        public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
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
                if ("tool_call".equals(node.path("type").asText(null))) {
                    return List.of(new CliEvent.ToolCallEvent(
                            node.path("toolUseId").asText(null),
                            node.path("toolName").asText(null),
                            node.get("input")));
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
                    longOrNull(node, "totalTokens"),
                    longOrNull(node, "inputTokens"),
                    longOrNull(node, "outputTokens"),
                    longOrNull(node, "cacheReadTokens"),
                    longOrNull(node, "cacheCreationTokens"),
                    longOrNull(node, "contextUsedTokens"),
                    longOrNull(node, "contextWindowTokens"));
        }

        private static Long longOrNull(JsonNode node, String field) {
            return node.hasNonNull(field) ? node.get(field).asLong() : null;
        }
    }

    /** 装配产物：运行时 + fake bot + 会话（ACTIVE）+ 已答完的澄清 flow + 探针适配器 + 心跳调度器。 */
    private record RunFixture(AppRuntime runtime, SessionManager sessions, FakeBot bot,
            BotRegistry.BotConfig config, Session session, Clarification.ClarificationFlow flow,
            ProbeAdapter adapter, ScheduledExecutorService heartbeat, CancellationToken run) {
    }

    @TempDir
    Path tmpDir;

    private ScheduledExecutorService heartbeatOfLastFixture;

    @AfterEach
    void shutdownHeartbeat() {
        // throw 路径（session 缺失等）不走 finally shutdownNow，这里兜底防止线程泄漏
        if (heartbeatOfLastFixture != null) {
            heartbeatOfLastFixture.shutdownNow();
        }
    }

    /** 装配：会话 ACTIVE、澄清 flow 全部问题已作答（续跑 prompt 用）。 */
    private RunFixture fixture(List<String> skills, String... adapterModes) throws IOException {
        BotRegistry.BotConfig config = new BotRegistry.BotConfig("bot-1", "app-1", "secret",
                CliId.CLAUDE, "产品经理", skills, null, tmpDir.toString(), 3);
        SessionManager sessions = new SessionManager();
        AppRuntime runtime = new AppRuntime(sessions, new TeamRegistry("bot-1", List.of(config)));
        ResolvedSession resolved = sessions.resolve(
                new MessageAddress("om_msg_1", "oc_chat_1", "om_thread_1", "om_thread_1"),
                CliId.CLAUDE, "bot-1", tmpDir.toString());
        sessions.transition(resolved.session().id(), SessionStatus.ACTIVE);
        Session session = sessions.get(resolved.session().id());

        Clarification.ClarificationRequest request = new Clarification.ClarificationRequest(
                "需求澄清", "", List.of(new Clarification.Question("q1", "范围？",
                List.of(new Clarification.Option("a", "方案A"), new Clarification.Option("b", "方案B")),
                null)));
        Clarification.ClarificationFlow flow = runtime.clarificationFlows().create(
                new Clarification.CreateFlowOptions("task-1", "bot-1", session.id(), "ou_owner",
                        null, null, "om_original", null, false, request));
        runtime.clarificationFlows().answer(flow.token(), "q1", "全量");

        FakeBot bot = new FakeBot();
        ProbeAdapter adapter = new ProbeAdapter(adapterModes);
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "test-clarification-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatOfLastFixture = heartbeat;
        return new RunFixture(runtime, sessions, bot, config, session, flow, adapter,
                heartbeat, new CancellationToken());
    }

    /** 把 CLI 输出事件行写入临时 fixture 文件，返回 emit 用路径字符串。 */
    private String cliOutput(String... lines) throws IOException {
        Path file = tmpDir.resolve("cli-" + UUID.randomUUID() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file.toString();
    }

    /** 组装 options。注意：除 session 缺失用例测公开入口外，其余测试一律走包私有重载注入
     * 探针适配器——公开入口按注册表解析真实 claude/codex CLI，误走会拉起真 CLI 进程。 */
    private static ClarificationRunner.ClarificationContinuation options(RunFixture f) {
        return new ClarificationRunner.ClarificationContinuation(f.runtime(), f.bot(), f.config(),
                f.flow(), f.run(), ProductDeliveryMode.LARK_DOC);
    }

    /** 反射读取 productSpecFlows 内部 Map（core 类未暴露只读视图，同 CollaborationServiceTest 做法）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, ProductSpec.ProductSpecFlow> productSpecFlows(
            ProductSpec.ProductSpecFlowStore store) throws Exception {
        var field = ProductSpec.ProductSpecFlowStore.class.getDeclaredField("flows");
        field.setAccessible(true);
        return (Map<String, ProductSpec.ProductSpecFlow>) field.get(store);
    }

    // ---------- Step 1①：session 缺失 throw 逐字 ----------

    @Test
    void session缺失时抛出逐字文案() throws Exception {
        RunFixture f = fixture(List.of());
        Clarification.ClarificationRequest request = new Clarification.ClarificationRequest(
                "需求澄清", "", List.of(new Clarification.Question("q1", "范围？",
                List.of(new Clarification.Option("a", "方案A"), new Clarification.Option("b", "方案B")),
                null)));
        Clarification.ClarificationFlow orphan = f.runtime().clarificationFlows().create(
                new Clarification.CreateFlowOptions("task-2", "bot-1", "missing-session",
                        "ou_owner", null, null, "om_original", null, false, request));
        ClarificationRunner.ClarificationContinuation options =
                new ClarificationRunner.ClarificationContinuation(f.runtime(), f.bot(), f.config(),
                        orphan, f.run(), ProductDeliveryMode.LARK_DOC);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ClarificationRunner.continueClarificationFlow(options));
        assertEquals("需求澄清对应的会话已经失效", e.getMessage());
        assertNull(f.bot().progressCardToMessageId, "不应发进度卡");
    }

    // ---------- Step 1②：进度卡 replyCard null → throw 逐字 ----------

    @Test
    void 进度卡replyCard返回null时抛出逐字文案() throws Exception {
        RunFixture f = fixture(List.of());
        f.bot().cardMessageId = null;

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat()));
        assertEquals("飞书没有返回需求整理进度卡片的 message_id", e.getMessage());
        // 进度卡参数：回复到原消息、replyInThread 透传、标题 = adapter.displayName · 执行中
        assertEquals("om_original", f.bot().progressCardToMessageId);
        assertFalse(f.bot().progressReplyInThread);
        assertEquals("测试CLI · 执行中",
                f.bot().progressCard.get("header").get("title").get("content").asText());
    }

    // ---------- 成功路径：setCliSessionId / contextWindows 回写 ----------

    @Test
    void 成功路径回写cliSessionId与contextWindows且清理activeRun() throws Exception {
        RunFixture f = fixture(List.of(), "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"整理完成\",\"sessionId\":\"cli-sess-9\","
                        + "\"stats\":{\"contextWindowTokens\":123456,\"totalTokens\":100}}"));
        // 身份匹配的 activeRun：finally 应移除
        ActiveRun activeRun = new ActiveRun(f.run(), "ou_owner");
        f.runtime().activeRuns().put(f.session().id(), activeRun);

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertEquals("cli-sess-9", f.sessions().get(f.session().id()).cliSessionId(),
                "CLI 会话 ID 应回写");
        assertEquals(123456L, f.runtime().contextWindows().get(f.session().id()),
                "上下文窗口应回写");
        assertEquals(List.of(Clarification.formatClarificationAnswers(f.flow())),
                f.adapter().newSessionPrompts, "续跑 prompt 应为澄清答案格式化产物");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status(),
                "收尾后应标记会话空闲");
        assertNull(f.runtime().activeRuns().get(f.session().id()),
                "token 引用相等的 activeRun 应被清理");
    }

    @Test
    void stats上下文窗口为0时不回写contextWindows() throws Exception {
        RunFixture f = fixture(List.of(), "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"整理完成\",\"sessionId\":\"cli-sess-0\","
                        + "\"stats\":{\"contextWindowTokens\":0}}"));

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertEquals("cli-sess-0", f.sessions().get(f.session().id()).cliSessionId(),
                "sessionId 与 stats 独立，照常回写");
        assertTrue(f.runtime().contextWindows().isEmpty(), "contextWindowTokens 为 0 不回写（裁决 12）");
    }

    @Test
    void activeRun身份不匹配时不清理() throws Exception {
        RunFixture f = fixture(List.of(), "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"整理完成\",\"sessionId\":\"cli-sess-1\"}"));
        // 占位者是另一个 token 的 ActiveRun（引用不等）：finally 不得误删
        ActiveRun otherRun = new ActiveRun(new CancellationToken(), "ou_other");
        f.runtime().activeRuns().put(f.session().id(), otherRun);

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertSame(otherRun, f.runtime().activeRuns().get(f.session().id()),
                "身份不匹配的 activeRun 应保留");
    }

    // ---------- 心跳：周期 push 进度卡 + finally shutdownNow ----------

    @Test
    void 心跳周期push进度卡且finally关闭心跳() throws Exception {
        // CLI 先挂 2.5s 再出结果：heartbeat 在 ~1s 起 push，经 1s 节流窗口在 ~2s 落到 updateCard
        RunFixture f = fixture(List.of(), "hang:2500", "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"整理完成\",\"sessionId\":\"cli-sess-h\"}"));

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertTrue(f.heartbeat().isShutdown(), "finally 应 shutdownNow 心跳调度器");
        assertTrue(f.bot().updatedCards.size() >= 2,
                "心跳应至少 push 一次进度卡 + 收尾一张卡，实际 "
                        + f.bot().updatedCards.size());
        for (int i = 0; i < f.bot().updatedCards.size() - 1; i++) {
            assertEquals("blue", headerTemplate(f.bot().updatedCards.get(i)),
                    "收尾卡之前的更新都应是 running 进度卡");
        }
        assertEquals("green", headerTemplate(f.bot().updatedCards.getLast()), "收尾卡应为 success");
    }

    // ---------- 分支 A：grill-me + findClarificationRequest 命中 ----------

    @Test
    void 分支A创建新澄清流程发澄清卡并通知() throws Exception {
        RunFixture f = fixture(List.of("grill-me", "to-spec"), "emit:" + cliOutput(
                "{\"type\":\"tool_call\",\"toolUseId\":\"t1\",\"toolName\":\"request_clarification\","
                        + "\"input\":{\"title\":\"补充澄清\",\"questions\":[{\"id\":\"n1\","
                        + "\"prompt\":\"优先级？\",\"options\":[{\"id\":\"p\",\"label\":\"P0\"},"
                        + "{\"id\":\"q\",\"label\":\"P1\"}]}]}}",
                "{\"type\":\"tool_call\",\"toolUseId\":\"t2\",\"toolName\":\"request_spec_approval\","
                        + "\"input\":{\"deliveryMode\":\"lark-doc\",\"title\":\"方案\","
                        + "\"summary\":\"概述\",\"documentUrl\":\"https://example.feishu.cn/docx/abc123\"}}",
                "{\"type\":\"result\",\"answer\":\"需要补充优先级\",\"sessionId\":\"cli-a\"}"));

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        // 新澄清流程落地：请求来自 tool_call、卡片消息指向进度卡
        Clarification.ClarificationFlow nextFlow =
                f.runtime().clarificationFlows().findForTask("task-1", "bot-1");
        assertNotNull(nextFlow, "分支 A 应创建新一轮澄清流程");
        assertEquals("补充澄清", nextFlow.request().title());
        assertEquals(1, nextFlow.request().questions().size());
        assertEquals("om-card-1", nextFlow.cardMessageId(), "新流程卡片应指向进度卡消息");
        assertEquals(f.session().id(), nextFlow.sessionId());
        // 澄清卡：含作答按钮；@通知文案逐字
        assertTrue(f.bot().updatedCards.getLast().toString().contains("answer_clarification"),
                "收尾应更新为澄清卡");
        assertEquals("还需要确认 1 个问题，请在上方卡片中选择。", f.bot().mentionText);
        assertEquals("om_original", f.bot().mentionToMessageId);
        assertEquals("ou_owner", f.bot().mentionTarget.openId());
        assertEquals("", f.bot().mentionTarget.name());
        // A 命中即 return：即使 skills 含 to-spec 且结果带 request_spec_approval 也不进分支 B
        assertTrue(productSpecFlows(f.runtime().productSpecFlows()).isEmpty(),
                "分支 A 命中后不应创建产品流程");
        assertTrue(f.bot().replies.isEmpty(), "分支 A 不发文本回复");
    }

    // ---------- 分支 B：to-spec + local 交付 ----------

    @Test
    void 分支B本地交付校验文档后创建产品流程并通知() throws Exception {
        // local 模式要求 spec 文件与 tickets 目录（含 .md）真实存在
        Files.createDirectories(tmpDir.resolve("spec/tickets"));
        Files.writeString(tmpDir.resolve("spec/product-spec.md"), "# 方案", StandardCharsets.UTF_8);
        Files.writeString(tmpDir.resolve("spec/tickets/t1.md"), "- 工单", StandardCharsets.UTF_8);
        RunFixture f = fixture(List.of("to-spec"), "emit:" + cliOutput(
                "{\"type\":\"tool_call\",\"toolUseId\":\"t1\",\"toolName\":\"request_spec_approval\","
                        + "\"input\":{\"deliveryMode\":\"local\",\"title\":\"标题\","
                        + "\"summary\":\"概述\",\"specPath\":\"spec/product-spec.md\","
                        + "\"ticketsPath\":\"spec/tickets\"}}",
                "{\"type\":\"result\",\"answer\":\"方案已提交\",\"sessionId\":\"cli-b\","
                        + "\"stats\":{\"contextWindowTokens\":4200}}"));
        ClarificationRunner.ClarificationContinuation options =
                new ClarificationRunner.ClarificationContinuation(f.runtime(), f.bot(), f.config(),
                        f.flow(), f.run(), ProductDeliveryMode.LOCAL);

        ClarificationRunner.continueClarificationFlow(options, f.session(), f.adapter(),
                f.heartbeat());

        Map<String, ProductSpec.ProductSpecFlow> flows =
                productSpecFlows(f.runtime().productSpecFlows());
        assertEquals(1, flows.size(), "分支 B 应创建产品确认流程");
        ProductSpec.ProductSpecFlow specFlow = flows.values().iterator().next();
        assertEquals("task-1", specFlow.taskId());
        assertEquals("bot-1", specFlow.botId());
        assertEquals(f.session().id(), specFlow.sessionId());
        assertEquals("ou_owner", specFlow.ownerOpenId());
        assertEquals(new ProductSpec.ProductSpecRequest.Local("local", "标题", "概述",
                "spec/product-spec.md", "spec/tickets"), specFlow.request());
        assertEquals("产品方案已生成，请查看上方确认卡。", f.bot().mentionText);
        assertEquals("cli-b", f.sessions().get(f.session().id()).cliSessionId());
        assertEquals(4200L, f.runtime().contextWindows().get(f.session().id()));
        // 收尾卡 = 产品确认卡（含方案标题）；分支 B 不发文本回复
        assertTrue(f.bot().updatedCards.getLast().toString().contains("标题"),
                "收尾应更新为产品确认卡");
        assertTrue(f.bot().replies.isEmpty(), "分支 B 不发文本回复");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status());
    }

    // ---------- 分支 C：结果卡 + 短答案不续传 ----------

    @Test
    void 分支C发结果卡通知且短答案不续传() throws Exception {
        RunFixture f = fixture(List.of(), "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"整理完成\",\"sessionId\":\"cli-c\"}"));

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertEquals("green", headerTemplate(f.bot().updatedCards.getLast()), "收尾应为 success 卡");
        assertTrue(f.bot().replies.isEmpty(), "短答案不应分片续传");
        assertEquals("需求澄清已完成，请查看上方结果。", f.bot().mentionText);
        assertEquals("om_original", f.bot().mentionToMessageId);
        assertTrue(f.runtime().clarificationFlows().findForTask("task-1", "bot-1") == f.flow(),
                "不应创建新澄清流程");
        assertTrue(productSpecFlows(f.runtime().productSpecFlows()).isEmpty());
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status());
    }

    // ---------- 分支 C：长答案分片续传 ----------

    @Test
    void 分支C长答案按续传规则分片回复() throws Exception {
        String answer = "长".repeat(13_000);
        RunFixture f = fixture(List.of(), "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"" + answer + "\",\"sessionId\":\"cli-c2\"}"));

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertTrue(com.leftrr.agentscheduling.im.CardText.answerNeedsContinuation(answer),
                "13000 字答案应触发续传");
        // ⑬ 独立手算期望值（不调用 splitLongText/answerContinuation，避免循环论证）：
        // 13000 字全无换行 → 预览截断无段落/行可回退、取满 6000 → 续传段为余下 7000 字；
        // 7000 > 4000 且无换行 → 按上限硬切为 4000 + 3000 两条回复
        assertEquals(List.of("长".repeat(4_000), "长".repeat(3_000)), f.bot().replies,
                "分片应等于手算期望：4000 字 + 3000 字两条");
        // 独立性质断言：每条分片不超续传分片上限，拼接还原续传段全量内容
        assertTrue(f.bot().replies.stream().allMatch(chunk -> chunk.length() <= 4_000),
                "每条分片不得超 4000 上限");
        assertEquals(7_000, f.bot().replies.stream().mapToInt(String::length).sum(),
                "分片拼接应完整覆盖续传段 7000 字");
        assertEquals("需求澄清已完成，请查看上方结果。", f.bot().mentionText);
    }

    // ---------- catch：取消 → cancelled 卡文案 ----------

    @Test
    void 取消后发cancelled收尾卡且不含错误详情() throws Exception {
        RunFixture f = fixture(List.of(), "emit:" + cliOutput(
                "{\"type\":\"result\",\"answer\":\"不会到达\",\"sessionId\":\"cli-x\"}"));
        f.runtime().activeRuns().put(f.session().id(), new ActiveRun(f.run(), "ou_owner"));
        f.run().cancel(); // 取消先于执行：executeCli 立即抛已取消

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertEquals("grey", headerTemplate(f.bot().updatedCards.getLast()), "取消应收尾 cancelled 卡");
        assertTrue(f.bot().updatedCards.getLast().toString()
                .contains("需求整理已停止。你可以继续在当前话题里补充。"));
        assertFalse(f.bot().updatedCards.getLast().toString().contains("查看错误详情"),
                "取消卡不应带错误详情");
        assertEquals(0, f.bot().mentionCalls, "取消路径不发结果通知");
        assertNull(f.runtime().activeRuns().get(f.session().id()), "取消路径同样清理 activeRun");
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status());
    }

    // ---------- catch：失败 → failed 卡 + technicalDetail ----------

    @Test
    void 失败后发failed收尾卡并带错误详情() throws Exception {
        RunFixture f = fixture(List.of(), "stderr:引擎崩溃");

        ClarificationRunner.continueClarificationFlow(options(f), f.session(), f.adapter(), f.heartbeat());

        assertEquals("red", headerTemplate(f.bot().updatedCards.getLast()), "失败应收尾 failed 卡");
        assertTrue(f.bot().updatedCards.getLast().toString()
                .contains("需求整理没有完成。请在当前话题里重试。"));
        assertTrue(f.bot().updatedCards.getLast().toString().contains("引擎崩溃"),
                "技术详情应携带错误消息");
        assertEquals(0, f.bot().mentionCalls);
        assertEquals(SessionStatus.IDLE, f.sessions().get(f.session().id()).status());
    }
}

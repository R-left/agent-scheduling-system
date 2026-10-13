package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.leftrr.agentscheduling.core.CancelMode;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.CliRequest;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.JsonSessionStore;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.ResolvedSession;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.SessionStore;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.core.TopicAddress;
import com.leftrr.agentscheduling.core.TopicTask;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.CardScheduler;
import com.leftrr.agentscheduling.im.CardText;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.im.IncomingMessage;
import com.leftrr.agentscheduling.im.Mention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 Task 2/3：BotMessageOrchestrator 全量（index.ts L151-765）。
 * 替身口径（brief）：fake Bot 记录调用、真 SessionManager（临时目录 JsonSessionStore）；
 * Task 3 执行段用 M2 FakeCli 子进程 + 探针适配器（ClarificationRunnerTest 先例）编排事件
 * 触发各收尾分支，绝不拉起真实 claude/codex。每条文案逐字断言（LogbackCapture 截获 SLF4J 消息体）。
 */
class BotMessageOrchestratorTest {

    private static final String CHAT_ID = "oc_chat1";
    private static final String THREAD_ID = "ot_t1";

    @TempDir
    Path temp;

    private Path ws1;
    private Path ws2;
    private JsonSessionStore store;
    private BotRegistry.BotConfig config;
    private SessionManager sessions;
    private AppRuntime runtime;
    private BotMessageOrchestrator orchestrator;
    private FakeBot fakeBot;
    private ProbeAdapter quietAdapter;
    private String resultFx;
    private String compactFx;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AtomicInteger fixtureSeq = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        ws1 = Files.createDirectories(temp.resolve("ws1"));
        ws2 = Files.createDirectories(temp.resolve("ws2"));
        config = new BotRegistry.BotConfig("pm", "app-id", "app-secret", CliId.CLAUDE,
                "CEO 助理", List.of(), "", ws1.toString(), 16);
        BotRegistry.BotConfig config2 = new BotRegistry.BotConfig("pm2", "app-id-2",
                "app-secret-2", CliId.CLAUDE, "开发", List.of(), "", ws1.toString(), 16);

        store = new JsonSessionStore(temp.resolve("sessions.json").toString(), "pm",
                Map.of("pm", ws1.toString()));
        sessions = SessionManager.open(store);
        runtime = new AppRuntime(sessions, new TeamRegistry("pm", List.of(config, config2)),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                ConcurrentHashMap.newKeySet(), new Collaboration.CollaborationInbox(),
                new Clarification.ClarificationFlowStore(), new ProductSpec.ProductSpecFlowStore());
        // Task 3 起 onMessage 尾部会真实执行 CLI：默认编排器注入静默探针适配器
        // （FakeCli emit 一条 result 事件即正常完成；compact 走 boundary+result 夹具），
        // 各分支测试再按需换装
        String resultFxName = emitBothWorkspaces(
                "{\"type\":\"result\",\"answer\":\"好的，已完成\",\"sessionId\":\"cli-s1\"}");
        String compactFxName = emitBothWorkspaces(
                "[{\"type\":\"system\",\"subtype\":\"compact_boundary\"}]",
                "{\"type\":\"result\",\"result\":\"整理完成\"}");
        resultFx = resultFxName;
        compactFx = compactFxName;
        quietAdapter = new ProbeAdapter(new String[]{"emit:" + resultFxName},
                new String[]{"stdin-record:compact-record.jsonl:" + compactFxName});
        orchestrator = orchestratorFor(config, quietAdapter);

        fakeBot = new FakeBot();
        runtime.botRuntimes().put("pm",
                new BotRuntime(config, fakeBot, new BotIdentity("ou_pm", "CEO 助理")));
        runtime.botRuntimes().put("pm2",
                new BotRuntime(config2, new FakeBot(), new BotIdentity("ou_pm2", "开发")));
    }

    // ---------- 替身 ----------

    /** fake Bot：记录 reply/replyCard/updateCard/downloadResource 调用，行为可注入。 */
    private static final class FakeBot implements Bot {
        record CardCall(String messageId, JsonNode card, boolean replyInThread) {}
        record CardUpdate(String messageId, JsonNode card) {}
        record Download(String messageId, String fileKey, String type, String saveDir,
                String fileName) {}
        record MentionCall(String messageId, BotIdentity target, String text,
                boolean replyInThread) {}

        final List<String> replies = new ArrayList<>();
        final List<CardCall> cards = new ArrayList<>();
        final List<CardUpdate> updates = new ArrayList<>();
        final List<Download> downloads = new ArrayList<>();
        final List<MentionCall> mentions = new ArrayList<>();
        String replyCardResult = "om-card-1";
        RuntimeException replyCardError;
        RuntimeException updateCardError;
        /** 可抛 IOException 的下载行为注入（Function 不允许受检异常）。 */
        interface DownloadHandler {
            String apply(Download d) throws IOException;
        }
        DownloadHandler downloadHandler = d -> null;

        @Override
        public BotIdentity getIdentity() {
            return new BotIdentity("ou_pm", "CEO 助理");
        }

        @Override
        public String reply(String messageId, String text, boolean replyInThread) {
            replies.add(messageId + "|" + text + "|" + replyInThread);
            return messageId;
        }

        @Override
        public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
            if (replyCardError != null) {
                throw replyCardError;
            }
            cards.add(new CardCall(messageId, card, replyInThread));
            return replyCardResult;
        }

        @Override
        public String replyMention(String messageId, BotIdentity target, String text,
                boolean replyInThread) {
            mentions.add(new MentionCall(messageId, target, text, replyInThread));
            return "om-mention-" + mentions.size();
        }

        @Override
        public void updateCard(String messageId, JsonNode card) {
            if (updateCardError != null) {
                throw updateCardError;
            }
            updates.add(new CardUpdate(messageId, card));
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
                String saveDir, String fileName) throws IOException {
            Download record = new Download(messageId, fileKey, type, saveDir, fileName);
            downloads.add(record);
            return downloadHandler.apply(record);
        }
    }

    // ---------- Task 3 执行段替身：探针适配器 + 事件夹具（ClarificationRunnerTest 先例） ----------

    /**
     * 探针适配器：java 启动 FakeCli 子进程（跨平台、不依赖真实 claude/codex），
     * 解析 result/tool_call/tool_start/tool_end/context/stats 事件，
     * 记录转发给 buildArgs/buildResumeArgs 的 prompt 供断言。
     */
    private static final class ProbeAdapter implements CliAdapter {
        final List<String> newSessionPrompts = new ArrayList<>();
        final List<String> resumePrompts = new ArrayList<>();
        private final CliId id;
        private final String[] modes;
        private final String[] compactModes;

        /** compact 路径复用执行 modes（仅当该适配器也承担 compact 用例时区分）。 */
        ProbeAdapter(String... modes) {
            this(CliId.CLAUDE, modes, modes);
        }

        ProbeAdapter(String[] modes, String[] compactModes) {
            this(CliId.CLAUDE, modes, compactModes);
        }

        ProbeAdapter(CliId id, String[] modes, String[] compactModes) {
            this.id = id;
            this.modes = modes;
            this.compactModes = compactModes;
        }

        @Override
        public CliId id() {
            return id;
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
            return FakeCliCommand.command(modes).subList(1, FakeCliCommand.command(modes).size());
        }

        @Override
        public List<String> buildResumeArgs(String prompt, String sessionId,
                CliPromptInput promptInput) {
            resumePrompts.add(prompt);
            return FakeCliCommand.command(modes).subList(1, FakeCliCommand.command(modes).size());
        }

        @Override
        public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
            List<String> full = FakeCliCommand.command(compactModes);
            return new CliCompactPlan.ClaudeStreamJson(full.get(0),
                    full.subList(1, full.size()), "M7-ORCH-COMPACT-PROMPT");
        }

        @Override
        public List<CliEvent> parseEvents(String line) {
            try {
                JsonNode node = MAPPER.readTree(line);
                return switch (node.path("type").asText(null)) {
                    case "result" -> List.of(new CliEvent.ResultEvent(
                            node.path("answer").asText(null),
                            node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null,
                            statsOf(node.get("stats"))));
                    case "tool_call" -> List.of(new CliEvent.ToolCallEvent(
                            node.path("toolUseId").asText(null),
                            node.path("toolName").asText(null),
                            node.get("input")));
                    case "tool_start" -> List.of(new CliEvent.ToolStartEvent(
                            node.path("toolUseId").asText(null),
                            node.path("toolName").asText(null),
                            node.path("label").asText(null),
                            node.hasNonNull("detail") ? node.get("detail").asText() : null));
                    case "tool_end" -> List.of(new CliEvent.ToolEndEvent(
                            node.path("toolUseId").asText(null),
                            node.path("failed").asBoolean(false)));
                    case "context" -> List.of(new CliEvent.ContextEvent(
                            node.path("usedTokens").asLong()));
                    case "stats" -> List.of(new CliEvent.StatsEvent(statsOf(node.get("stats"))));
                    default -> List.of();
                };
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

    /** 在两个工作区各落一份同名夹具（协作路径 cwd=ws2，普通路径 cwd=ws1），返回文件名。 */
    private String emitBothWorkspaces(String... lines) throws IOException {
        String content = String.join("\n", lines);
        String name = "fx-" + fixtureSeq.incrementAndGet() + ".jsonl";
        Files.writeString(ws1.resolve(name), content, StandardCharsets.UTF_8);
        Files.writeString(ws2.resolve(name), content, StandardCharsets.UTF_8);
        return name;
    }

    /** 单工作区夹具（cwd 相对名），返回文件名。 */
    private String fixture(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("fx-" + fixtureSeq.incrementAndGet() + ".jsonl");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return file.getFileName().toString();
    }

    /** 按自定义 config + 适配器构建编排器（技能集/交付模式随用例定制）。 */
    private BotMessageOrchestrator orchestratorFor(BotRegistry.BotConfig cfg,
            ProbeAdapter adapter) {
        return new BotMessageOrchestrator(runtime, cfg, new CollaborationService(runtime),
                ProductDeliveryMode.LARK_DOC, id -> adapter);
    }

    /** 在收尾 updateCard 序列里按 header template 找卡（心跳/finish 混杂时定位收尾卡）。 */
    private static ObjectNode findUpdateByTemplate(List<FakeBot.CardUpdate> updates,
            String template) {
        for (FakeBot.CardUpdate update : updates) {
            if (template.equals(update.card().path("header").path("template").asText(null))) {
                return (ObjectNode) update.card();
            }
        }
        return null;
    }

    private static final AtomicInteger toolUseSeq = new AtomicInteger();

    private static String toolCallLine(String toolName, String inputJson) {
        // CliRunner 以 toolUseId 为键聚合 tool_calls：同轮多个 tool_call 必须不同 id
        return "{\"type\":\"tool_call\",\"toolUseId\":\"t" + toolUseSeq.incrementAndGet()
                + "\",\"toolName\":\"" + toolName + "\",\"input\":" + inputJson + "}";
    }

    // ---------- 消息与协作夹具 ----------

    private IncomingMessage userMessage(String messageId, String text) {
        return new IncomingMessage(messageId, CHAT_ID, "group", "text", text, null, THREAD_ID,
                "user", "ou_alice", null, List.of(), "{}");
    }

    private IncomingMessage botMessage(String messageId, String dispatchId) {
        return new IncomingMessage(messageId, CHAT_ID, "group", "post",
                "@CEO 助理 任务编号：" + dispatchId + " 请处理", null, THREAD_ID, "bot", "ou_pm2",
                null, List.of(new Mention("@_user_1", "CEO 助理", "ou_pm")), "{}");
    }

    private Collaboration.CollaborationMessage collaboration(String dispatchId, String fromBotId,
            int round, String workspaceDir) {
        return collaborationTo(dispatchId, fromBotId, "pm", round, workspaceDir);
    }

    /** reportTo 可定制的协作消息（Task 3 回派三态用例）。 */
    private Collaboration.CollaborationMessage collaborationTo(String dispatchId, String fromBotId,
            String reportToBotId, int round, String workspaceDir) {
        return new Collaboration.CollaborationMessage(dispatchId, "task-1", "ou_owner", null,
                fromBotId, "pm", reportToBotId, "整理季度报告", "汇总三个表格的数据", null, round, 16,
                workspaceDir);
    }

    private String sessionIdInFlight() {
        assertEquals(1, runtime.activeRuns().size());
        return runtime.activeRuns().keySet().iterator().next();
    }

    // ---------- 主链路 ----------

    @Test
    void 普通消息_五条调试日志逐字_任务卡与执行收尾() throws IOException, InterruptedException {
        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);
        } finally {
            capture.detach();
        }
        String sid = store.load().get(0).id();

        assertEquals(List.of(
                "[收到] chat=" + CHAT_ID + " threadId=" + THREAD_ID + " rootId=null"
                        + " sender=ou_alice",
                "  原文: 你好",
                "  还原: 你好",
                "  mentions: (无)",
                "  [会话] 新建 id=" + sid + " status=creating",
                "[卡片] 已发送 message_id=om-card-1 inThread=true",
                "[CLI] claude 完成 session_id=cli-s1"),
                capture.messages());

        // Task 3 执行段收尾：会话空闲、CLI 会话回写、activeRuns 清空、成功卡 + 通知
        Session executed = store.load().get(0);
        assertEquals(SessionStatus.IDLE, executed.status());
        assertEquals("cli-s1", executed.cliSessionId());
        assertTrue(runtime.activeRuns().isEmpty());

        assertEquals(1, fakeBot.cards.size());
        FakeBot.CardCall card = fakeBot.cards.get(0);
        assertEquals("om_1", card.messageId());
        assertTrue(card.replyInThread());
        ObjectNode cardJson = (ObjectNode) card.card();
        assertEquals("测试CLI · 执行中",
                cardJson.path("header").path("title").path("content").asText());
        assertEquals("blue", cardJson.path("header").path("template").asText());
        assertTrue(cardJson.toString().contains("正在理解任务"));
        assertTrue(cardJson.toString().contains(sid));

        assertEquals(1, fakeBot.updates.size());
        ObjectNode finishCard = (ObjectNode) fakeBot.updates.get(0).card();
        assertEquals("测试CLI · 已完成",
                finishCard.path("header").path("title").path("content").asText());
        assertEquals("green", finishCard.path("header").path("template").asText());
        // success 卡 answer 非空时正文展示 answer（detail「执行完成」仅作空 answer 兜底）
        assertTrue(finishCard.toString().contains("好的，已完成"));

        assertEquals(1, fakeBot.mentions.size());
        FakeBot.MentionCall mention = fakeBot.mentions.get(0);
        assertEquals("任务已完成，请查看上方结果。", mention.text());
        assertEquals("ou_alice", mention.target().openId());
    }

    @Test
    void 带提及的消息_mentions行与还原文案() throws IOException, InterruptedException {
        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            IncomingMessage msg = new IncomingMessage("om_1", CHAT_ID, "group", "text",
                    "@_user_1 检查项目状态", null, THREAD_ID, "user", "ou_alice", null,
                    List.of(new Mention("@_user_1", "CEO 助理", "ou_pm")), "{}");
            orchestrator.onMessage(msg, fakeBot);
        } finally {
            capture.detach();
        }
        assertTrue(capture.messages().contains("  还原: @CEO 助理 检查项目状态"));
        assertTrue(capture.messages().contains("  mentions: @_user_1=CEO 助理(ou_pm)"));
    }

    // ---------- 空 prompt 直达请求 ----------

    @Test
    void cliRequest空prompt_回复引导文案并返回() throws IOException, InterruptedException {
        orchestrator.onMessage(userMessage("om_1", "/claude"), fakeBot);

        assertEquals(List.of("om_1|请在 /claude 后面写下任务，例如：/claude 检查项目状态|true"),
                fakeBot.replies);
        assertEquals(0, sessions.size());
        assertTrue(runtime.activeRuns().isEmpty());
        assertTrue(fakeBot.cards.isEmpty());
    }

    // ---------- 命令分流 ----------

    @Test
    void 命令分流Handled_help回复后会话转空闲且不再发卡() throws IOException, InterruptedException {
        orchestrator.onMessage(userMessage("om_1", "/help"), fakeBot);

        assertEquals(List.of("om_1|" + String.join("\n",
                "/status 查看当前会话",
                "/team 查看当前 Agent 团队",
                "/new 开启一个全新的 CLI 会话",
                "/resume 选择当前工作目录中的 CLI 会话",
                "/compact [要求] 使用当前引擎原生整理上下文",
                "/cd 查看当前工作目录",
                "/cd <目录> 切换当前话题的工作目录",
                "/close 关闭当前会话",
                "/help 查看命令",
                "/claude <任务> 新话题使用 Claude Code",
                "/codex <任务> 新话题使用 Codex") + "|true"), fakeBot.replies);
        assertEquals(1, sessions.size());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
        assertTrue(fakeBot.cards.isEmpty());
    }

    // ---------- 三个状态守卫 ----------

    @Test
    void closed守卫_逐字文案() throws IOException, InterruptedException {
        ResolvedSession preseeded = sessions.resolve(
                new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CLAUDE, "pm", ws1.toString());
        sessions.transition(preseeded.session().id(), SessionStatus.CLOSED);

        orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);

        assertEquals(List.of("om_1|这个话题的会话已经关闭，请新开一个话题继续。|true"), fakeBot.replies);
        assertTrue(fakeBot.cards.isEmpty());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void creating复用守卫_逐字文案() throws IOException, InterruptedException {
        sessions.resolve(new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CLAUDE, "pm", ws1.toString());

        orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);

        assertEquals(List.of("om_1|当前会话正在准备，请稍后再追问。|true"), fakeBot.replies);
        assertTrue(fakeBot.cards.isEmpty());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void active守卫_逐字文案() throws IOException, InterruptedException {
        // Task 3 起消息会真实执行并收尾为 idle，active 态改为直接预置（等价原「首条执行中」）
        ResolvedSession preseeded = sessions.resolve(
                new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CLAUDE, "pm", ws1.toString());
        sessions.transition(preseeded.session().id(), SessionStatus.ACTIVE);
        runtime.activeRuns().put(preseeded.session().id(),
                new ActiveRun(new CancellationToken(), "ou_alice"));

        orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);

        assertEquals(List.of("om_1|当前会话还在执行，请等任务结束后再追问。|true"), fakeBot.replies);
        assertTrue(fakeBot.cards.isEmpty());
        assertEquals(1, runtime.activeRuns().size());
    }

    // ---------- 协作识别 ----------

    @Test
    void 协作识别_完整路径_ownerOpenId取协作来源() throws IOException, InterruptedException {
        runtime.collaborationInbox().register(
                collaboration("d1a2b3c4d5e6", "pm2", 1, ws2.toString()));

        orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);

        assertTrue(runtime.processedCollaborationTurns().contains("task-1:1:pm"));
        // Task 3 执行收尾：协作任务也走完整收尾链（quiet 适配器正常完成 → idle）
        Session session = store.load().get(0);
        assertEquals(SessionStatus.IDLE, session.status());
        assertEquals(ws2.toString(), session.workspaceDir());
        assertEquals("cli-s1", session.cliSessionId());
        assertTrue(runtime.activeRuns().isEmpty());
        assertTrue(fakeBot.replies.isEmpty());
        assertEquals(1, fakeBot.cards.size());
        assertEquals(1, fakeBot.updates.size());
    }

    @Test
    void 协作非目标_忽略日志且不建会话() throws IOException, InterruptedException {
        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);
        } finally {
            capture.detach();
        }

        assertEquals(List.of("[协作] 忽略非目标 bot 消息 sender=ou_pm2 target=pm"),
                capture.messages());
        assertEquals(0, sessions.size());
        assertTrue(runtime.activeRuns().isEmpty());
        assertTrue(fakeBot.cards.isEmpty());
    }

    @Test
    void 协作来源未注册_找不到来源日志() throws IOException, InterruptedException {
        runtime.collaborationInbox()
                .register(collaboration("d1a2b3c4d5e6", "pm3", 1, ws2.toString()));

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);
        } finally {
            capture.detach();
        }

        assertEquals(List.of("[协作] 找不到来源 bot: pm3"), capture.messages());
        assertEquals(0, sessions.size());
        assertTrue(fakeBot.cards.isEmpty());
    }

    @Test
    void 协作turnKey防重_第二次忽略() throws IOException, InterruptedException {
        runtime.collaborationInbox().register(
                collaboration("d11111111111", "pm2", 1, ws2.toString()));
        runtime.collaborationInbox().register(
                collaboration("d22222222222", "pm2", 1, ws2.toString()));

        orchestrator.onMessage(botMessage("om_b1", "d11111111111"), fakeBot);
        assertEquals(1, runtime.processedCollaborationTurns().size());

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(botMessage("om_b2", "d22222222222"), fakeBot);
            assertEquals(List.of("[协作] 忽略重复消息 task-1:1:pm"), capture.messages());
        } finally {
            capture.detach();
        }

        assertEquals(1, runtime.processedCollaborationTurns().size());
        assertEquals(1, fakeBot.cards.size());
        assertEquals(0, runtime.activeRuns().size());
    }

    @Test
    void 协作工作区切换_session换到协作工作目录() throws IOException, InterruptedException {
        ResolvedSession preseeded = sessions.resolve(
                new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CLAUDE, "pm", ws1.toString());
        String sid = preseeded.session().id();
        sessions.transition(sid, SessionStatus.IDLE);
        runtime.collaborationInbox().register(
                collaboration("d1a2b3c4d5e6", "pm2", 1, ws2.toString()));

        orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);

        Session session = sessions.get(sid);
        assertEquals(ws2.toString(), session.workspaceDir());
        assertEquals(SessionStatus.IDLE, session.status());
    }

    // ---------- 旧澄清作废 ----------

    private Clarification.ClarificationRequest clarificationRequest() {
        return new Clarification.ClarificationRequest("需求澄清", "", List.of(
                new Clarification.Question("q1", "范围是？",
                        List.of(new Clarification.Option("all", "全做"),
                                new Clarification.Option("half", "做一半")), null)));
    }

    @Test
    void 旧澄清作废_删流程并更新superseded卡() throws IOException, InterruptedException {
        String taskId = TopicTask.topicTaskId(
                new TopicAddress("om_1", CHAT_ID, THREAD_ID, null));
        Clarification.ClarificationFlow flow = runtime.clarificationFlows().create(
                new Clarification.CreateFlowOptions(taskId, "pm", "sess-x", "ou_alice", null,
                        null, "om_orig", "om-old-card", true, clarificationRequest()));

        orchestrator.onMessage(userMessage("om_1", "改成只做第一部分"), fakeBot);

        assertNull(runtime.clarificationFlows().get(flow.token()));
        // updates[0] = superseded 卡；updates[1] = 执行收尾成功卡（Task 3 起）
        assertEquals(2, fakeBot.updates.size());
        assertEquals("om-old-card", fakeBot.updates.get(0).messageId());
        assertEquals(ClarificationCards.buildClarificationSupersededCard(flow).toString(),
                fakeBot.updates.get(0).card().toString());
        // 作废后继续正常执行
        assertEquals(1, fakeBot.cards.size());
    }

    @Test
    void 旧澄清作废_卡片更新失败warn后继续() throws IOException, InterruptedException {
        String taskId = TopicTask.topicTaskId(
                new TopicAddress("om_1", CHAT_ID, THREAD_ID, null));
        Clarification.ClarificationFlow flow = runtime.clarificationFlows().create(
                new Clarification.CreateFlowOptions(taskId, "pm", "sess-x", "ou_alice", null,
                        null, "om_orig", "om-old-card", true, clarificationRequest()));
        fakeBot.updateCardError = new RuntimeException("boom");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(userMessage("om_1", "改成只做第一部分"), fakeBot);
        } finally {
            capture.detach();
        }

        assertTrue(capture.messages()
                .contains("[澄清] 旧卡片更新失败，继续处理用户的新消息: boom"));
        assertNull(runtime.clarificationFlows().get(flow.token()));
        assertEquals(1, fakeBot.cards.size());
    }

    // ---------- 附件下载 ----------

    @Test
    void 附件下载_image成功_日志含绝对路径() throws IOException, InterruptedException {
        Path saved = temp.resolve("img_v2_x.png");
        Files.writeString(saved, "png");
        fakeBot.downloadHandler = d -> saved.toString();

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            IncomingMessage msg = new IncomingMessage("om_1", CHAT_ID, "group", "image", "看图",
                    null, THREAD_ID, "user", "ou_alice", null, List.of(),
                    "{\"image_key\":\"img_v2_x\"}");
            orchestrator.onMessage(msg, fakeBot);
        } finally {
            capture.detach();
        }

        FakeBot.Download download = fakeBot.downloads.get(0);
        assertEquals("om_1", download.messageId());
        assertEquals("img_v2_x", download.fileKey());
        assertEquals("image", download.type());
        assertEquals(Path.of("data", "downloads").toString(), download.saveDir());
        assertNull(download.fileName());
        String absPath = saved.toAbsolutePath().normalize().toString();
        assertTrue(capture.messages().contains("  [下载] image → " + absPath));
        assertEquals(1, fakeBot.cards.size());
    }

    @Test
    void 附件下载_单文件失败不中断整体() throws IOException, InterruptedException {
        Path saved = temp.resolve("img_2.png");
        Files.writeString(saved, "png");
        fakeBot.downloadHandler = d -> {
            if (d.fileKey().equals("img_1")) {
                throw new IOException("boom");
            }
            return saved.toString();
        };

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            IncomingMessage msg = new IncomingMessage("om_1", CHAT_ID, "group", "post", "看图",
                    null, THREAD_ID, "user", "ou_alice", null, List.of(),
                    "{\"content\":[[{\"tag\":\"img\",\"image_key\":\"img_1\"},"
                            + "{\"tag\":\"img\",\"image_key\":\"img_2\"}]]}");
            orchestrator.onMessage(msg, fakeBot);
        } finally {
            capture.detach();
        }

        assertTrue(capture.messages().contains("  [下载失败] img_1: boom"));
        String absPath = saved.toAbsolutePath().normalize().toString();
        assertTrue(capture.messages().contains("  [下载] image → " + absPath));
        assertEquals(2, fakeBot.downloads.size());
        assertEquals(1, fakeBot.cards.size());
    }

    // ---------- 任务卡回滚 ----------

    @Test
    void replyCard抛错_回滚activeRuns并标记空闲() throws IOException {
        fakeBot.replyCardError = new RuntimeException("send fail");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot));
        assertEquals("send fail", thrown.getMessage());

        assertTrue(runtime.activeRuns().isEmpty());
        Session session = store.load().get(0);
        assertEquals(SessionStatus.IDLE, session.status());
    }

    @Test
    void replyCard无message_id_回滚并日志() throws IOException, InterruptedException {
        fakeBot.replyCardResult = null;

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);
        } finally {
            capture.detach();
        }

        assertTrue(capture.messages().contains("[卡片] 响应里没有 message_id，无法继续更新"));
        assertFalse(capture.messages().contains("[卡片] 已发送 message_id=om-card-1 inThread=true"));
        assertTrue(runtime.activeRuns().isEmpty());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
    }

    // ---------- compact 任务卡 ----------

    @Test
    void compact_标题整理上下文_claude无指令detail() throws IOException, InterruptedException {
        ResolvedSession preseeded = sessions.resolve(
                new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CLAUDE, "pm", ws1.toString());
        sessions.setCliSessionId(preseeded.session().id(), "cli-1");
        sessions.transition(preseeded.session().id(), SessionStatus.IDLE);

        orchestrator.onMessage(userMessage("om_2", "/compact"), fakeBot);

        assertEquals(1, fakeBot.cards.size());
        ObjectNode card = (ObjectNode) fakeBot.cards.get(0).card();
        assertEquals("整理上下文 · 执行中",
                card.path("header").path("title").path("content").asText());
        assertTrue(card.toString().contains("正在调用 测试CLI 原生上下文整理"));
    }

    @Test
    void compact_codex带指令_默认策略文案() throws IOException, InterruptedException {
        ResolvedSession preseeded = sessions.resolve(
                new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CODEX, "pm", ws1.toString());
        sessions.setCliSessionId(preseeded.session().id(), "cli-1");
        sessions.transition(preseeded.session().id(), SessionStatus.IDLE);

        // codex 会话需要 id=CODEX 的适配器（initialDetail 按 cliAdapter.id 分岔文案）
        ProbeAdapter codexAdapter = new ProbeAdapter(CliId.CODEX,
                new String[]{"emit:" + resultFx},
                new String[]{"stdin-record:compact-record.jsonl:" + compactFx});
        orchestratorFor(config, codexAdapter)
                .onMessage(userMessage("om_2", "/compact 保持要点"), fakeBot);

        assertEquals(1, fakeBot.cards.size());
        ObjectNode card = (ObjectNode) fakeBot.cards.get(0).card();
        assertEquals("整理上下文 · 执行中",
                card.path("header").path("title").path("content").asText());
        assertTrue(card.toString().contains("Codex 正在使用原生默认策略整理上下文"));
    }

    // ================================================================
    // M7 Task 3：进度心跳、执行与收尾三分支（index.ts L379-765）
    // ================================================================

    /** 轮询等待消息线程进入执行段（activeRuns 注册后），返回 sessionId。 */
    private String awaitActiveRun() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (runtime.activeRuns().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("任务未进入执行段");
            }
            Thread.sleep(20);
        }
        return runtime.activeRuns().keySet().iterator().next();
    }

    /** 预置一个空闲会话（可带 CLI 会话 ID），返回 sessionId。 */
    private String preseedIdleSession(String cliSessionId) throws IOException {
        ResolvedSession preseeded = sessions.resolve(
                new MessageAddress("om_pre", CHAT_ID, THREAD_ID, null),
                CliId.CLAUDE, "pm", ws1.toString());
        if (cliSessionId != null) {
            sessions.setCliSessionId(preseeded.session().id(), cliSessionId);
        }
        sessions.transition(preseeded.session().id(), SessionStatus.IDLE);
        return preseeded.session().id();
    }

    // ---------- 事件过滤（L427-434） ----------

    @Test
    void 事件过滤_仅tool与context事件参与进度聚合() {
        assertTrue(BotMessageOrchestrator.isProgressEvent(
                new CliEvent.ToolStartEvent("u1", "Read", "读文件", null)));
        assertTrue(BotMessageOrchestrator.isProgressEvent(new CliEvent.ToolEndEvent("u1", false)));
        assertTrue(BotMessageOrchestrator.isProgressEvent(new CliEvent.ContextEvent(1_000L)));
        // 非 {tool_start, tool_end, context} 不 accept
        assertFalse(BotMessageOrchestrator.isProgressEvent(new CliEvent.SessionEvent("s1")));
        assertFalse(BotMessageOrchestrator.isProgressEvent(new CliEvent.StatsEvent(null)));
        assertFalse(BotMessageOrchestrator.isProgressEvent(
                new CliEvent.ToolCallEvent("u2", "request_clarification", null)));
        assertFalse(BotMessageOrchestrator.isProgressEvent(
                new CliEvent.ResultEvent("答", "s1", null)));
        assertFalse(BotMessageOrchestrator.isProgressEvent(new CliEvent.ErrorEvent("错", null)));
    }

    // ---------- 分支三：正常完成 ----------

    @Test
    void 正常完成_成功卡带进度快照_会话与上下文回写_通知() throws IOException, InterruptedException {
        String sid = preseedIdleSession(null);
        runtime.contextWindows().put(sid, 100_000L);
        String fx = emitBothWorkspaces(
                "{\"type\":\"tool_start\",\"toolUseId\":\"u1\",\"toolName\":\"Read\",\"label\":\"读文件\"}",
                "{\"type\":\"tool_end\",\"toolUseId\":\"u1\",\"failed\":false}",
                "{\"type\":\"context\",\"usedTokens\":1000}",
                "{\"type\":\"result\",\"answer\":\"任务答案\",\"sessionId\":\"cli-n1\","
                        + "\"stats\":{\"contextWindowTokens\":200000,\"totalTokens\":1234}}");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(config, new ProbeAdapter("emit:" + fx))
                    .onMessage(userMessage("om_1", "做任务"), fakeBot);
        } finally {
            capture.detach();
        }

        // then 前置回写：sessionId 与 contextWindowTokens（真值，0 才不写）
        Session executed = store.load().get(0);
        assertEquals("cli-n1", executed.cliSessionId());
        assertEquals(200_000L, runtime.contextWindows().get(sid));
        // 成功卡：执行详情折叠面板含活动轨迹 + 上下文用量（窗口取装配值 100k）
        ObjectNode finishCard = findUpdateByTemplate(fakeBot.updates, "green");
        assertNotNull(finishCard);
        String cardText = finishCard.toString();
        assertTrue(cardText.contains("读文件"));
        assertTrue(cardText.contains("最近执行轨迹"));
        // 上下文窗口取值口径 = TS buildFinishedElements：window 优先取 stats.contextWindowTokens
        //（200k），而非 contextWindows 装配值（100k 只在 stats 缺失时兜底）
        assertTrue(cardText.contains("1k / 200k（1%）"));
        // 日志用原始 result 的 sessionId（非 finalResult 概念在本用例等价，仍钉住取值来源）
        assertTrue(capture.messages().contains("[CLI] claude 完成 session_id=cli-n1"));
        // 通知 + finally 收尾（分支未 return，统一落 finally）
        assertEquals(1, fakeBot.mentions.size());
        assertEquals("任务已完成，请查看上方结果。", fakeBot.mentions.get(0).text());
        assertEquals("ou_alice", fakeBot.mentions.get(0).target().openId());
        assertEquals(SessionStatus.IDLE, executed.status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void 正常完成_长答案分片续传() throws IOException, InterruptedException {
        // 6500 code units > MAX_CARD_ANSWER_LENGTH(6000)：卡片只展示前段，续传分片走 reply
        String answer = "很长的回答".repeat(1300);
        String fx = emitBothWorkspaces(
                "{\"type\":\"result\",\"answer\":\"" + answer + "\",\"sessionId\":\"cli-n2\"}");

        orchestratorFor(config, new ProbeAdapter("emit:" + fx))
                .onMessage(userMessage("om_1", "做任务"), fakeBot);

        List<String> expected = CardText.splitLongText(CardText.answerContinuation(answer))
                .stream().map(chunk -> "om_1|" + chunk + "|true").toList();
        // 续传段 = answer 6000 之后的尾段（500 code units），单分片即全部剩余内容
        assertEquals(1, expected.size());
        assertEquals(expected, fakeBot.replies);
        assertTrue(answer.endsWith(expected.get(0).split("\\|", -1)[1]));
        // 任务卡出现「剩余内容已继续发送」提示；剩余部分由 reply 分片送达（上方已断言）
        ObjectNode finishCard = findUpdateByTemplate(fakeBot.updates, "green");
        assertNotNull(finishCard);
        assertTrue(finishCard.toString().contains("回答较长，剩余内容已继续发送。"));
    }

    // ---------- 分支三：compact 正常完成（裁决 8 结果映射两态） ----------

    @Test
    void compact正常完成_有message_grey暂时无需整理() throws IOException, InterruptedException {
        preseedIdleSession("cli-1");
        // NativeCompact：无 boundary 但 result 为「消息不够」→ compacted=false + message 保留
        String fx = emitBothWorkspaces(
                "{\"type\":\"result\",\"result\":\"Not enough messages to compact.\"}");
        ProbeAdapter greyAdapter = new ProbeAdapter(new String[]{"emit:" + resultFx},
                new String[]{"emit:" + fx});

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(config, greyAdapter).onMessage(userMessage("om_1", "/compact"), fakeBot);
        } finally {
            capture.detach();
        }

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "grey");
        assertNotNull(card);
        assertEquals("暂时无需整理", card.path("header").path("title").path("content").asText());
        assertTrue(card.toString().contains("当前上下文还不需要整理。继续使用一段时间后再试。"));
        // !isCompacting 为 false：sessionId 与 contextWindow 均不回写（裁决 6 前置条件）
        assertEquals("cli-1", store.load().get(0).cliSessionId());
        assertNull(runtime.contextWindows().get(store.load().get(0).id()));
        assertTrue(capture.messages().contains("[CLI] claude 完成 session_id=cli-1"));
        assertEquals("上下文整理已完成，请查看上方结果。", fakeBot.mentions.get(0).text());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void compact正常完成_空message_绿卡上下文已整理() throws IOException, InterruptedException {
        preseedIdleSession("cli-1");
        // setUp 的 compactFx：boundary + result → compacted=true、message 丢弃为 null → answer ""

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(userMessage("om_1", "/compact"), fakeBot);
        } finally {
            capture.detach();
        }

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "green");
        assertNotNull(card);
        assertEquals("上下文已整理", card.path("header").path("title").path("content").asText());
        // 空串为 falsy：detail 回落两段 join("\n\n")（displayName 用探针适配器名）
        assertTrue(card.toString().contains("测试CLI 已在当前 CLI 会话内完成原生压缩。"));
        assertTrue(card.toString().contains("CLI 会话 ID 保持不变，下一条任务会继续使用整理后的上下文。"));
        assertEquals("cli-1", store.load().get(0).cliSessionId());
        assertTrue(capture.messages().contains("[CLI] claude 完成 session_id=cli-1"));
        assertEquals("上下文整理已完成，请查看上方结果。", fakeBot.mentions.get(0).text());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
    }

    // 注：裁决 8 的 requireNonNull 崩溃分支（/compact 且无 cliSessionId）经 onMessage 不可达
    // —— CommandHandler 的 /compact 守卫（L197-201）已拦下无 CLI 会话的整理请求并回复引导，
    // requireNonNull 仅为对齐 TS `session.cliSessionId!` 的声明性防崩溃，故无对应编排用例。

    // ---------- 分支一：澄清卡收尾 ----------

    @Test
    void 澄清分支_建流程发澄清卡通知与日志() throws IOException, InterruptedException {
        BotRegistry.BotConfig grill = new BotRegistry.BotConfig("pm", "app-id", "app-secret",
                CliId.CLAUDE, "CEO 助理", List.of("grill-me"), "", ws1.toString(), 16);
        String fx = emitBothWorkspaces(
                toolCallLine("request_clarification",
                        "{\"title\":\"需求澄清\",\"intro\":\"\",\"questions\":[{\"id\":\"q1\","
                                + "\"prompt\":\"范围是？\",\"options\":[{\"id\":\"all\",\"label\":\"全做\"},"
                                + "{\"id\":\"half\",\"label\":\"做一半\"}]}]}"),
                "{\"type\":\"result\",\"answer\":\"请确认\",\"sessionId\":\"cli-c1\"}");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(grill, new ProbeAdapter("emit:" + fx))
                    .onMessage(userMessage("om_1", "做任务"), fakeBot);
        } finally {
            capture.detach();
        }

        String taskId = TopicTask.topicTaskId(new TopicAddress("om_1", CHAT_ID, THREAD_ID, null));
        Clarification.ClarificationFlow flow =
                runtime.clarificationFlows().findForTask(taskId, "pm");
        assertNotNull(flow);
        assertEquals("需求澄清", flow.request().title());
        assertEquals(1, flow.request().questions().size());
        assertEquals("ou_alice", flow.ownerOpenId());
        assertEquals("om-card-1", flow.cardMessageId());
        // then 前置回写已发生（澄清分支在回写之后）
        assertEquals("cli-c1", store.load().get(0).cliSessionId());
        // 澄清卡经 cardUpdater.finish 落 updateCard（任务卡原位更新）
        assertEquals(1, fakeBot.updates.size());
        assertEquals("om-card-1", fakeBot.updates.get(0).messageId());
        assertEquals(ClarificationCards.buildClarificationCard(flow).toString(),
                fakeBot.updates.get(0).card().toString());
        // 通知 + 日志逐字
        assertEquals("需要你确认 1 个问题，请在上方卡片中选择。", fakeBot.mentions.get(0).text());
        assertTrue(capture.messages().contains("[澄清] 已发送交互卡片 questions=1"));
        // 分支 return 落 finally：会话空闲、activeRuns 清空（removeActiveRun + markSessionIdle）
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    // ---------- 分支二：产品方案 ----------

    @Test
    void 产品分支_lark交付_确认卡与通知() throws IOException, InterruptedException {
        BotRegistry.BotConfig spec = new BotRegistry.BotConfig("pm", "app-id", "app-secret",
                CliId.CLAUDE, "CEO 助理", List.of("lark-doc"), "", ws1.toString(), 16);
        String fx = emitBothWorkspaces(
                toolCallLine("request_spec_approval",
                        "{\"deliveryMode\":\"lark-doc\",\"title\":\"季度方案\",\"summary\":\"方案摘要\","
                                + "\"documentUrl\":\"https://example.feishu.cn/docx/tok123\"}"),
                "{\"type\":\"result\",\"answer\":\"方案已生成\",\"sessionId\":\"cli-p1\"}");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(spec, new ProbeAdapter("emit:" + fx))
                    .onMessage(userMessage("om_1", "写方案"), fakeBot);
        } finally {
            capture.detach();
        }

        // 确认卡经 finish 落 updateCard，包含 title 与文档 token
        assertEquals(1, fakeBot.updates.size());
        assertEquals("om-card-1", fakeBot.updates.get(0).messageId());
        String cardText = fakeBot.updates.get(0).card().toString();
        assertTrue(cardText.contains("季度方案"));
        assertTrue(cardText.contains("tok123"));
        // 通知 + 日志逐字
        assertEquals("产品方案已生成，请查看上方确认卡。", fakeBot.mentions.get(0).text());
        assertEquals("ou_alice", fakeBot.mentions.get(0).target().openId());
        assertTrue(capture.messages().contains("[产品文档] 已展示待确认产物"));
        // 分支 return 落 finally
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertEquals("cli-p1", store.load().get(0).cliSessionId());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void 产品分支_local交付_文档缺失走失败分支() throws IOException, InterruptedException {
        BotRegistry.BotConfig spec = new BotRegistry.BotConfig("pm", "app-id", "app-secret",
                CliId.CLAUDE, "CEO 助理", List.of("to-spec"), "", ws1.toString(), 16);
        String fx = emitBothWorkspaces(
                toolCallLine("request_spec_approval",
                        "{\"deliveryMode\":\"local\",\"title\":\"季度方案\",\"summary\":\"方案摘要\","
                                + "\"specPath\":\"spec.md\",\"ticketsPath\":\"tickets\"}"),
                "{\"type\":\"result\",\"answer\":\"方案已生成\",\"sessionId\":\"cli-p2\"}");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(spec, new ProbeAdapter("emit:" + fx))
                    .onMessage(userMessage("om_1", "写方案"), fakeBot);
        } finally {
            capture.detach();
        }

        // assertProductSpecDocuments throw → catch 失败分支，technicalDetail=throw 消息
        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("执行没有完成。你可以调整指令后，在当前话题里重试。"));
        assertTrue(card.toString().contains("产品方案尚未完整写入工作区，不能展示。"));
        assertTrue(card.toString().contains("- Spec: spec.md"));
        assertTrue(card.toString().contains("- Tickets: tickets"));
        assertTrue(capture.messages().contains(
                "[CLI] 执行失败: 产品方案尚未完整写入工作区，不能展示。\n- Spec: spec.md\n- Tickets: tickets"));
        assertEquals("任务执行失败，请查看上方错误信息。", fakeBot.mentions.get(0).text());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void 产品分支_漏提交_重试后仍缺失走失败分支() throws IOException, InterruptedException {
        BotRegistry.BotConfig spec = new BotRegistry.BotConfig("pm", "app-id", "app-secret",
                CliId.CLAUDE, "CEO 助理", List.of("to-spec"), "", ws1.toString(), 16);

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            // quietAdapter：首跑与重试都只回 result、无 tool_call → 补救提示词重试一轮仍缺失
            orchestratorFor(spec, quietAdapter).onMessage(userMessage("om_1", "写方案"), fakeBot);
        } finally {
            capture.detach();
        }

        // 补救重试确实发生：首跑 new session + 重试 resume（sessionId 回写后非空）
        assertEquals(1, quietAdapter.newSessionPrompts.size());
        assertEquals(1, quietAdapter.resumePrompts.size());
        assertTrue(quietAdapter.resumePrompts.get(0).contains("立即调用 request_spec_approval"));
        assertTrue(quietAdapter.resumePrompts.get(0).contains("deliveryMode 使用 lark-doc"));
        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString()
                .contains("产品方案已经整理完成，但产品经理没有调用 request_spec_approval"));
        assertTrue(capture.messages().contains(
                "[CLI] 执行失败: 产品方案已经整理完成，但产品经理没有调用 request_spec_approval"));
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    // ---------- 分支二：dispatch 合法性（四 throw + 互斥 throw，文案逐字） ----------

    @Test
    void dispatch_新派发_协作卡与交接通知() throws IOException, InterruptedException {
        String fx = emitBothWorkspaces(
                toolCallLine("dispatch_task",
                        "{\"targetBotId\":\"pm2\",\"objective\":\"整理季度报告\","
                                + "\"instruction\":\"汇总三个表格\",\"expectedOutput\":\"季度报告\"}"),
                "{\"type\":\"result\",\"answer\":\"好的，已整理\",\"sessionId\":\"cli-d1\"}");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(config, new ProbeAdapter("emit:" + fx))
                    .onMessage(userMessage("om_1", "派任务"), fakeBot);
        } finally {
            capture.detach();
        }

        // 协作卡（senderBot=fakeBot 第二张卡）逐字含目标/指令/产出
        assertEquals(2, fakeBot.cards.size());
        String collabCard = fakeBot.cards.get(1).card().toString();
        assertTrue(collabCard.contains("整理季度报告"));
        assertTrue(collabCard.contains("汇总三个表格"));
        assertTrue(collabCard.contains("季度报告"));
        // 通知序 = TS 语句序：正常完成通知 → 协作 mention（dispatch 内）→ 用户交接通知
        assertEquals(3, fakeBot.mentions.size());
        assertEquals("任务已完成，请查看上方结果。", fakeBot.mentions.get(0).text());
        assertTrue(fakeBot.mentions.get(1).text()
                .startsWith("新的协作任务：整理季度报告（任务编号："));
        assertEquals("任务已交给 开发，请查看上方协作消息。", fakeBot.mentions.get(2).text());
        // 派发路径不提前 return：统一落 finally（会话空闲、activeRuns 清空）
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void dispatch校验_非leader抛错() throws IOException, InterruptedException {
        BotRegistry.BotConfig notLeader = new BotRegistry.BotConfig("pm2", "app-id", "app-secret",
                CliId.CLAUDE, "开发", List.of(), "", ws1.toString(), 16);
        String fx = emitBothWorkspaces(
                toolCallLine("dispatch_task",
                        "{\"targetBotId\":\"pm2\",\"objective\":\"整理季度报告\","
                                + "\"instruction\":\"汇总三个表格\"}"),
                "{\"type\":\"result\",\"answer\":\"好的\",\"sessionId\":\"cli-d2\"}");

        orchestratorFor(notLeader, new ProbeAdapter("emit:" + fx))
                .onMessage(userMessage("om_1", "派任务"), fakeBot);

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("只有 CEO 助理可以调用 dispatch_task 派发团队任务"));
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void dispatch校验_目标未注册抛错() throws IOException, InterruptedException {
        String fx = emitBothWorkspaces(
                toolCallLine("dispatch_task",
                        "{\"targetBotId\":\"pm9\",\"objective\":\"整理季度报告\","
                                + "\"instruction\":\"汇总三个表格\"}"),
                "{\"type\":\"result\",\"answer\":\"好的\",\"sessionId\":\"cli-d3\"}");

        orchestratorFor(config, new ProbeAdapter("emit:" + fx))
                .onMessage(userMessage("om_1", "派任务"), fakeBot);

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("团队成员未注册或未启用: pm9"));
    }

    @Test
    void dispatch校验_自派抛错() throws IOException, InterruptedException {
        String fx = emitBothWorkspaces(
                toolCallLine("dispatch_task",
                        "{\"targetBotId\":\"pm\",\"objective\":\"整理季度报告\","
                                + "\"instruction\":\"汇总三个表格\"}"),
                "{\"type\":\"result\",\"answer\":\"好的\",\"sessionId\":\"cli-d4\"}");

        orchestratorFor(config, new ProbeAdapter("emit:" + fx))
                .onMessage(userMessage("om_1", "派任务"), fakeBot);

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("不能把团队任务派发给当前 bot: pm"));
    }

    @Test
    void dispatch校验_协作轮次上限抛错() throws IOException, InterruptedException {
        runtime.collaborationInbox().register(
                collaborationTo("d1a2b3c4d5e6", "pm2", "pm2", 16, ws2.toString()));
        String fx = emitBothWorkspaces(
                toolCallLine("dispatch_task",
                        "{\"targetBotId\":\"pm2\",\"objective\":\"整理季度报告\","
                                + "\"instruction\":\"汇总三个表格\"}"),
                "{\"type\":\"result\",\"answer\":\"好的\",\"sessionId\":\"cli-d5\"}");

        orchestratorFor(config, new ProbeAdapter("emit:" + fx))
                .onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("协作任务已达到轮次上限 16，不能继续派发"));
    }

    @Test
    void 互斥_同时提交产品方案与派发抛错() throws IOException, InterruptedException {
        BotRegistry.BotConfig spec = new BotRegistry.BotConfig("pm", "app-id", "app-secret",
                CliId.CLAUDE, "CEO 助理", List.of("lark-doc"), "", ws1.toString(), 16);
        String fx = emitBothWorkspaces(
                toolCallLine("request_spec_approval",
                        "{\"deliveryMode\":\"lark-doc\",\"title\":\"季度方案\",\"summary\":\"方案摘要\","
                                + "\"documentUrl\":\"https://example.feishu.cn/docx/tok123\"}"),
                toolCallLine("dispatch_task",
                        "{\"targetBotId\":\"pm2\",\"objective\":\"整理季度报告\","
                                + "\"instruction\":\"汇总三个表格\"}"),
                "{\"type\":\"result\",\"answer\":\"好的\",\"sessionId\":\"cli-d6\"}");

        orchestratorFor(spec, new ProbeAdapter("emit:" + fx))
                .onMessage(userMessage("om_1", "派任务"), fakeBot);

        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("不能同时提交产品方案和派发团队任务"));
    }

    // ---------- 分支三：协作回派三态 ----------

    @Test
    void 协作回派_未达上限_回派reportToBotId() throws IOException, InterruptedException {
        runtime.collaborationInbox().register(
                collaborationTo("d1a2b3c4d5e6", "pm2", "pm2", 1, ws2.toString()));

        orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);

        // 回派协作卡：instruction 三段 join("\n\n") 文案逐字（selfName 取 botRuntimes 身份名）
        assertEquals(2, fakeBot.cards.size());
        String collabCard = fakeBot.cards.get(1).card().toString();
        assertTrue(collabCard.contains("CEO 助理 已完成当前协作任务，下面是它的结果："));
        assertTrue(collabCard.contains("好的，已完成"));
        assertTrue(collabCard.contains(
                "请基于这份结果继续组织后续工作：仍需其他成员参与时使用 dispatch_task 继续派发；"
                        + "已经可以交付时，直接向用户汇总结论。"));
        assertTrue(collabCard.contains("继续推进原任务，或在已经完成时向用户给出最终结论。"));
        // 回派路径无用户通知；唯一的 mention 来自 CollaborationService 的协作提醒
        assertEquals(1, fakeBot.mentions.size());
        assertTrue(fakeBot.mentions.get(0).text().startsWith("协作结果已经返回（任务编号："));
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void 协作回派_达到轮次上限_通知决定下一步() throws IOException, InterruptedException {
        runtime.collaborationInbox().register(
                collaborationTo("d1a2b3c4d5e6", "pm2", "pm2", 16, ws2.toString()));

        orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);

        // 不回派：只有任务卡一张；通知 collaboration.ownerOpenId（ou_owner）
        assertEquals(1, fakeBot.cards.size());
        assertEquals(1, fakeBot.mentions.size());
        assertEquals("协作任务“整理季度报告”已达到 16 轮上限，请查看上方结果并决定下一步。",
                fakeBot.mentions.get(0).text());
        assertEquals("ou_owner", fakeBot.mentions.get(0).target().openId());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
    }

    @Test
    void 协作回派_reportToIsSelf_通知已经完成() throws IOException, InterruptedException {
        runtime.collaborationInbox().register(
                collaboration("d1a2b3c4d5e6", "pm2", 1, ws2.toString()));

        orchestrator.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);

        assertEquals(1, fakeBot.cards.size());
        assertEquals(1, fakeBot.mentions.size());
        assertEquals("协作任务“整理季度报告”已经完成，请查看上方结果。",
                fakeBot.mentions.get(0).text());
        assertEquals("ou_owner", fakeBot.mentions.get(0).target().openId());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
    }

    // ---------- catch：取消（三种 cancelMode/来源文案） ----------

    @Test
    void 取消分支_非协作_默认文案_通知发送者() throws Exception {
        BotMessageOrchestrator hanging = orchestratorFor(config, new ProbeAdapter("hang:30000"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                hanging.onMessage(userMessage("om_1", "长时间任务"), fakeBot);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            String sid = awaitActiveRun();
            runtime.activeRuns().get(sid).token().cancel();
            worker.join(15_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());

            assertTrue(capture.messages().contains("[CLI] 任务已取消"));
            ObjectNode card = findUpdateByTemplate(fakeBot.updates, "grey");
            assertNotNull(card);
            assertTrue(card.toString().contains("本次任务已停止。你可以继续在当前话题里提问。"));
            assertEquals("任务已停止，请查看上方状态。", fakeBot.mentions.get(0).text());
            assertEquals("ou_alice", fakeBot.mentions.get(0).target().openId());
            assertEquals(SessionStatus.IDLE, store.load().get(0).status());
            assertTrue(runtime.activeRuns().isEmpty());
        } finally {
            capture.detach();
            runtime.activeRuns().values().forEach(run -> run.token().cancel());
            worker.join(1_000);
        }
    }

    @Test
    void 取消分支_close模式文案() throws Exception {
        BotMessageOrchestrator hanging = orchestratorFor(config, new ProbeAdapter("hang:30000"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                hanging.onMessage(userMessage("om_1", "长时间任务"), fakeBot);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            String sid = awaitActiveRun();
            runtime.activeRuns().get(sid).setCancelMode(CancelMode.CLOSE);
            runtime.activeRuns().get(sid).token().cancel();
            worker.join(15_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());

            ObjectNode card = findUpdateByTemplate(fakeBot.updates, "grey");
            assertNotNull(card);
            assertTrue(card.toString().contains("本次任务已停止，当前会话已经关闭。"));
        } finally {
            runtime.activeRuns().values().forEach(run -> run.token().cancel());
            worker.join(1_000);
        }
    }

    @Test
    void 取消分支_协作来源_通知bot身份() throws Exception {
        runtime.collaborationInbox().register(
                collaboration("d1a2b3c4d5e6", "pm2", 1, ws2.toString()));
        BotMessageOrchestrator hanging = orchestratorFor(config, new ProbeAdapter("hang:30000"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                hanging.onMessage(botMessage("om_b1", "d1a2b3c4d5e6"), fakeBot);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            String sid = awaitActiveRun();
            // ownerOpenId 取协作来源（Task 2 断言迁移至执行中可观测点）
            assertEquals("ou_owner", runtime.activeRuns().get(sid).ownerOpenId());
            runtime.activeRuns().get(sid).token().cancel();
            worker.join(15_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());

            assertEquals("任务已停止，请查看上方状态。", fakeBot.mentions.get(0).text());
            // senderRuntime=pm2（协作来源）→ 通知 bot 身份而非消息 sender
            assertEquals("ou_pm2", fakeBot.mentions.get(0).target().openId());
            assertEquals("开发", fakeBot.mentions.get(0).target().name());
            assertEquals(SessionStatus.IDLE, store.load().get(0).status());
            assertTrue(runtime.activeRuns().isEmpty());
        } finally {
            runtime.activeRuns().values().forEach(run -> run.token().cancel());
            worker.join(1_000);
        }
    }

    @Test
    void 取消分支_compact_整理已停止文案() throws Exception {
        preseedIdleSession("cli-1");
        String fx = fixture(ws1, "[{\"type\":\"system\",\"subtype\":\"compact_boundary\"}]");
        ProbeAdapter hangingCompact = new ProbeAdapter(new String[]{"emit:" + resultFx},
                new String[]{"emit-hang:" + fx + ":30000"});
        BotMessageOrchestrator hanging = orchestratorFor(config, hangingCompact);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                hanging.onMessage(userMessage("om_1", "/compact"), fakeBot);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            String sid = awaitActiveRun();
            runtime.activeRuns().get(sid).token().cancel();
            worker.join(15_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());

            ObjectNode card = findUpdateByTemplate(fakeBot.updates, "grey");
            assertNotNull(card);
            assertTrue(card.toString().contains("整理已停止，当前 CLI 会话没有改变。"));
            // compact 取消不回写 CLI 会话 ID
            assertEquals("cli-1", store.load().get(0).cliSessionId());
            assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        } finally {
            runtime.activeRuns().values().forEach(run -> run.token().cancel());
            worker.join(1_000);
        }
    }

    // ---------- catch：失败 ----------

    @Test
    void 失败分支_失败卡与通知() throws IOException, InterruptedException {
        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(config, new ProbeAdapter("stderr:boom"))
                    .onMessage(userMessage("om_1", "做任务"), fakeBot);
        } finally {
            capture.detach();
        }

        assertTrue(capture.messages().contains("[CLI] 执行失败: boom"));
        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("执行没有完成。你可以调整指令后，在当前话题里重试。"));
        assertTrue(card.toString().contains("boom"));
        assertEquals("任务执行失败，请查看上方错误信息。", fakeBot.mentions.get(0).text());
        assertEquals("ou_alice", fakeBot.mentions.get(0).target().openId());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void compact失败_无boundary事件走失败分支() throws IOException, InterruptedException {
        preseedIdleSession("cli-1");
        String fx = emitBothWorkspaces("{\"type\":\"result\",\"result\":\"hello\"}");
        ProbeAdapter badCompact = new ProbeAdapter(new String[]{"emit:" + resultFx},
                new String[]{"emit:" + fx});

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(config, badCompact).onMessage(userMessage("om_1", "/compact"), fakeBot);
        } finally {
            capture.detach();
        }

        assertTrue(capture.messages()
                .contains("[CLI] 执行失败: Claude Code 没有返回上下文整理完成事件"));
        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "red");
        assertNotNull(card);
        assertTrue(card.toString().contains("上下文整理失败，当前 CLI 会话没有改变。"));
        assertTrue(card.toString().contains("Claude Code 没有返回上下文整理完成事件"));
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
    }

    // ---------- 收尾链穿越序对照（裁决 6 嵌套模板） ----------

    @Test
    void 穿越序_thenFinish抛错由catch接管_不触发兜底() throws IOException, InterruptedException {
        fakeBot.updateCardError = new RuntimeException("card boom");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);
        } finally {
            capture.detach();
        }

        // then 主体 finish 抛错 → catch 失败分支（finish 幂等跳过）→ 通知照发 → finally 清理
        assertTrue(capture.messages().contains("[CLI] 执行失败: card boom"));
        assertFalse(capture.messages().stream().anyMatch(m -> m.contains("[任务] 回传或收尾失败:")));
        assertEquals("任务执行失败，请查看上方错误信息。", fakeBot.mentions.get(0).text());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void 穿越序_catchFinish抛错落finally再落外层兜底() throws IOException, InterruptedException {
        fakeBot.updateCardError = new RuntimeException("card boom");

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestratorFor(config, new ProbeAdapter("stderr:boom"))
                    .onMessage(userMessage("om_1", "做任务"), fakeBot);
        } finally {
            capture.detach();
        }

        // catch 主体 finish（首次 finish）抛错 → finally 清理 → 外层兜底 log
        assertTrue(capture.messages().contains("[CLI] 执行失败: boom"));
        assertTrue(capture.messages().contains("[任务] 回传或收尾失败: card boom"));
        // finish 抛错在通知之前：无失败通知
        assertTrue(fakeBot.mentions.isEmpty());
        assertEquals(SessionStatus.IDLE, store.load().get(0).status());
        assertTrue(runtime.activeRuns().isEmpty());
    }

    @Test
    void 穿越序_markSessionIdle失败_日志容错不外抛() throws IOException, InterruptedException {
        // 仅 IDLE 落盘失败：前半 resolve（CREATING）与 transition ACTIVE 必须放行
        //（否则消息进不了执行段），finally 的 markSessionIdle 才是本用例的受测点
        SessionStore failingStore = new SessionStore() {
            @Override
            public List<Session> load() throws IOException {
                return store.load();
            }

            @Override
            public void save(List<Session> toSave) throws IOException {
                if (toSave.stream().anyMatch(s -> s.status() == SessionStatus.IDLE)) {
                    throw new IOException("disk full");
                }
                store.save(toSave);
            }
        };
        SessionManager failingSessions = SessionManager.open(failingStore);
        AppRuntime failingRuntime = new AppRuntime(failingSessions,
                new TeamRegistry("pm", List.of(config)),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                ConcurrentHashMap.newKeySet(), new Collaboration.CollaborationInbox(),
                new Clarification.ClarificationFlowStore(), new ProductSpec.ProductSpecFlowStore());
        failingRuntime.botRuntimes().put("pm",
                new BotRuntime(config, fakeBot, new BotIdentity("ou_pm", "CEO 助理")));
        BotMessageOrchestrator failing = new BotMessageOrchestrator(failingRuntime, config,
                new CollaborationService(failingRuntime), ProductDeliveryMode.LARK_DOC,
                id -> quietAdapter);

        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            failing.onMessage(userMessage("om_1", "你好"), fakeBot);
        } finally {
            capture.detach();
        }

        // 执行正常完成；finally 的 markSessionIdle 失败被内层 try/catch 吞掉并打日志，
        // 不触发外层兜底（裁决 6：finally 异常才落兜底，markSessionIdle 自身容错）
        assertTrue(capture.messages().contains("[会话] 保存空闲状态失败: disk full"));
        assertFalse(capture.messages().stream().anyMatch(m -> m.contains("[任务] 回传或收尾失败:")));
        ObjectNode card = findUpdateByTemplate(fakeBot.updates, "green");
        assertNotNull(card);
        assertTrue(card.toString().contains("好的，已完成"));
        assertEquals("任务已完成，请查看上方结果。", fakeBot.mentions.get(0).text());
    }

    // ---------- 心跳机制（裁决 7 self-rescheduling，手动调度器直测） ----------

    @Test
    void 心跳_自重排与停止幂等_停止后不再渲染() {
        class ManualScheduler implements CardScheduler {
            record ScheduledCall(Runnable task, long delayMs) {}

            final List<ScheduledCall> calls = new ArrayList<>();
            final List<Runnable> cancelled = new ArrayList<>();

            @Override
            public Cancellable schedule(Runnable task, long delayMs) {
                calls.add(new ScheduledCall(task, delayMs));
                return () -> cancelled.add(task);
            }
        }
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger renders = new AtomicInteger();
        BotMessageOrchestrator.ProgressHeartbeat heartbeat =
                new BotMessageOrchestrator.ProgressHeartbeat(
                        scheduler, renders::incrementAndGet, 1_000);

        // start 即排下一次（delay = interval，无 fixed-rate）
        heartbeat.start();
        assertEquals(1, scheduler.calls.size());
        assertEquals(1_000, scheduler.calls.get(0).delayMs());

        // tick：渲染 + 尾部自重排
        scheduler.calls.get(0).task().run();
        assertEquals(1, renders.get());
        assertEquals(2, scheduler.calls.size());
        assertEquals(1_000, scheduler.calls.get(1).delayMs());

        // 停止幂等：挂起句柄被取消（手动调度器记录每次 cancel 调用，真调度器 cancel 天然幂等）；
        // 已取消句柄若仍触发，stopped 标志兜底不渲染、不重排
        heartbeat.stop();
        heartbeat.stop();
        assertFalse(scheduler.cancelled.isEmpty());
        scheduler.calls.get(1).task().run();
        assertEquals(1, renders.get());
        assertEquals(2, scheduler.calls.size());
    }

    // ---------- composeTaskText（taskText 三选一 + 附件段，纯函数直测） ----------

    @Test
    void taskText三选一_澄清消息优先且prompt优先于还原文本() {
        Clarification.ClarificationFlow flow = Clarification.flowsForTest(
                "tok", clarificationRequest(), 0, List.of());

        String withCliRequest = BotMessageOrchestrator.composeTaskText(flow, null,
                new CliRequest(CliId.CLAUDE, "补充信息"), "还原文本", List.of());
        assertTrue(withCliRequest.contains("用户的新消息：补充信息"));
        assertTrue(withCliRequest.contains("此前还没有确认任何选项。"));

        String withoutCliRequest = BotMessageOrchestrator.composeTaskText(flow, null,
                null, "还原文本", List.of());
        assertTrue(withoutCliRequest.contains("用户的新消息：还原文本"));
    }

    @Test
    void taskText三选一_协作prompt() {
        Collaboration.CollaborationMessage collab = collaboration("d1", "pm2", 1,
                ws2.toString());
        String text = BotMessageOrchestrator.composeTaskText(null, collab,
                new CliRequest(CliId.CLAUDE, "不该被用"), "还原文本", List.of());
        assertTrue(text.contains("协作目标：整理季度报告"));
        assertTrue(text.contains("执行要求：汇总三个表格的数据"));
        assertFalse(text.contains("不该被用"));
    }

    @Test
    void taskText三选一_无澄清无协作_cliRequest优先() {
        assertEquals("做事", BotMessageOrchestrator.composeTaskText(null, null,
                new CliRequest(CliId.CLAUDE, "做事"), "还原文本", List.of()));
        assertEquals("还原文本", BotMessageOrchestrator.composeTaskText(null, null,
                null, "还原文本", List.of()));
    }

    @Test
    void 附件段文案逐字() {
        assertEquals("任务\n\n【用户上传的附件/图片】\n- C:\\a.png\n- D:\\b.txt"
                        + "\n请直接从上述本地路径读取并分析。",
                BotMessageOrchestrator.composeTaskText(null, null, null, "任务",
                        List.of("C:\\a.png", "D:\\b.txt")));
        assertEquals("任务", BotMessageOrchestrator.composeTaskText(null, null, null,
                "任务", List.of()));
    }
}

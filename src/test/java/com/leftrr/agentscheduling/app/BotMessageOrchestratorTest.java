package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
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
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.core.TopicAddress;
import com.leftrr.agentscheduling.core.TopicTask;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.im.IncomingMessage;
import com.leftrr.agentscheduling.im.Mention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 Task 2：BotMessageOrchestrator 前半（index.ts L151-377）。
 * 替身口径（brief）：fake Bot 记录调用、真 SessionManager（临时目录 JsonSessionStore）、
 * 前半不拉起任何 CLI（cliAdapter 只取 displayName/registry，绝不执行）。
 * 每条文案逐字断言（LogbackCapture 截获 SLF4J 消息体）。
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

    @BeforeEach
    void setUp() throws IOException {
        ws1 = Files.createDirectories(temp.resolve("ws1"));
        ws2 = Files.createDirectories(temp.resolve("ws2"));
        config = new BotRegistry.BotConfig("pm", "app-id", "app-secret", CliId.CLAUDE,
                "CEO 助理", List.of("to-spec"), "", ws1.toString(), 16);
        BotRegistry.BotConfig config2 = new BotRegistry.BotConfig("pm2", "app-id-2",
                "app-secret-2", CliId.CLAUDE, "开发", List.of(), "", ws1.toString(), 16);

        store = new JsonSessionStore(temp.resolve("sessions.json").toString(), "pm",
                Map.of("pm", ws1.toString()));
        sessions = SessionManager.open(store);
        runtime = new AppRuntime(sessions, new TeamRegistry("pm", List.of(config, config2)),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                ConcurrentHashMap.newKeySet(), new Collaboration.CollaborationInbox(),
                new Clarification.ClarificationFlowStore(), new ProductSpec.ProductSpecFlowStore());
        orchestrator = new BotMessageOrchestrator(runtime, config,
                new CollaborationService(runtime), ProductDeliveryMode.LARK_DOC);

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

        final List<String> replies = new ArrayList<>();
        final List<CardCall> cards = new ArrayList<>();
        final List<CardUpdate> updates = new ArrayList<>();
        final List<Download> downloads = new ArrayList<>();
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
            return messageId;
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
        return new Collaboration.CollaborationMessage(dispatchId, "task-1", "ou_owner", null,
                fromBotId, "pm", "pm", "整理季度报告", "汇总三个表格的数据", null, round, 16,
                workspaceDir);
    }

    private String sessionIdInFlight() {
        assertEquals(1, runtime.activeRuns().size());
        return runtime.activeRuns().keySet().iterator().next();
    }

    // ---------- 主链路 ----------

    @Test
    void 普通消息_五条调试日志逐字_任务卡与运行态() throws IOException, InterruptedException {
        LogbackCapture capture = LogbackCapture.attach(BotMessageOrchestrator.class);
        try {
            orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);
            String sid = sessionIdInFlight();

            assertEquals(List.of(
                    "[收到] chat=" + CHAT_ID + " threadId=" + THREAD_ID + " rootId=null"
                            + " sender=ou_alice",
                    "  原文: 你好",
                    "  还原: 你好",
                    "  mentions: (无)",
                    "  [会话] 新建 id=" + sid + " status=creating",
                    "[卡片] 已发送 message_id=om-card-1 inThread=true"),
                    capture.messages());
        } finally {
            capture.detach();
        }

        String sid = sessionIdInFlight();
        assertEquals(SessionStatus.ACTIVE, sessions.get(sid).status());
        assertEquals(CliId.CLAUDE, sessions.get(sid).cliId());
        ActiveRun activeRun = runtime.activeRuns().get(sid);
        assertEquals("ou_alice", activeRun.ownerOpenId());
        assertFalse(activeRun.token().isCancelled());

        assertTrue(fakeBot.replies.isEmpty());
        assertEquals(1, fakeBot.cards.size());
        FakeBot.CardCall card = fakeBot.cards.get(0);
        assertEquals("om_1", card.messageId());
        assertTrue(card.replyInThread());
        ObjectNode cardJson = (ObjectNode) card.card();
        assertEquals("Claude Code · 执行中",
                cardJson.path("header").path("title").path("content").asText());
        assertEquals("blue", cardJson.path("header").path("template").asText());
        assertTrue(cardJson.toString().contains("正在理解任务"));
        assertTrue(cardJson.toString().contains(sid));
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
        orchestrator.onMessage(userMessage("om_pre", "你好"), fakeBot);

        fakeBot.replies.clear();
        fakeBot.cards.clear();
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
        String sid = sessionIdInFlight();
        assertEquals(SessionStatus.ACTIVE, sessions.get(sid).status());
        assertEquals(ws2.toString(), sessions.get(sid).workspaceDir());
        assertEquals("ou_owner", runtime.activeRuns().get(sid).ownerOpenId());
        assertTrue(fakeBot.replies.isEmpty());
        assertEquals(1, fakeBot.cards.size());
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
        assertEquals(1, runtime.activeRuns().size());
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
        assertEquals(SessionStatus.ACTIVE, session.status());
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
        assertEquals(1, fakeBot.updates.size());
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
        orchestrator.onMessage(userMessage("om_1", "你好"), fakeBot);
        String sid = sessionIdInFlight();
        sessions.setCliSessionId(sid, "cli-1");
        sessions.transition(sid, SessionStatus.IDLE);

        orchestrator.onMessage(userMessage("om_2", "/compact"), fakeBot);

        assertEquals(2, fakeBot.cards.size());
        ObjectNode card = (ObjectNode) fakeBot.cards.get(1).card();
        assertEquals("整理上下文 · 执行中",
                card.path("header").path("title").path("content").asText());
        assertTrue(card.toString().contains("正在调用 Claude Code 原生上下文整理"));
    }

    @Test
    void compact_codex带指令_默认策略文案() throws IOException, InterruptedException {
        orchestrator.onMessage(userMessage("om_1", "/codex 检查项目"), fakeBot);
        String sid = sessionIdInFlight();
        sessions.setCliSessionId(sid, "cli-1");
        sessions.transition(sid, SessionStatus.IDLE);

        orchestrator.onMessage(userMessage("om_2", "/compact 保持要点"), fakeBot);

        assertEquals(2, fakeBot.cards.size());
        ObjectNode card = (ObjectNode) fakeBot.cards.get(1).card();
        assertEquals("整理上下文 · 执行中",
                card.path("header").path("title").path("content").asText());
        assertTrue(card.toString().contains("Codex 正在使用原生默认策略整理上下文"));
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

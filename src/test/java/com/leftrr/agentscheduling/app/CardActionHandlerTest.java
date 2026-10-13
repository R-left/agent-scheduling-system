package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.IsoTime;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.SessionStore;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.BotOptions;
import com.leftrr.agentscheduling.im.CardAction;
import com.leftrr.agentscheduling.im.CardActionResponse;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 7：createCardActionHandler 测试。
 * 对齐 TS 版 src/app/card-action-handler.ts（295 行）：四分支全部 toast 文案逐字（含中文标点）、
 * 卡片构造器产物（header 标题/模板色钉产物即钉构造器调用）、approve 派发六段 instruction 与
 * expectedOutput 逐字、answer_clarification complete 路径裁决 4 时序（内存态变更先于返回、
 * 续跑启动器被调、默认启动器虚拟线程异常吞掉只记 stderr）、resume 分支 Claude 文件系统替身、
 * abort_task 四 outcome。工厂的「虚拟线程执行 + join」包裹（裁决 5）由全部用例同步拿到正确
 * 返回值间接钉住。
 *
 * <p>替身口径（计划裁决 14 与 brief）：真 stores + 真 SessionManager（不落盘），fake Bot
 * （四分支不发消息，只 consume Bot 门面）；CollaborationService 用子类替身记录 dispatch 入参；
 * resume 成功路径走 NativeSessions 的 Claude 分支（纯文件系统读，无子进程）——把
 * System.setProperty("user.home") 指到临时目录伪造 ~/.claude/projects 布局（env
 * CLAUDE_CONFIG_DIR 不可注入，缺席才可测；Task 5 同手法）；Codex 分支按裁决 14 不测留 M7。
 * 「approve 返回 null」用 ProductSpecFlowStore 子类替身（内存版正常路径不可达）。
 * 「答案没有保存」用零选项问题（answerWithRecommendation 无推荐项返回 null 的真实可达路径）。</p>
 */
class CardActionHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- 替身 ----------

    /** 四分支不发消息的 fake Bot：replyCard 返回值可注入 null（供续跑抛逐字文案用）。 */
    private static final class FakeBot implements Bot {
        String replyCardId = "om-card-1";

        @Override
        public BotIdentity getIdentity() {
            return new BotIdentity("ou_bot", "产品经理");
        }

        @Override
        public String reply(String messageId, String text, boolean replyInThread) {
            return replyCardId;
        }

        @Override
        public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
            return replyCardId;
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

    /** 记录 dispatch 入参的 CollaborationService 替身；failure 注入后 dispatch 抛出。 */
    private static final class RecordingCollaborationService extends CollaborationService {
        final List<CollaborationService.CollaborationDispatch> calls = new ArrayList<>();
        RuntimeException failure;

        RecordingCollaborationService(AppRuntime runtime) {
            super(runtime);
        }

        @Override
        public void dispatch(CollaborationDispatch options) {
            if (failure != null) {
                throw failure;
            }
            calls.add(options);
        }
    }

    /** 第二次 save 抛 IOException 的 SessionStore（resolve 第 1 次、transition 第 2 次）。 */
    private static final class FailingSecondSaveStore implements SessionStore {
        private int saves;

        @Override
        public List<Session> load() {
            return List.of();
        }

        @Override
        public void save(List<Session> sessions) throws IOException {
            saves++;
            if (saves >= 2) {
                throw new IOException("存储不可用");
            }
        }
    }

    /** 装配好的运行时。 */
    private record Fixture(AppRuntime runtime, SessionManager sessions, FakeBot bot,
            BotRegistry.BotConfig config, Session session,
            RecordingCollaborationService collaboration) {
    }

    private static BotRegistry.BotConfig config() {
        return new BotRegistry.BotConfig("bot-1", "app-1", "secret", CliId.CLAUDE, "产品经理",
                List.of("to-spec"), null, "D:/ws-default", 3);
    }

    private static Fixture fixture(String workspaceDir, SessionManager sessions) throws IOException {
        BotRegistry.BotConfig config = config();
        Session session = sessions.resolve(
                new MessageAddress("om_msg_1", "oc_chat_1", "om_thread_1", "om_thread_1"),
                CliId.CLAUDE, "bot-1", workspaceDir).session();
        AppRuntime runtime = new AppRuntime(sessions, new TeamRegistry("bot-1", List.of(config)));
        FakeBot bot = new FakeBot();
        runtime.botRuntimes().put("bot-1", new BotRuntime(config, bot, new BotIdentity("ou_bot", "产品经理")));
        return new Fixture(runtime, sessions, bot, config, session,
                new RecordingCollaborationService(runtime));
    }

    private static Fixture fixture() throws IOException {
        return fixture("D:/ws-topic", new SessionManager());
    }

    private static Fixture fixture(String workspaceDir) throws IOException {
        return fixture(workspaceDir, new SessionManager());
    }

    /** 复制 Fixture，仅替换 productSpecFlows store（其余字段/容器引用共享）。 */
    private static Fixture withProductSpecStore(Fixture f, ProductSpec.ProductSpecFlowStore store) {
        AppRuntime runtime = new AppRuntime(f.runtime().sessions(), f.runtime().teamRegistry(),
                f.runtime().activeRuns(), f.runtime().contextWindows(), f.runtime().botRuntimes(),
                f.runtime().processedCollaborationTurns(), f.runtime().collaborationInbox(),
                f.runtime().clarificationFlows(), store);
        return new Fixture(runtime, f.sessions(), f.bot(), f.config(), f.session(), f.collaboration());
    }

    // ---------- 输入构造 ----------

    private static ObjectNode value(String action) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("action", action);
        return node;
    }

    private static CardAction action(String operatorOpenId, String messageId, JsonNode value) {
        return new CardAction(operatorOpenId, null, messageId, value, MAPPER.createObjectNode());
    }

    private static CardAction action(String operatorOpenId, JsonNode value) {
        return action(operatorOpenId, "om_msg_1", value);
    }

    private static CardAction action(String operatorOpenId, JsonNode value, JsonNode formValue) {
        return new CardAction(operatorOpenId, null, "om_msg_1", value, formValue);
    }

    private static ProductSpec.ProductSpecRequest localRequest() {
        return new ProductSpec.ProductSpecRequest.Local("local", "会议纪要方案", "整理纪要",
                "spec/product-spec.md", "spec/tickets");
    }

    private static ProductSpec.ProductSpecRequest larkRequest() {
        return new ProductSpec.ProductSpecRequest.Lark("lark-doc", "会议纪要方案", "整理纪要",
                "https://example.feishu.cn/docx/abc123");
    }

    private static ProductSpec.ProductSpecFlow specFlow(Fixture f, String sessionId,
            Collaboration.CollaborationOrigin collaboration) throws IOException {
        return f.runtime().productSpecFlows().create(new ProductSpec.CreateFlowOptions(
                "task-1", "bot-1", sessionId, "ou_owner", null, collaboration, localRequest()));
    }

    private static ObjectNode approveValue(String flowToken) {
        ObjectNode value = value("approve_product_spec");
        value.put("flowToken", flowToken);
        return value;
    }

    /** 两题澄清 flow：q1（推荐 a）、q2（无推荐）。 */
    private static Clarification.ClarificationFlow clarificationFlow(Fixture f, String sessionId) {
        Clarification.ClarificationRequest request = new Clarification.ClarificationRequest(
                "需求澄清", "", List.of(
                new Clarification.Question("q1", "范围？", List.of(
                        new Clarification.Option("a", "方案A"), new Clarification.Option("b", "方案B")), "a"),
                new Clarification.Question("q2", "优先级？", List.of(
                        new Clarification.Option("p", "P0"), new Clarification.Option("q", "P1")), null)));
        return f.runtime().clarificationFlows().create(new Clarification.CreateFlowOptions(
                "task-1", "bot-1", sessionId, "ou_owner", null, null,
                "om_original", null, false, request));
    }

    private static ObjectNode answerValue(String flowToken, String questionId) {
        ObjectNode value = value("answer_clarification");
        value.put("flowToken", flowToken);
        value.put("questionId", questionId);
        return value;
    }

    // ---------- 调用入口 ----------

    private static CardActionResponse handle(Fixture f, CardAction action) {
        return handle(f, action, null);
    }

    /** starter 为 null 走生产默认启动器；非 null 注入测试启动器（裁决 4 测试缝，包私有重载）。 */
    private static CardActionResponse handle(Fixture f, CardAction action,
            Consumer<ClarificationRunner.ClarificationContinuation> starter) {
        CardActionHandler.CardActionHandlerOptions options =
                new CardActionHandler.CardActionHandlerOptions(
                        f.runtime(), f.config(), f.collaboration(), ProductDeliveryMode.LARK_DOC);
        BotOptions.CardActionListener listener = starter == null
                ? CardActionHandler.createCardActionHandler(options)
                : CardActionHandler.createCardActionHandler(options, starter);
        return listener.onCardAction(action);
    }

    private static void assertToast(CardActionResponse response, CardActionResponse.ToastType type,
            String content) {
        assertNotNull(response);
        assertNotNull(response.toast(), "应有 toast");
        assertEquals(type, response.toast().type());
        assertEquals(content, response.toast().content());
    }

    // ---------- approve_product_spec ----------

    @Test
    void approveFlowToken守卫三路返回失效error文案() throws IOException {
        Fixture f = fixture();

        // 流程不存在
        CardActionResponse missing = handle(f, action("ou_owner", approveValue("no-such-token")));
        assertToast(missing, CardActionResponse.ToastType.ERROR, "这份产品方案已经失效。");
        assertNull(missing.card());

        // botId 不符（流程属于别的 bot）
        ProductSpec.ProductSpecFlow otherBotFlow = f.runtime().productSpecFlows().create(
                new ProductSpec.CreateFlowOptions("task-2", "bot-2", "s1", "ou_owner", null,
                        null, localRequest()));
        CardActionResponse wrongBot =
                handle(f, action("ou_owner", approveValue(otherBotFlow.token())));
        assertToast(wrongBot, CardActionResponse.ToastType.ERROR, "这份产品方案已经失效。");

        // messageId 缺失
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(), null);
        CardActionResponse noMessage =
                handle(f, action("ou_owner", (String) null, approveValue(flow.token())));
        assertToast(noMessage, CardActionResponse.ToastType.ERROR, "这份产品方案已经失效。");
        // 三路都不应真正确认
        assertEquals(ProductSpec.SpecStatus.PENDING, flow.status());
    }

    @Test
    void approveExpired流程返回warning与失效卡() throws IOException {
        Fixture f = fixture();
        // 同 (taskId, botId) 再 create 一次 → 旧流程置 expired
        ProductSpec.ProductSpecFlow expired = specFlow(f, f.session().id(), null);
        specFlow(f, f.session().id(), null);
        assertEquals(ProductSpec.SpecStatus.EXPIRED, expired.status());

        CardActionResponse response = handle(f, action("ou_owner", approveValue(expired.token())));

        assertToast(response, CardActionResponse.ToastType.WARNING, "这份产品方案已经失效。");
        assertEquals("产品方案确认已失效", response.card().get("header").get("title").get("content").asText());
        assertEquals("grey", response.card().get("header").get("template").asText());
    }

    @Test
    void approve已确认流程幂等返回info与已确认卡() throws IOException {
        Fixture f = fixture();
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(), null);
        f.runtime().productSpecFlows().approve(flow.token());

        CardActionResponse response = handle(f, action("ou_owner", approveValue(flow.token())));

        assertToast(response, CardActionResponse.ToastType.INFO, "产品方案已经确认。");
        assertEquals("产品方案已确认", response.card().get("header").get("title").get("content").asText());
        assertEquals("green", response.card().get("header").get("template").asText());
        assertEquals(0, f.collaboration().calls.size(), "幂等路径不派发");
    }

    @Test
    void approve非发起人返回只有任务发起人可以确认() throws IOException {
        Fixture f = fixture();
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(), null);

        CardActionResponse response = handle(f, action("ou_other", approveValue(flow.token())));

        assertToast(response, CardActionResponse.ToastType.WARNING, "只有任务发起人可以确认。");
        assertNull(response.card());
        assertEquals(ProductSpec.SpecStatus.PENDING, flow.status(), "不应确认");
    }

    @Test
    void approve返回null时提示方案状态已经更新() throws IOException {
        Fixture f = fixture();
        ProductSpec.ProductSpecFlowStore approveNullStore = new ProductSpec.ProductSpecFlowStore() {
            @Override
            public ProductSpec.ProductSpecFlow approve(String token) {
                return null;
            }
        };
        Fixture f2 = withProductSpecStore(f, approveNullStore);
        ProductSpec.ProductSpecFlow flow = f2.runtime().productSpecFlows().create(
                new ProductSpec.CreateFlowOptions("task-1", "bot-1", f2.session().id(),
                        "ou_owner", null, null, localRequest()));

        CardActionResponse response = handle(f2, action("ou_owner", approveValue(flow.token())));

        assertToast(response, CardActionResponse.ToastType.WARNING, "方案状态已经更新。");
        assertNull(response.card());
    }

    @Test
    void approve无协作origin时直接成功不发派发() throws IOException {
        Fixture f = fixture();
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(), null);

        CardActionResponse response = handle(f, action("ou_owner", approveValue(flow.token())));

        assertToast(response, CardActionResponse.ToastType.SUCCESS, "产品方案已确认。");
        assertEquals("产品方案已确认", response.card().get("header").get("title").get("content").asText());
        assertEquals(0, f.collaboration().calls.size());
        assertEquals(ProductSpec.SpecStatus.APPROVED, flow.status());
    }

    @Test
    void approve协作派发成功dispatch入参逐字() throws IOException {
        Fixture f = fixture();
        Collaboration.CollaborationOrigin origin =
                new Collaboration.CollaborationOrigin("task-1", "bot-1", "ceo-bot", 1, 3);
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(), origin);

        CardActionResponse response = handle(f, action("ou_owner", approveValue(flow.token())));

        assertEquals(1, f.collaboration().calls.size());
        CollaborationService.CollaborationDispatch dispatch = f.collaboration().calls.get(0);
        assertSame(f.config(), dispatch.senderConfig());
        assertSame(f.bot(), dispatch.senderBot());
        assertEquals("om_msg_1", dispatch.replyToMessageId());
        assertEquals("ceo-bot", dispatch.targetBotId());
        assertEquals("task-1", dispatch.taskId());
        assertEquals("ou_owner", dispatch.ownerOpenId());
        assertNull(dispatch.ownerUnionId());
        assertEquals("ceo-bot", dispatch.reportToBotId());
        assertEquals("产品方案已确认：会议纪要方案", dispatch.objective());
        // 六段 join("\n\n") 逐字（含 dispatch_task 提示段）
        assertEquals(String.join("\n\n",
                "产品经理 已经完成产品方案，用户确认通过。",
                "方案标题：会议纪要方案",
                "方案摘要：整理纪要",
                "Spec：spec/product-spec.md\nTickets：spec/tickets",
                "确认记录：" + flow.approvedAt(),
                "请基于这份已确认方案继续组织后续工作：需要开发者实现时，使用 dispatch_task 把方案交给 developer。"),
                dispatch.instruction());
        assertEquals("继续推进原任务，或在已经完成时向用户给出最终结论。", dispatch.expectedOutput());
        assertEquals(2, dispatch.round(), "round 应为 origin.round + 1");
        assertEquals(3, dispatch.maxRounds());
        assertEquals("D:/ws-topic", dispatch.workspaceDir(), "会话存在时取会话工作目录");
        // 成功 toast + 已确认卡
        assertToast(response, CardActionResponse.ToastType.SUCCESS, "产品方案已确认。");
        assertEquals("产品方案已确认", response.card().get("header").get("title").get("content").asText());
    }

    @Test
    void approve协作派发无会话时workspaceDir回落config且larkDoc描述逐字() throws IOException {
        Fixture f = fixture();
        Collaboration.CollaborationOrigin origin =
                new Collaboration.CollaborationOrigin("task-1", "bot-1", "ceo-bot", 2, 3);
        ProductSpec.ProductSpecFlow flow = f.runtime().productSpecFlows().create(
                new ProductSpec.CreateFlowOptions("task-1", "bot-1", "missing-session",
                        "ou_owner", "ouu_owner", origin, larkRequest()));

        CardActionResponse response = handle(f, action("ou_owner", approveValue(flow.token())));

        assertEquals(1, f.collaboration().calls.size());
        CollaborationService.CollaborationDispatch dispatch = f.collaboration().calls.get(0);
        assertEquals("D:/ws-default", dispatch.workspaceDir(), "会话缺失回落 config.workspaceDir");
        assertEquals("ouu_owner", dispatch.ownerUnionId());
        assertTrue(dispatch.instruction().contains("文档 URL：https://example.feishu.cn/docx/abc123"),
                "lark-doc 交付描述应为文档 URL 行，实际: " + dispatch.instruction());
        assertFalse(dispatch.instruction().contains("Spec："), "lark-doc 不应有 Spec/Tickets 行");
        assertToast(response, CardActionResponse.ToastType.SUCCESS, "产品方案已确认。");
    }

    @Test
    void approveBotRuntime缺失返回当前Bot尚未就绪() throws IOException {
        Fixture f = fixture();
        f.runtime().botRuntimes().remove("bot-1");
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(),
                new Collaboration.CollaborationOrigin("task-1", "bot-1", "ceo-bot", 1, 3));

        CardActionResponse response = handle(f, action("ou_owner", approveValue(flow.token())));

        assertToast(response, CardActionResponse.ToastType.ERROR, "当前 Bot 尚未就绪。");
        assertNull(response.card());
        assertEquals(0, f.collaboration().calls.size());
        assertEquals(ProductSpec.SpecStatus.APPROVED, flow.status(), "确认在派发检查之前已生效");
    }

    @Test
    void approve派发失败catch文案逐字() throws IOException {
        Fixture f = fixture();
        ProductSpec.ProductSpecFlow flow = specFlow(f, f.session().id(),
                new Collaboration.CollaborationOrigin("task-1", "bot-1", "ceo-bot", 1, 3));
        f.collaboration().failure = new RuntimeException("飞书超时");

        CardActionResponse response = handle(f, action("ou_owner", approveValue(flow.token())));

        assertToast(response, CardActionResponse.ToastType.ERROR, "确认结果交回失败：飞书超时");
        assertNull(response.card());
    }

    // ---------- answer_clarification ----------

    @Test
    void answerFlow守卫三路返回这组澄清问题已经失效() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());

        // 流程不存在
        CardActionResponse missing = handle(f, action("ou_owner", answerValue("no-token", "q1")));
        assertToast(missing, CardActionResponse.ToastType.ERROR, "这组澄清问题已经失效。");
        assertNull(missing.card());

        // botId 不符
        Clarification.ClarificationFlow otherBotFlow = f.runtime().clarificationFlows().create(
                new Clarification.CreateFlowOptions("task-2", "bot-2", "s1", "ou_owner", null,
                        null, "om", null, false, clarificationFlow(f, f.session().id()).request()));
        CardActionResponse wrongBot = handle(f, action("ou_owner", answerValue(otherBotFlow.token(), "q1")));
        assertToast(wrongBot, CardActionResponse.ToastType.ERROR, "这组澄清问题已经失效。");

        // messageId 缺失
        CardActionResponse noMessage = handle(f,
                action("ou_owner", (String) null, answerValue(flow.token(), "q1")));
        assertToast(noMessage, CardActionResponse.ToastType.ERROR, "这组澄清问题已经失效。");
        assertEquals(0, flow.answers().size(), "守卫路径不应作答");
    }

    @Test
    void answer非发起人返回只有任务发起人可以回答() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());

        CardActionResponse response =
                handle(f, action("ou_other", answerValue(flow.token(), "q1")));

        assertToast(response, CardActionResponse.ToastType.WARNING, "只有任务发起人可以回答。");
        assertEquals(0, flow.answers().size());
    }

    @Test
    void answer问题不匹配返回问题已经更新文案() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());

        CardActionResponse response =
                handle(f, action("ou_owner", answerValue(flow.token(), "q9")));

        assertToast(response, CardActionResponse.ToastType.WARNING, "问题已经更新，请按当前卡片作答。");
        assertEquals(0, flow.answers().size());
    }

    @Test
    void answerDecisionModeCurrent仅采用当前题推荐() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("decisionMode", "current");

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.SUCCESS, "已记录，继续下一题。");
        // 下一题澄清卡（2/2）
        assertEquals("需求澄清（2/2）", response.card().get("config").get("summary").get("content").asText());
        assertEquals(1, flow.answers().size(), "current 模式只答当前题");
        assertEquals(Clarification.AnswerSource.AGENT, flow.answers().get(0).source());
        assertEquals("方案A", flow.answers().get(0).answer(), "应取推荐项 label");
        assertEquals(1, flow.currentIndex());
    }

    @Test
    void answerDecisionModeRemaining答完全部题进complete路径() throws Exception {
        Fixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.IDLE);
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        CountDownLatch started = new CountDownLatch(1);
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("decisionMode", "remaining");

        CardActionResponse response = handle(f, action("ou_owner", value), continuation ->
                started.countDown());

        assertTrue(started.await(5, TimeUnit.SECONDS), "续跑应被启动");
        assertToast(response, CardActionResponse.ToastType.SUCCESS, "答案已收到。");
        // 全部题采用推荐（q2 无推荐 → 首个选项）
        assertEquals(List.of("方案A", "P0"),
                flow.answers().stream().map(Clarification.ClarificationAnswer::answer).toList());
        assertEquals(Clarification.AnswerSource.AGENT, flow.answers().get(1).source());
        assertEquals(SessionStatus.ACTIVE, f.sessions().get(f.session().id()).status());
        assertNull(f.runtime().clarificationFlows().get(flow.token()));
    }

    @Test
    void answerCustom空答案提示请先输入你的答案() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("custom", true);
        // formValue.custom_answer 为纯空白 → trim 后为空
        ObjectNode form = MAPPER.createObjectNode();
        form.put("custom_answer", "   ");

        CardActionResponse response = handle(f, action("ou_owner", value, form));

        assertToast(response, CardActionResponse.ToastType.WARNING, "请先输入你的答案。");
        assertEquals(0, flow.answers().size());

        // formValue 缺失同样命中（TS typeof !== 'string' → ''）
        CardActionResponse noForm = handle(f, action("ou_owner", value));
        assertToast(noForm, CardActionResponse.ToastType.WARNING, "请先输入你的答案。");
    }

    @Test
    void answer未知选项提示这个选项已经失效() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("optionId", "zzz");

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.WARNING, "这个选项已经失效。");
        assertEquals(0, flow.answers().size());
    }

    @Test
    void answer未保存提示答案没有保存请重试() throws IOException {
        Fixture f = fixture();
        // 零选项问题：answerWithRecommendation 找不到推荐项返回 null（真实可达的未保存路径）
        Clarification.ClarificationRequest request = new Clarification.ClarificationRequest(
                "需求澄清", "", List.of(new Clarification.Question("q1", "范围？", List.of(), null)));
        Clarification.ClarificationFlow flow = f.runtime().clarificationFlows().create(
                new Clarification.CreateFlowOptions("task-1", "bot-1", f.session().id(),
                        "ou_owner", null, null, "om_original", null, false, request));
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("decisionMode", "current");

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.WARNING, "答案没有保存，请重试。");
    }

    @Test
    void answer选项作答逐题推进toast与卡() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("optionId", "a");

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.SUCCESS, "已记录，继续下一题。");
        assertEquals("需求澄清（2/2）", response.card().get("config").get("summary").get("content").asText());
        assertEquals(1, flow.answers().size());
        assertEquals("方案A", flow.answers().get(0).answer(), "应取选项 label 而非 optionId");
        assertEquals(Clarification.AnswerSource.USER, flow.answers().get(0).source());
    }

    @Test
    void answerCustom答案trim后作答() throws IOException {
        Fixture f = fixture();
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        ObjectNode value = answerValue(flow.token(), "q1");
        value.put("custom", true);
        ObjectNode form = MAPPER.createObjectNode();
        form.put("custom_answer", "  自定义需求\u3000");

        CardActionResponse response = handle(f, action("ou_owner", value, form));

        assertToast(response, CardActionResponse.ToastType.SUCCESS, "已记录，继续下一题。");
        assertEquals("自定义需求", flow.answers().get(0).answer(), "JS trim 应剥全角空格等 Unicode 空白");
    }

    @Test
    void answerComplete路径内存态先于返回变更且续跑被调() throws Exception {
        Fixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.IDLE);
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        f.runtime().clarificationFlows().answer(flow.token(), "q1", "方案A");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ClarificationRunner.ClarificationContinuation> captured =
                new AtomicReference<>();
        ObjectNode value = answerValue(flow.token(), "q2");
        value.put("optionId", "p");

        CardActionResponse response = handle(f, action("ou_owner", value), continuation -> {
            captured.set(continuation);
            started.countDown();
        });

        assertTrue(started.await(5, TimeUnit.SECONDS), "续跑启动器应被调（裁决 4）");
        assertToast(response, CardActionResponse.ToastType.SUCCESS, "答案已收到。");
        assertEquals("需求澄清 · 正在整理",
                response.card().get("header").get("title").get("content").asText());
        // 内存态变更先于返回：会话已激活、activeRun 已注册、flow 已删除
        assertEquals(SessionStatus.ACTIVE, f.sessions().get(f.session().id()).status());
        ActiveRun activeRun = f.runtime().activeRuns().get(f.session().id());
        assertNotNull(activeRun, "activeRun 应已注册");
        assertEquals("ou_owner", activeRun.ownerOpenId());
        assertFalse(activeRun.token().isCancelled());
        assertNull(f.runtime().clarificationFlows().get(flow.token()), "flow 应已删除");
        // 续跑参数逐项
        assertSame(f.runtime(), captured.get().runtime());
        assertSame(f.bot(), captured.get().bot());
        assertSame(f.config(), captured.get().config());
        assertSame(flow, captured.get().flow(), "续跑携带答完后的同一 flow 对象");
        assertSame(activeRun.token(), captured.get().run());
        assertEquals(ProductDeliveryMode.LARK_DOC, captured.get().defaultDeliveryMode());
    }

    @Test
    void answerComplete默认启动器续跑异常被吞并记stderr() throws Exception {
        Fixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.IDLE);
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        f.runtime().clarificationFlows().answer(flow.token(), "q1", "方案A");
        f.bot().replyCardId = null; // 续跑发进度卡失败 → 抛逐字文案
        ObjectNode value = answerValue(flow.token(), "q2");
        value.put("optionId", "p");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            // 生产默认启动器：监听器正常返回，续跑异常只记 stderr 不外抛
            CardActionResponse response = handle(f, action("ou_owner", value));
            assertToast(response, CardActionResponse.ToastType.SUCCESS, "答案已收到。");
            String text = "";
            Instant deadline = Instant.now().plusSeconds(5);
            while (!(text = buffer.toString(StandardCharsets.UTF_8))
                    .contains("飞书没有返回需求整理进度卡片的 message_id")
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            assertTrue(text.contains("[澄清] 继续执行失败: 飞书没有返回需求整理进度卡片的 message_id"),
                    "虚拟线程续跑异常应被吞并记录，实际 stderr: " + text);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    void answerComplete会话缺失或已关闭返回对应的CLI会话已经失效() throws IOException {
        // 会话缺失
        Fixture f1 = fixture();
        Clarification.ClarificationFlow missing = clarificationFlow(f1, "missing-session");
        f1.runtime().clarificationFlows().answer(missing.token(), "q1", "方案A");
        ObjectNode value = answerValue(missing.token(), "q2");
        value.put("optionId", "p");
        CardActionResponse noSession = handle(f1, action("ou_owner", value));
        assertToast(noSession, CardActionResponse.ToastType.ERROR, "对应的 CLI 会话已经失效。");
        assertNotNull(f1.runtime().clarificationFlows().get(missing.token()), "flow 不应删除");

        // 会话已关闭
        Fixture f2 = fixture();
        f2.sessions().transition(f2.session().id(), SessionStatus.IDLE);
        f2.sessions().transition(f2.session().id(), SessionStatus.CLOSED);
        Clarification.ClarificationFlow flow = clarificationFlow(f2, f2.session().id());
        f2.runtime().clarificationFlows().answer(flow.token(), "q1", "方案A");
        ObjectNode closedValue = answerValue(flow.token(), "q2");
        closedValue.put("optionId", "p");
        CardActionResponse closed = handle(f2, action("ou_owner", closedValue));
        assertToast(closed, CardActionResponse.ToastType.ERROR, "对应的 CLI 会话已经失效。");
        assertEquals(0, f2.runtime().activeRuns().size());
    }

    @Test
    void answerComplete会话执行中返回当前会话仍在执行() throws IOException {
        Fixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.IDLE);
        f.sessions().transition(f.session().id(), SessionStatus.ACTIVE);
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        f.runtime().clarificationFlows().answer(flow.token(), "q1", "方案A");
        ObjectNode value = answerValue(flow.token(), "q2");
        value.put("optionId", "p");

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.WARNING, "当前会话仍在执行，请稍后重试。");
        assertNotNull(f.runtime().clarificationFlows().get(flow.token()), "flow 不应删除");
    }

    @Test
    void answerCompleteBotRuntime缺失返回会话失效文案() throws IOException {
        Fixture f = fixture();
        f.sessions().transition(f.session().id(), SessionStatus.IDLE);
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        f.runtime().clarificationFlows().answer(flow.token(), "q1", "方案A");
        f.runtime().botRuntimes().remove("bot-1");
        ObjectNode value = answerValue(flow.token(), "q2");
        value.put("optionId", "p");

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.ERROR, "对应的 CLI 会话已经失效。");
    }

    @Test
    void answerCompleteTransition失败toast错误且内存态回滚() throws Exception {
        // resolve（save 1）成功，handler 的 transition（save 2）失败
        SessionManager sessions = new SessionManager(IsoTime::now,
                () -> UUID.randomUUID().toString(), new FailingSecondSaveStore());
        Fixture f = fixture("D:/ws-topic", sessions);
        Clarification.ClarificationFlow flow = clarificationFlow(f, f.session().id());
        f.runtime().clarificationFlows().answer(flow.token(), "q1", "方案A");
        CountDownLatch started = new CountDownLatch(1);
        ObjectNode value = answerValue(flow.token(), "q2");
        value.put("optionId", "p");

        CardActionResponse response = handle(f, action("ou_owner", value),
                continuation -> started.countDown());

        assertToast(response, CardActionResponse.ToastType.ERROR, "存储不可用");
        assertFalse(started.await(50, TimeUnit.MILLISECONDS), "transition 失败不应启动续跑");
        assertEquals(SessionStatus.CREATING, f.sessions().get(f.session().id()).status(),
                "transition 失败应回滚，会话保持原状态");
        assertEquals(0, f.runtime().activeRuns().size(), "不应注册 activeRun");
        assertNotNull(f.runtime().clarificationFlows().get(flow.token()), "flow 不应删除");
    }

    // ---------- resume_cli_session ----------

    /** 把 user.home 指向临时目录（还原逻辑见 finally）；env CLAUDE_CONFIG_DIR 不可注入，缺席才可测。 */
    private static void withFakeHome(Path tempHome, Runnable action) {
        Assumptions.assumeTrue(
                System.getenv("CLAUDE_CONFIG_DIR") == null
                        || System.getenv("CLAUDE_CONFIG_DIR").isEmpty(),
                "CLAUDE_CONFIG_DIR 已设置，无法注入 user.home 替身");
        String original = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            action.run();
        } finally {
            System.setProperty("user.home", original);
        }
    }

    /** 镜像 NativeSessions.claudeProjectDirectory 的目录键规则（cwd 中非字母数字 → '-'）。 */
    private static Path claudeProjectDir(Path home, String cwd) {
        return home.resolve(".claude").resolve("projects")
                .resolve(cwd.replaceAll("[^A-Za-z0-9]", "-"));
    }

    @Test
    void resume守卫三连文案逐字() throws IOException {
        // 会话不存在
        Fixture f1 = fixture();
        ObjectNode missing = value("resume_cli_session");
        missing.put("agentSessionId", "no-such");
        missing.put("cliSessionId", "cli-1");
        CardActionResponse noSession = handle(f1, action("ou_owner", missing));
        assertToast(noSession, CardActionResponse.ToastType.ERROR, "这条会话记录已经失效。");

        // botId 不符
        Fixture f2 = fixture();
        Session other = f2.sessions().resolve(
                new MessageAddress("om_2", "oc_2", "ot_2", "ot_2"), CliId.CLAUDE,
                "other-bot", "D:/ws").session();
        ObjectNode wrongBot = value("resume_cli_session");
        wrongBot.put("agentSessionId", other.id());
        wrongBot.put("cliSessionId", "cli-1");
        assertToast(handle(f2, action("ou_owner", wrongBot)),
                CardActionResponse.ToastType.ERROR, "这条会话记录已经失效。");

        // cliSessionId 缺失
        Fixture f3 = fixture();
        ObjectNode noCli = value("resume_cli_session");
        noCli.put("agentSessionId", f3.session().id());
        noCli.put("cliSessionId", "");
        assertToast(handle(f3, action("ou_owner", noCli)),
                CardActionResponse.ToastType.ERROR, "这条会话记录已经失效。");
    }

    @Test
    void resumeActive与closed守卫文案逐字() throws IOException {
        Fixture f1 = fixture();
        f1.sessions().transition(f1.session().id(), SessionStatus.IDLE);
        f1.sessions().transition(f1.session().id(), SessionStatus.ACTIVE);
        ObjectNode active = value("resume_cli_session");
        active.put("agentSessionId", f1.session().id());
        active.put("cliSessionId", "cli-1");
        assertToast(handle(f1, action("ou_owner", active)),
                CardActionResponse.ToastType.WARNING, "当前任务结束后才能切换会话。");

        Fixture f2 = fixture();
        f2.sessions().transition(f2.session().id(), SessionStatus.IDLE);
        f2.sessions().transition(f2.session().id(), SessionStatus.CLOSED);
        ObjectNode closed = value("resume_cli_session");
        closed.put("agentSessionId", f2.session().id());
        closed.put("cliSessionId", "cli-1");
        assertToast(handle(f2, action("ou_owner", closed)),
                CardActionResponse.ToastType.WARNING, "当前话题的会话已经关闭。");
    }

    @Test
    void resume成功切换CLI会话并重建恢复卡(@TempDir Path tempHome) throws Exception {
        Fixture f = fixture(tempHome.resolve("ws-a").toString());
        withFakeHome(tempHome, () -> {
            try {
                Path projectDir = claudeProjectDir(tempHome, f.session().workspaceDir());
                Files.createDirectories(projectDir);
                // 经 Jackson 序列化：Windows 路径的反斜杠需要 JSON 转义，手工拼接会产出非法 JSON
                String line = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                        .put("sessionId", "native-1")
                        .put("cwd", f.session().workspaceDir())
                        .put("type", "last-prompt")
                        .put("lastPrompt", "整理会议纪要"));
                Files.writeString(projectDir.resolve("native-1.jsonl"), line + "\n",
                        StandardCharsets.UTF_8);
                ObjectNode value = value("resume_cli_session");
                value.put("agentSessionId", f.session().id());
                value.put("cliSessionId", "native-1");

                CardActionResponse response = handle(f, action("ou_owner", value));

                assertToast(response, CardActionResponse.ToastType.SUCCESS, "已切换到选中的历史会话。");
                assertEquals("恢复历史会话", response.card().get("header").get("title").get("content").asText());
                assertEquals("Claude Code", response.card().get("header").get("subtitle").get("content").asText());
                assertEquals("native-1", f.sessions().get(f.session().id()).cliSessionId(),
                        "CLI 会话 ID 应已切换");
                // 重建卡里 native-1 应标记为当前会话
                assertTrue(response.card().toString().contains("当前会话"),
                        "重建卡应把切换后的会话标为当前会话");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void resume会话不在当前工作目录文案逐字(@TempDir Path tempHome) throws Exception {
        Fixture f = fixture(tempHome.resolve("ws-b").toString());
        withFakeHome(tempHome, () -> {
            ObjectNode value = value("resume_cli_session");
            value.put("agentSessionId", f.session().id());
            value.put("cliSessionId", "native-x");

            CardActionResponse response = handle(f, action("ou_owner", value));

            assertToast(response, CardActionResponse.ToastType.ERROR, "这个 CLI 会话已经不在当前工作目录中。");
            assertNull(f.sessions().get(f.session().id()).cliSessionId(), "不应切换");
        });
    }

    @Test
    void resume读取失败catch文案(@TempDir Path tempHome) throws Exception {
        Fixture f = fixture(tempHome.resolve("ws-c").toString());
        withFakeHome(tempHome, () -> {
            try {
                // 项目目录位置建成普通文件：Files.list 抛 NotDirectoryException（IOException 子类）
                Path projectDir = claudeProjectDir(tempHome, f.session().workspaceDir());
                Files.createDirectories(projectDir.getParent());
                Files.writeString(projectDir, "not a directory", StandardCharsets.UTF_8);
                ObjectNode value = value("resume_cli_session");
                value.put("agentSessionId", f.session().id());
                value.put("cliSessionId", "native-1");

                CardActionResponse response = handle(f, action("ou_owner", value));

                assertNotNull(response);
                assertNotNull(response.toast());
                assertEquals(CardActionResponse.ToastType.ERROR, response.toast().type(),
                        "读取失败应走 catch 的 error toast");
                assertNotNull(response.toast().content(), "错误消息应为 (error as Error).message");
                assertNotSame("", response.toast().content());
                assertNull(response.card());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ---------- abort_task ----------

    @Test
    void abortNotFound返回任务已经结束() throws IOException {
        Fixture f = fixture();
        ObjectNode value = value("abort_task");
        value.put("sessionId", f.session().id());

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.INFO, "任务已经结束，无需再次停止。");
    }

    @Test
    void abortForbidden返回无法识别操作者() throws IOException {
        Fixture f = fixture();
        f.runtime().activeRuns().put(f.session().id(),
                new ActiveRun(new CancellationToken(), "ou_owner"));
        ObjectNode value = value("abort_task");
        value.put("sessionId", f.session().id());

        CardActionResponse response = handle(f, action(null, value));

        assertToast(response, CardActionResponse.ToastType.WARNING, "无法识别操作者，无法停止任务。");
    }

    @Test
    void abortAlreadyStopping返回正在停止() throws IOException {
        Fixture f = fixture();
        ActiveRun active = new ActiveRun(new CancellationToken(), "ou_owner");
        active.token().cancel();
        f.runtime().activeRuns().put(f.session().id(), active);
        ObjectNode value = value("abort_task");
        value.put("sessionId", f.session().id());

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.INFO, "正在停止任务，请稍候。");
    }

    @Test
    void abortStopped返回已发送停止指令且令牌取消() throws IOException {
        Fixture f = fixture();
        ActiveRun active = new ActiveRun(new CancellationToken(), "ou_owner");
        f.runtime().activeRuns().put(f.session().id(), active);
        ObjectNode value = value("abort_task");
        value.put("sessionId", f.session().id());

        CardActionResponse response = handle(f, action("ou_owner", value));

        assertToast(response, CardActionResponse.ToastType.SUCCESS, "已发送停止指令。");
        assertTrue(active.token().isCancelled());
    }

    // ---------- 未知 action ----------

    @Test
    void 未知action返回null() throws IOException {
        Fixture f = fixture();
        assertNull(handle(f, action("ou_owner", value("whatever"))));
    }
}

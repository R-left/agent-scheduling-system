package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliCompactPlan;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.CliPromptInput;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M7 Task 4：DocumentCommentScheduler 测试（brief Step 4 全部用例）。
 * 对齐 TS 版 src/index.ts L779-866：同 sessionId 串行（顺序 latch 断言）、不同 sessionId
 * 并行、eventKey 防重（同 eventId 第二次静默）、mentionedBot false 忽略、无 pending flow
 * 忽略文案逐字、workingReaction 失败不阻塞且 finally 不移除表情、处理异常走回写文案、
 * 回写再失败兜底 log、队列误清防护（前序 finally 不删新任务队列头）、超 1000 FIFO 淘汰。
 * 执行体（runProductDocumentComment 静态方法）经 CommentRunner 函数字段注入 stub（绝不拉
 * 真实 CLI）；Bot 为记录型 fake；日志断言走 LogbackCapture（Task 0 先例）。
 */
class DocumentCommentSchedulerTest {

    // ---------- 替身 ----------

    /** 记录型 fake Bot：记录表情调用序与评论回写，可注入失败。 */
    private static final class RecordingBot implements Bot {
        record Reaction(String commentId, boolean active) {}

        final List<Reaction> reactions = Collections.synchronizedList(new ArrayList<>());
        /** 仅「添加（true）」方向抛错——workingReaction 失败用例；移除方向不受影响。 */
        volatile RuntimeException addWorkingError;
        final List<String> replyTexts = Collections.synchronizedList(new ArrayList<>());
        volatile RuntimeException replyError;

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
            RuntimeException error = replyError;
            if (error != null) {
                throw error;
            }
            replyTexts.add(text);
        }

        @Override
        public void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active) {
            if (active) {
                RuntimeException error = addWorkingError;
                if (error != null) {
                    throw error;
                }
            }
            reactions.add(new Reaction(comment.commentId(), active));
        }

        @Override
        public String downloadResource(String messageId, String fileKey, String type,
                String saveDir, String fileName) {
            return null;
        }
    }

    /** 执行体 stub：记录启动序，可按 commentId 挂 latch 阻塞或注入失败（绝不拉真实 CLI）。 */
    private static final class StubRunner implements DocumentCommentScheduler.CommentRunner {
        final List<String> started = Collections.synchronizedList(new ArrayList<>());
        final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();

        @Override
        public void run(ProductCommentRunner.ProductDocumentComment options)
                throws IOException, InterruptedException {
            String commentId = options.comment().commentId();
            started.add(commentId);
            CountDownLatch gate = gates.get(commentId);
            if (gate != null) {
                gate.await();
            }
            RuntimeException failure = failures.get(commentId);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record Fixture(BotRegistry.BotConfig config, AppRuntime runtime,
            DocumentCommentScheduler scheduler, RecordingBot bot, StubRunner runner) {}

    /** 轮询等待异步条件（虚拟线程编排无同步完成点，统一轮询 + 超时兜底）。 */
    private static void await(String what, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("等待超时: " + what);
    }

    private Fixture fixture() {
        BotRegistry.BotConfig config = new BotRegistry.BotConfig("bot-1", "app-1", "secret",
                CliId.CLAUDE, "产品经理", List.of("lark-drive"), null, "ws", 3);
        AppRuntime runtime =
                new AppRuntime(new SessionManager(), new TeamRegistry("bot-1", List.of(config)));
        RecordingBot bot = new RecordingBot();
        StubRunner runner = new StubRunner();
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        DocumentCommentScheduler scheduler = new DocumentCommentScheduler(runtime, executor,
                runner);
        return new Fixture(config, runtime, scheduler, bot, runner);
    }

    /** 建立 fileToken → PENDING 流程映射（URL /docx/<token> 与 DOC_TOKEN 匹配）。 */
    private void larkFlow(Fixture f, String fileToken, String sessionId) throws IOException {
        f.runtime().productSpecFlows().create(new ProductSpec.CreateFlowOptions(
                "task-" + fileToken, "bot-1", sessionId, "ou_owner", null, null,
                new ProductSpec.ProductSpecRequest.Lark("lark-doc", "产品方案", "概述",
                        "https://example.feishu.cn/docx/" + fileToken)));
    }

    private static IncomingDocumentComment comment(String fileToken, String commentId,
            String eventId) {
        return new IncomingDocumentComment(eventId, fileToken, "docx", commentId, null,
                "ou_commenter", null, true);
    }

    private static void release(StubRunner runner, String commentId) {
        runner.gates.get(commentId).countDown();
    }

    // ---------- 串行性：同 sessionId 两评论顺序执行 ----------

    @Test
    void 同session两评论串行_顺序执行且表情先加后移() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        larkFlow(f, "tokB", "sess-1");
        f.runner().gates.put("cmtA", new CountDownLatch(1));
        f.runner().gates.put("cmtB", new CountDownLatch(1));

        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-A"));
        await("cmtA 启动且表情已加", () -> f.runner().started.equals(List.of("cmtA"))
                && f.bot().reactions.equals(List.of(new RecordingBot.Reaction("cmtA", true))));

        // cmtA 阻塞期间排入 cmtB：执行体不得启动（串行），表情可先行添加（TS 同款）
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokB", "cmtB", "ev-B"));
        await("cmtB 表情已加", () -> f.bot().reactions.size() >= 2);
        assertEquals(List.of("cmtA"), f.runner().started, "cmtA 未完成前 cmtB 不得启动");

        release(f.runner(), "cmtA");
        await("cmtB 启动", () -> f.runner().started.equals(List.of("cmtA", "cmtB")));
        release(f.runner(), "cmtB");
        await("两任务全部收尾（队列头清空）", () -> f.scheduler().queueHead("sess-1") == null);

        // 表情序：A 加 → B 加（B 排队时先行添加）→ A 移 → B 移（A 的 finally 先于 B 执行体）
        assertEquals(List.of(
                new RecordingBot.Reaction("cmtA", true),
                new RecordingBot.Reaction("cmtB", true),
                new RecordingBot.Reaction("cmtA", false),
                new RecordingBot.Reaction("cmtB", false)), f.bot().reactions);
    }

    // ---------- 不同 sessionId 并行 ----------

    @Test
    void 不同session两评论并行执行() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        larkFlow(f, "tokB", "sess-2");
        f.runner().gates.put("cmtA", new CountDownLatch(1));
        f.runner().gates.put("cmtB", new CountDownLatch(1));

        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-A"));
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokB", "cmtB", "ev-B"));

        // 两执行体同时阻塞中 = 并行（串行队列按 sessionId 隔离）
        await("两执行体均启动", () -> f.runner().started.size() == 2);

        release(f.runner(), "cmtA");
        release(f.runner(), "cmtB");
        await("两队列头均清空", () -> f.scheduler().queueHead("sess-1") == null
                && f.scheduler().queueHead("sess-2") == null);
        assertTrue(f.runner().started.containsAll(List.of("cmtA", "cmtB")));
    }

    // ---------- eventKey 防重：同 eventId 第二次静默 ----------

    @Test
    void 同eventId第二次静默忽略() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.runner().gates.put("cmtA", new CountDownLatch(1));

        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-1"));
        await("cmtA 启动", () -> f.runner().started.equals(List.of("cmtA")));

        // 第二次同 eventId：静默 return——无日志、无表情、无执行体
        LogbackCapture capture = LogbackCapture.attach(DocumentCommentScheduler.class);
        try {
            f.scheduler().scheduleDocumentComment(f.config(), f.bot(),
                    comment("tokA", "cmtA", "ev-1"));
        } finally {
            capture.detach();
        }
        assertTrue(capture.messages().isEmpty(), "重复事件应静默，实际日志: " + capture.messages());
        assertEquals(List.of(new RecordingBot.Reaction("cmtA", true)), f.bot().reactions,
                "第二次不得再加表情");
        assertEquals(List.of("cmtA"), f.runner().started, "第二次不得再进执行体");

        release(f.runner(), "cmtA");
        await("收尾完成", () -> f.scheduler().queueHead("sess-1") == null);
    }

    /** eventKey 回落分支：eventId 缺失（null/空串）按 [fileToken, commentId, replyId].join(':') 防重。 */
    @Test
    void eventId缺失时按三元组回落防重() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.runner().gates.put("cmtA", new CountDownLatch(1));

        // eventId null 与空串都走 fallback（TS || 的 falsy 语义），同三元组第二次静默
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", null));
        await("cmtA 启动", () -> f.runner().started.equals(List.of("cmtA")));
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", ""));

        assertEquals(List.of("cmtA"), f.runner().started, "同三元组第二次不得进执行体");
        assertEquals(List.of(new RecordingBot.Reaction("cmtA", true)), f.bot().reactions);

        release(f.runner(), "cmtA");
        await("收尾完成", () -> f.scheduler().queueHead("sess-1") == null);
    }

    // ---------- mentionedBot false 忽略 ----------

    @Test
    void 未提及bot的评论静默忽略() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        LogbackCapture capture = LogbackCapture.attach(DocumentCommentScheduler.class);
        try {
            f.scheduler().scheduleDocumentComment(f.config(), f.bot(),
                    new IncomingDocumentComment("ev-1", "tokA", "docx", "cmtA", null,
                            "ou_commenter", null, false));
        } finally {
            capture.detach();
        }
        assertTrue(capture.messages().isEmpty(), "不应有日志，实际: " + capture.messages());
        assertTrue(f.bot().reactions.isEmpty());
        assertTrue(f.runner().started.isEmpty());
        assertNull(f.scheduler().queueHead("sess-1"));
    }

    // ---------- 无 pending flow：忽略文案逐字 ----------

    @Test
    void 未关联待确认方案时忽略并逐字日志() throws Exception {
        Fixture f = fixture();
        LogbackCapture capture = LogbackCapture.attach(DocumentCommentScheduler.class);
        try {
            f.scheduler().scheduleDocumentComment(f.config(), f.bot(),
                    comment("tok-missing", "cmtA", "ev-1"));
        } finally {
            capture.detach();
        }
        assertEquals(List.of("[产品评论] 忽略未关联待确认方案的评论 file=tok-missing"),
                capture.messages());
        assertTrue(f.bot().reactions.isEmpty());
        assertTrue(f.runner().started.isEmpty());
    }

    // ---------- workingReaction 失败不阻塞且 finally 不移除表情 ----------

    @Test
    void 添加表情失败时警告不阻塞且不移除表情() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.bot().addWorkingError = new RuntimeException("no permission");
        f.runner().gates.put("cmtA", new CountDownLatch(1));

        LogbackCapture capture = LogbackCapture.attach(DocumentCommentScheduler.class);
        try {
            f.scheduler().scheduleDocumentComment(f.config(), f.bot(),
                    comment("tokA", "cmtA", "ev-A"));
            await("cmtA 启动（表情失败不阻塞执行体）",
                    () -> f.runner().started.equals(List.of("cmtA")));
            release(f.runner(), "cmtA");
            await("收尾完成", () -> f.scheduler().queueHead("sess-1") == null);
        } finally {
            capture.detach();
        }

        // 文案逐字：TS console.warn('[产品评论] 添加处理中表情失败，继续执行:', message)
        assertTrue(capture.messages()
                .contains("[产品评论] 添加处理中表情失败，继续执行: no permission"));
        // reactionAdded=false：finally 不得移除表情（无 false 方向调用）
        assertFalse(f.bot().reactions.contains(new RecordingBot.Reaction("cmtA", false)),
                "表情添加失败时 finally 不应移除表情");
        assertTrue(f.runner().started.equals(List.of("cmtA")));
    }

    // ---------- 处理异常 → 回写文案 + 处理失败日志 ----------

    @Test
    void 执行体异常时回写逐字文案并清队列() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.runner().failures.put("cmtA", new RuntimeException("CLI 崩了"));

        LogbackCapture capture = LogbackCapture.attach(DocumentCommentScheduler.class);
        try {
            f.scheduler().scheduleDocumentComment(f.config(), f.bot(),
                    comment("tokA", "cmtA", "ev-A"));
            await("回写评论", () -> !f.bot().replyTexts.isEmpty());
        } finally {
            capture.detach();
        }

        assertEquals(List.of("这条评论暂时没有处理完成：CLI 崩了"), f.bot().replyTexts,
                "回写文案逐字（message 保留原始异常消息）");
        assertTrue(capture.messages().contains("[产品评论] 处理失败: CLI 崩了"));
        assertNull(f.scheduler().queueHead("sess-1"), "finally 条件删除应清空队列头");
        // 表表情已添加 → finally 移除
        await("表情移除", () -> f.bot().reactions.contains(new RecordingBot.Reaction("cmtA", false)));
    }

    // ---------- 回写再失败 → 兜底 log，不外抛 ----------

    @Test
    void 回写失败时兜底日志且不外抛() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.runner().failures.put("cmtA", new RuntimeException("CLI 崩了"));
        f.bot().replyError = new RuntimeException("send failed");

        LogbackCapture capture = LogbackCapture.attach(DocumentCommentScheduler.class);
        try {
            f.scheduler().scheduleDocumentComment(f.config(), f.bot(),
                    comment("tokA", "cmtA", "ev-A"));
            await("兜底日志出现", () -> capture.messages()
                    .contains("[产品评论] 回写失败: send failed"));
        } finally {
            capture.detach();
        }

        assertTrue(capture.messages().contains("[产品评论] 处理失败: CLI 崩了"));
        assertTrue(capture.messages().contains("[产品评论] 回写失败: send failed"));
        assertNull(f.scheduler().queueHead("sess-1"), "回写失败后 finally 仍应清理队列头");
        assertTrue(f.bot().replyTexts.isEmpty());
    }

    // ---------- 队列误清防护：前序 finally 不删新任务队列头 ----------

    @Test
    void 前序完成时不误删新入队的队列头() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        larkFlow(f, "tokB", "sess-1");
        larkFlow(f, "tokC", "sess-1");
        f.runner().gates.put("cmtA", new CountDownLatch(1));
        f.runner().gates.put("cmtB", new CountDownLatch(1));

        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-A"));
        await("cmtA 启动", () -> f.runner().started.equals(List.of("cmtA")));

        // cmtA 运行中入队 cmtB：队列头 = cmtB 的 queued
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokB", "cmtB", "ev-B"));
        await("cmtB 入队", () -> f.scheduler().queueHead("sess-1") != null);

        // cmtA 完成：其 finally 条件删除——map 头已是 cmtB 的 queued，引用不等不得删
        release(f.runner(), "cmtA");
        await("cmtB 启动", () -> f.runner().started.equals(List.of("cmtA", "cmtB")));
        assertTrue(f.scheduler().queueHead("sess-1") != null,
                "cmtA 完成后不得误删 cmtB 的队列头");

        // 再入队 cmtC（前缀链在 cmtB 上）：若 cmtA 误删了头，cmtC 的 previous 为 null
        // → 会与阻塞中的 cmtB 并发启动——负向断言窗口内 cmtC 不得启动
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokC", "cmtC", "ev-C"));
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            assertFalse(f.runner().started.contains("cmtC"),
                    "cmtC 不得在 cmtB 完成前启动（串行性被破坏 = 队列头被误删）");
            Thread.sleep(20);
        }

        release(f.runner(), "cmtB");
        await("cmtC 启动", () -> f.runner().started.equals(List.of("cmtA", "cmtB", "cmtC")));
        await("队列头清空", () -> f.scheduler().queueHead("sess-1") == null);
    }

    // ---------- 终审 Major-2：清理的 get/remove 非原子 → 两参原子条件删除 ----------

    /**
     * 终审 Major-2 确定性测试。原实现清理段是 get + 单参 remove 两步且不在
     * synchronized(this) 内：同 sessionId 新任务的 put 若落在 get 与 remove 之间，
     * 单参 remove 会无条件删掉新任务队列头，打穿裁决 10「条件 remove 防误清」。
     * 该窗口纳秒级、物理时序不可控，本测试用受控队列 Map 替身把它<b>确定性展开</b>：
     * 清理方 remove 到达的瞬间，替身先让「新任务队列头」抢先落位（模拟并发 put 恰在
     * 清理的读与删之间完成），再执行真删除——断言新任务队列头仍在、且从未走到无条件
     * 的单参 remove。两参原子条件删除才可能通过；旧的两步实现必删必红。
     */
    @Test
    void 清理窗口内新任务抢先入队_新队列头不被误清() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.runner().gates.put("cmtA", new CountDownLatch(1));

        RacingQueueMap controlledMap = new RacingQueueMap();
        Field queuesField = DocumentCommentScheduler.class
                .getDeclaredField("documentCommentQueues");
        queuesField.setAccessible(true);
        queuesField.set(f.scheduler(), controlledMap);

        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-A"));
        await("cmtA 启动", () -> f.runner().started.equals(List.of("cmtA")));

        CompletableFuture<Void> racingHead = new CompletableFuture<>();
        controlledMap.armed = true;
        controlledMap.racingPut = racingHead;
        release(f.runner(), "cmtA");
        await("清理已执行", () ->
                controlledMap.singleArgRemoveCalls.get() + controlledMap.conditionalRemoveCalls.get() >= 1);

        assertEquals(0, controlledMap.singleArgRemoveCalls.get(),
                "清理不得走无条件单参 remove（终审 Major-2 的缺陷形态）");
        assertEquals(1, controlledMap.conditionalRemoveCalls.get(),
                "清理应恰好一次两参原子条件删除");
        assertTrue(f.scheduler().queueHead("sess-1") == racingHead,
                "get/remove 间抢先入队的新任务队列头不得被误清");
    }

    /**
     * 受控队列 Map 替身：armed 后第一次 remove（清理方）先把「新任务队列头」put 进来
     * （模拟竞态窗口内新任务的 put 抢先完成），再执行真删除，并按参数形状分别计数。
     */
    private static final class RacingQueueMap
            extends ConcurrentHashMap<String, CompletableFuture<Void>> {
        volatile boolean armed;
        volatile CompletableFuture<Void> racingPut;
        final AtomicInteger singleArgRemoveCalls = new AtomicInteger();
        final AtomicInteger conditionalRemoveCalls = new AtomicInteger();

        private void injectRacingPutIfArmed(String key) {
            if (armed) {
                armed = false;
                super.put(key, racingPut);
            }
        }

        @Override
        public CompletableFuture<Void> remove(Object key) {
            singleArgRemoveCalls.incrementAndGet();
            injectRacingPutIfArmed((String) key);
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            conditionalRemoveCalls.incrementAndGet();
            injectRacingPutIfArmed((String) key);
            return super.remove(key, value);
        }
    }

    // ---------- 超 1000 FIFO 淘汰（rememberDocumentCommentEvent 直测） ----------

    @Test
    void 超过一千条按插入序淘汰最早一条() {
        Fixture f = fixture();
        for (int i = 0; i < DocumentCommentScheduler.MAX_REMEMBERED_DOCUMENT_COMMENT_EVENTS;
                i++) {
            f.scheduler().rememberDocumentCommentEvent("k-" + i);
        }
        assertTrue(f.scheduler().hasRememberedEvent("k-0"));
        f.scheduler().rememberDocumentCommentEvent("k-1000");
        assertFalse(f.scheduler().hasRememberedEvent("k-0"), "第 1001 条加入后应淘汰最早的 k-0");
        assertTrue(f.scheduler().hasRememberedEvent("k-1"));
        assertTrue(f.scheduler().hasRememberedEvent("k-1000"));
    }

    // ---------- 重复事件不重复入队（防重与队列互不干扰） ----------

    @Test
    void 重复事件在任务执行中到达不影响队列() throws Exception {
        Fixture f = fixture();
        larkFlow(f, "tokA", "sess-1");
        f.runner().gates.put("cmtA", new CountDownLatch(1));

        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-A"));
        await("cmtA 启动", () -> f.runner().started.equals(List.of("cmtA")));
        // 重复事件（同 eventId）到达：静默忽略，队列头保持原 queued
        f.scheduler().scheduleDocumentComment(f.config(), f.bot(), comment("tokA", "cmtA", "ev-A"));
        assertTrue(f.scheduler().queueHead("sess-1") != null, "重复事件不得清动队列");

        release(f.runner(), "cmtA");
        await("收尾完成", () -> f.scheduler().queueHead("sess-1") == null);
    }
}

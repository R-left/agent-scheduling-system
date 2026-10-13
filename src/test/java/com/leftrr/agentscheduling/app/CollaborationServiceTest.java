package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 4：CollaborationService.dispatch 测试。
 * 对齐 TS 版 src/app/collaboration-service.ts（95 行）：六步控制流逐分支钉住——
 * target/reportTo 缺失 throw 逐字、dispatchId 12 位十六进制、inbox.register 落账、
 * replyCard/replyMention 返回 null 时 throw 逐字且回收（consume 后为 null）、
 * round==1 与 round&gt;1 两版 mention 文案逐字、成功日志格式逐字。
 * fake Bot 记录调用并可注入 null 返回；inbox 用 core 真 CollaborationInbox；
 * AppRuntime 用 Task 1 两参构造（sessions + teamRegistry 必传）。
 */
class CollaborationServiceTest {

    private static final Pattern DISPATCH_ID_IN_TEXT = Pattern.compile("任务编号：([0-9a-f]{12})");

    private static BotRegistry.BotConfig config(String id) {
        // 末参 collaborationMaxRounds = 3：BotConfig 必填的协作最大轮数（对齐 TS 默认配置），
        // 与 dispatchOptions 里的 round/maxRounds 断言取值保持一致
        return new BotRegistry.BotConfig(id, "app-" + id, "secret", CliId.CLAUDE,
                "工程师", List.of(), null, "C:/ws/" + id, 3);
    }

    /**
     * 记录 replyCard / replyMention 调用参数的 fake Bot；两类返回值可注入为 null
     * 模拟飞书 message_id 缺失。beforeCard 钩子在 replyCard 调用瞬间执行，
     * 供回收断言捕捉「register 先于 replyCard 发生」的现场。
     */
    private static final class FakeBot implements Bot {
        String cardMessageId = "om-card-1";
        String mentionMessageId = "om-mention-1";
        Runnable beforeCard;
        // replyCard 现场记录
        String cardToMessageId;
        JsonNode card;
        boolean cardReplyInThread;
        int cardCalls;
        // replyMention 现场记录
        String mentionToMessageId;
        BotIdentity mentionTarget;
        String mentionText;
        boolean mentionReplyInThread;
        int mentionCalls;

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
            cardCalls++;
            this.cardToMessageId = messageId;
            this.card = card;
            this.cardReplyInThread = replyInThread;
            if (beforeCard != null) {
                beforeCard.run();
            }
            return cardMessageId;
        }

        @Override
        public String replyMention(String messageId, BotIdentity target, String text,
                boolean replyInThread) {
            mentionCalls++;
            this.mentionToMessageId = messageId;
            this.mentionTarget = target;
            this.mentionText = text;
            this.mentionReplyInThread = replyInThread;
            return mentionMessageId;
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
                String saveDir, String fileName) throws IOException {
            return null;
        }
    }

    /** 装配好的运行时 + 三方 bot（leader 为发送方兼结果接收方，worker 为目标）。 */
    private record Fixture(AppRuntime runtime, FakeBot leaderBot, FakeBot workerBot,
            BotIdentity leaderIdentity, BotIdentity workerIdentity) {
    }

    private static Fixture fixture() {
        AppRuntime runtime = new AppRuntime(new SessionManager(),
                new TeamRegistry("leader", List.of(config("leader"), config("worker"))));
        FakeBot leaderBot = new FakeBot();
        FakeBot workerBot = new FakeBot();
        BotIdentity leaderIdentity = new BotIdentity("ou_leader", "队长");
        BotIdentity workerIdentity = new BotIdentity("ou_worker", "工人");
        runtime.botRuntimes().put("leader", new BotRuntime(config("leader"), leaderBot, leaderIdentity));
        runtime.botRuntimes().put("worker", new BotRuntime(config("worker"), workerBot, workerIdentity));
        return new Fixture(runtime, leaderBot, workerBot, leaderIdentity, workerIdentity);
    }

    private static CollaborationService.CollaborationDispatch dispatchOptions(
            BotRegistry.BotConfig senderConfig, Bot senderBot, int round) {
        return new CollaborationService.CollaborationDispatch(
                senderConfig, senderBot, "om_src_1", "worker", "task-001",
                "ou_owner", null, "leader",
                "整理会议纪要", "按时间线整理", "markdown 文档",
                round, 3, "C:/ws/leader");
    }

    /** 从 mention 文案提取 dispatchId（也是文案逐字断言的一部分）。 */
    private static String extractDispatchId(String mentionText) {
        Matcher matcher = DISPATCH_ID_IN_TEXT.matcher(mentionText);
        assertTrue(matcher.find(), "mention 文案应含任务编号：" + mentionText);
        return matcher.group(1);
    }

    /** 反射读取 inbox 内部 Map：核对落账与回收（core 类未暴露只读视图）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Collaboration.CollaborationMessage> inboxEntries(
            Collaboration.CollaborationInbox inbox) throws Exception {
        var field = Collaboration.CollaborationInbox.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (Map<String, Collaboration.CollaborationMessage>) field.get(inbox);
    }

    // ---------- target 缺失：throw 逐字，未落账未发卡 ----------

    @Test
    void target缺失时抛出逐字文案且不落账不发卡() throws Exception {
        Fixture f = fixture();
        f.runtime.botRuntimes().remove("worker");
        CollaborationService service = new CollaborationService(f.runtime);

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1)));

        assertEquals("协作 bot 尚未就绪: worker", e.getMessage());
        assertEquals(0, f.leaderBot.cardCalls, "target 缺失不应发卡");
        assertEquals(0, f.leaderBot.mentionCalls);
        assertTrue(inboxEntries(f.runtime.collaborationInbox()).isEmpty(), "不应落账");
    }

    // ---------- reportTo 缺失：throw 逐字；两者同缺先报 target ----------

    @Test
    void reportTo缺失时抛出逐字文案() throws Exception {
        Fixture f = fixture();
        f.runtime.botRuntimes().remove("leader");
        CollaborationService service = new CollaborationService(f.runtime);

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1)));

        assertEquals("结果接收 bot 尚未就绪: leader", e.getMessage());
        assertEquals(0, f.leaderBot.cardCalls);
        assertTrue(inboxEntries(f.runtime.collaborationInbox()).isEmpty());
    }

    @Test
    void target与reportTo同缺时先报target() {
        Fixture f = fixture();
        f.runtime.botRuntimes().clear();
        CollaborationService service = new CollaborationService(f.runtime);

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1)));

        assertEquals("协作 bot 尚未就绪: worker", e.getMessage());
    }

    // ---------- 成功路径：dispatchId 格式、落账、发卡与 mention 参数 ----------

    @Test
    void 成功路径落账发卡发mention且字段正确() throws Exception {
        Fixture f = fixture();
        CollaborationService service = new CollaborationService(f.runtime);

        service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1));

        // replyCard 参数：回复到源消息、卡片段正确、replyInThread=true
        assertEquals(1, f.leaderBot.cardCalls);
        assertEquals("om_src_1", f.leaderBot.cardToMessageId);
        assertTrue(f.leaderBot.cardReplyInThread);
        // 卡片装配：senderName 取 botRuntimes 里 sender 的 identity.name，subtitle = sender → target
        assertEquals("队长 → 工人",
                f.leaderBot.card.get("header").get("subtitle").get("content").asText());
        // replyMention 参数：回复到卡片消息、@ 目标 identity、replyInThread=true
        assertEquals(1, f.leaderBot.mentionCalls);
        assertEquals("om-card-1", f.leaderBot.mentionToMessageId);
        assertSame(f.workerIdentity, f.leaderBot.mentionTarget);
        assertTrue(f.leaderBot.mentionReplyInThread);

        // inbox 落账：dispatchId 从 mention 文案提取，consume 取回的消息字段逐一对上
        String dispatchId = extractDispatchId(f.leaderBot.mentionText);
        Collaboration.CollaborationMessage message =
                f.runtime.collaborationInbox().consume(dispatchId, "worker");
        assertEquals(new Collaboration.CollaborationMessage(
                        dispatchId, "task-001", "ou_owner", null, "leader", "worker", "leader",
                        "整理会议纪要", "按时间线整理", "markdown 文档", 1, 3, "C:/ws/leader"),
                message);
        // consume 取走后收件箱为空
        assertNull(f.runtime.collaborationInbox().consume(dispatchId, "worker"));
    }

    @Test
    void sender不在botRuntimes时senderName回退为configId() {
        Fixture f = fixture();
        // guest 未注册进 botRuntimes：TS ?.identity.name ?? senderConfig.id 走回退分支
        CollaborationService service = new CollaborationService(f.runtime);

        service.dispatch(new CollaborationService.CollaborationDispatch(
                config("guest"), f.leaderBot, "om_src_1", "worker", "task-001",
                "ou_owner", null, "leader",
                "整理会议纪要", "按时间线整理", null, 1, 3, "C:/ws/leader"));

        assertEquals("guest → 工人",
                f.leaderBot.card.get("header").get("subtitle").get("content").asText());
    }

    // ---------- round==1 / round>1 两版 mention 文案逐字 ----------

    @Test
    void round1mention文案逐字() {
        Fixture f = fixture();
        CollaborationService service = new CollaborationService(f.runtime);

        service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1));

        String dispatchId = extractDispatchId(f.leaderBot.mentionText);
        assertEquals("新的协作任务：整理会议纪要（任务编号：" + dispatchId + "），请查看上方卡片。",
                f.leaderBot.mentionText);
    }

    @Test
    void round大于1mention文案逐字() {
        Fixture f = fixture();
        CollaborationService service = new CollaborationService(f.runtime);

        service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 2));

        String dispatchId = extractDispatchId(f.leaderBot.mentionText);
        assertEquals("协作结果已经返回（任务编号：" + dispatchId + "），请查看上方卡片。",
                f.leaderBot.mentionText);
    }

    // ---------- replyCard 返回 null：throw 逐字且 inbox 已回收 ----------

    @Test
    void replyCard返回null时抛出逐字文案且回收() throws Exception {
        Fixture f = fixture();
        f.leaderBot.cardMessageId = null;
        // replyCard 调用瞬间收件箱应恰好有一笔（register 先于发卡）
        AtomicInteger sizeAtCardTime = new AtomicInteger(-1);
        f.leaderBot.beforeCard = () -> {
            try {
                sizeAtCardTime.set(inboxEntries(f.runtime.collaborationInbox()).size());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        CollaborationService service = new CollaborationService(f.runtime);

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1)));

        assertEquals("飞书没有返回协作卡片 message_id", e.getMessage());
        assertEquals(1, sizeAtCardTime.get(), "发卡前应已落账");
        assertTrue(inboxEntries(f.runtime.collaborationInbox()).isEmpty(), "失败后应回收");
        assertEquals(0, f.leaderBot.mentionCalls, "卡片失败不应发 mention");
    }

    // ---------- replyMention 返回 null：throw 逐字且 inbox 已回收 ----------

    @Test
    void replyMention返回null时抛出逐字文案且回收() throws Exception {
        Fixture f = fixture();
        f.leaderBot.mentionMessageId = null;
        CollaborationService service = new CollaborationService(f.runtime);

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1)));

        assertEquals("飞书没有返回协作通知 message_id", e.getMessage());
        assertTrue(inboxEntries(f.runtime.collaborationInbox()).isEmpty(), "失败后应回收");
    }

    // ---------- 成功日志格式逐字 ----------

    // M7 Task 0（裁决 2）切换申报：System.out 直写改 SLF4J info（{} 占位），消息体
    // 文案逐字不变；logback ConsoleAppender 缓存流引用致 System.setOut 捕获不到，
    // 捕获方式改 LogbackCapture（对 formattedMessage 断言，文本与切换前一致）。
    @Test
    void 成功日志格式逐字() {
        Fixture f = fixture();
        CollaborationService service = new CollaborationService(f.runtime);

        LogbackCapture capture = LogbackCapture.attach(CollaborationService.class);
        try {
            service.dispatch(dispatchOptions(config("leader"), f.leaderBot, 1));
        } finally {
            capture.detach();
        }

        assertEquals("[协作] task=task-001 leader -> worker round=1/3", capture.singleMessage());
    }
}

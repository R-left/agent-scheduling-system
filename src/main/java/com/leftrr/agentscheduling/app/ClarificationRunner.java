package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.TaskProgressSnapshot;
import com.leftrr.agentscheduling.core.TaskProgressTracker;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.CardText;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.ProductSpecCards;
import com.leftrr.agentscheduling.im.ScheduledCardScheduler;
import com.leftrr.agentscheduling.im.TaskCards;
import com.leftrr.agentscheduling.im.ThrottledCardUpdater;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 澄清续跑编排：用户答完澄清问题后按答案续跑 CLI，并按结果分流三类收尾。
 * 对齐 TS 版 src/app/clarification-runner.ts（228 行）——11 步控制流与全部文案逐字：
 * 分支 A（grill-me 且结果带 request_clarification）落新一轮澄清流程并发澄清卡；
 * 分支 B（to-spec / lark-doc）走产品方案提交校验（local 交付先验文档存在）并发确认卡；
 * 分支 C 兜底发结果卡与长答案续传；异常按取消/失败两版收尾卡。
 *
 * <p>TS→Java 组装（裁决 7，per-task 组装）：ThrottledCardUpdater 用四参生产构造
 * （updateCard 提交 + ScheduledCardScheduler 进程级单例 + 单线程 updateExecutor + 1s 窗口）；
 * heartbeat = 方法内自建 daemon 单线程 ScheduledExecutorService 固定频率 push 进度卡渲染
 * （对齐 TS setInterval(renderProgress, 1_000) + unref），回调只 push 绝不 finish；
 * finally 里 shutdownNow 心跳、shutdown updateExecutor。queueMicrotask 不涉及本任务。</p>
 */
public final class ClarificationRunner {

    /** 对齐 TS options 对象（clarification-runner.ts:26-33）；run 对应 TS 的 AbortController。 */
    public record ClarificationContinuation(
            AppRuntime runtime,
            Bot bot,
            BotRegistry.BotConfig config,
            Clarification.ClarificationFlow flow,
            CancellationToken run,
            ProductDeliveryMode defaultDeliveryMode) {}

    private ClarificationRunner() {}

    /** TS continueClarificationFlow（clarification-runner.ts:26）。 */
    public static void continueClarificationFlow(ClarificationContinuation options)
            throws IOException, InterruptedException {
        Session session = options.runtime().sessions().get(options.flow().sessionId());
        if (session == null) {
            // TS 36：throw new Error('需求澄清对应的会话已经失效')
            throw new RuntimeException("需求澄清对应的会话已经失效");
        }
        // 包私有重载注入 adapter 与 heartbeat，供测试替身替换；生产路径按注册表解析 + 自建 daemon 心跳
        continueClarificationFlow(options, session,
                CliAdapterRegistry.get(session.cliId()), newHeartbeatExecutor());
    }

    /**
     * 主体控制流（TS 38-227）。session/adapter/heartbeat 由入口解析或测试注入；
     * updateExecutor 与 ThrottledCardUpdater 在本方法内 per-task 组装（裁决 7）。
     */
    static void continueClarificationFlow(ClarificationContinuation options, Session session,
            CliAdapter adapter, ScheduledExecutorService heartbeat)
            throws IOException, InterruptedException {
        Bot bot = options.bot();
        Clarification.ClarificationFlow flow = options.flow();
        // TS 39-43：进度跟踪器（M6 裁决 3：accept 在 CLI 事件回调、snapshot 在心跳线程，tracker 自带 synchronized）
        TaskProgressTracker progress = new TaskProgressTracker(System::currentTimeMillis,
                options.runtime().contextWindows().get(session.id()), false);

        // TS 44-57：先发进度卡；失败在 try 外直接抛（不进 catch/finally 清理，对齐 TS）
        String progressCardMessageId = bot.replyCard(flow.originalMessageId(),
                TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                        adapter.displayName(), TaskCards.TaskStatus.RUNNING,
                        "正在基于你的选择整理需求", progress.snapshot(),
                        null, null, null, session.id())),
                flow.replyInThread());
        if (progressCardMessageId == null) {
            // TS 56：throw new Error('飞书没有返回需求整理进度卡片的 message_id')
            throw new RuntimeException("飞书没有返回需求整理进度卡片的 message_id");
        }

        // 裁决 7：per-task 组装节流更新器与单线程执行器
        ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
        ThrottledCardUpdater cardUpdater = new ThrottledCardUpdater(
                card -> bot.updateCard(progressCardMessageId, card),
                ScheduledCardScheduler.getInstance(), updateExecutor, 1_000);
        // TS 62-71 renderProgress：快照渲染为 running 卡后 push（当前步骤为空回落兜底文案）
        Runnable renderProgress = () -> {
            TaskProgressSnapshot snapshot = progress.snapshot();
            cardUpdater.push(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                    adapter.displayName(), TaskCards.TaskStatus.RUNNING,
                    snapshot.current() != null && !snapshot.current().isEmpty()
                            ? snapshot.current() : "正在整理需求",
                    snapshot, null, null, null, session.id())));
        };
        // TS 72-73 setInterval(renderProgress, 1_000) + unref → daemon SES 固定频率；
        // 心跳回调只 push 进度渲染，绝不 finish（finish 仅收尾路径调用，ScheduledCardScheduler 约定）
        heartbeat.scheduleAtFixedRate(renderProgress, 1_000, 1_000, TimeUnit.MILLISECONDS);

        try {
            Consumer<CliEvent> onEvent = progressConsumer(progress, renderProgress);
            // TS 76-91：按澄清答案续跑 CLI
            CliRunResult result = AppExecutions.executeCli(adapter,
                    Clarification.formatClarificationAnswers(flow),
                    session.workspaceDir(), session.cliSessionId(), options.run(), onEvent);
            observeResult(options.runtime(), session, result);

            // TS 100-102：分支 A 判定（skills 含 grill-me 且结果带合法澄清请求）
            Clarification.ClarificationRequest nextRequest =
                    options.config().skills().contains("grill-me")
                            ? Clarification.findClarificationRequest(result.toolCalls())
                            : null;
            if (nextRequest != null) {
                // TS 104-115：落新一轮澄清流程（卡片消息指向本次进度卡）
                Clarification.ClarificationFlow nextFlow =
                        options.runtime().clarificationFlows().create(
                                new Clarification.CreateFlowOptions(
                                        flow.taskId(), options.config().id(), session.id(),
                                        flow.ownerOpenId(), flow.ownerUnionId(),
                                        flow.collaboration(), flow.originalMessageId(),
                                        progressCardMessageId, flow.replyInThread(), nextRequest));
                cardUpdater.finish(ClarificationCards.buildClarificationCard(nextFlow));
                Notifications.sendResultNotification(bot, flow.originalMessageId(),
                        new BotIdentity(flow.ownerOpenId(), ""),
                        "还需要确认 " + nextRequest.questions().size() + " 个问题，请在上方卡片中选择。",
                        flow.replyInThread());
                return;
            }

            // TS 127-130：分支 B 判定（skills 含 to-spec 或 lark-doc）
            boolean managesProductSpec = options.config().skills().contains("to-spec")
                    || options.config().skills().contains("lark-doc");
            if (managesProductSpec) {
                // TS 132-151：确保产品提交；缺调用时由补交提示词重试一轮，重试事件继续喂进度
                ProductSpecSubmission submission =
                        ProductSpecSubmission.ensureProductSpecSubmission(
                                result, options.defaultDeliveryMode(),
                                (retryPrompt, retrySessionId) -> AppExecutions.executeCli(adapter,
                                        retryPrompt, session.workspaceDir(),
                                        retrySessionId != null ? retrySessionId : session.cliSessionId(),
                                        options.run(), onEvent));
                observeResult(options.runtime(), session, submission.result());
                ProductSpec.ProductSpecRequest productSpecRequest = submission.request();
                if (productSpecRequest instanceof ProductSpec.ProductSpecRequest.Local local) {
                    // TS 165-167：local 交付先校验 spec/tickets 真实存在
                    ProductSpecDocuments.assertProductSpecDocuments(session.workspaceDir(), local);
                }
                ProductSpec.ProductSpecFlow productSpecFlow =
                        options.runtime().productSpecFlows().create(
                                new ProductSpec.CreateFlowOptions(
                                        flow.taskId(), options.config().id(), session.id(),
                                        flow.ownerOpenId(), flow.ownerUnionId(),
                                        flow.collaboration(), productSpecRequest));
                cardUpdater.finish(ProductSpecCards.buildProductSpecApprovalCard(productSpecFlow));
                Notifications.sendResultNotification(bot, flow.originalMessageId(),
                        new BotIdentity(flow.ownerOpenId(), ""),
                        "产品方案已生成，请查看上方确认卡。", flow.replyInThread());
                return;
            }

            // TS 188-195：分支 C 兜底——结果卡（success）
            cardUpdater.finish(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                    adapter.displayName(), TaskCards.TaskStatus.SUCCESS,
                    "需求已经整理完成", progress.snapshot(), result.answer(), result.stats(),
                    null, null)));
            // TS 196-200：长答案续传分片逐条回复
            if (CardText.answerNeedsContinuation(result.answer())) {
                for (String chunk : CardText.splitLongText(CardText.answerContinuation(result.answer()))) {
                    bot.reply(flow.originalMessageId(), chunk, flow.replyInThread());
                }
            }
            Notifications.sendResultNotification(bot, flow.originalMessageId(),
                    new BotIdentity(flow.ownerOpenId(), ""),
                    "需求澄清已完成，请查看上方结果。", flow.replyInThread());
        } catch (Exception error) {
            // TS 208-219：catch——按取消/失败两版收尾卡；中断路径恢复中断标记（TS 无中断概念，Java 惯例）
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            boolean aborted = options.run().isCancelled();
            // ㉑：error.getMessage() 可能为 null（如 NPE），technicalDetail 会缺失——
            // null 时回落 error.toString()（TS (error as Error).message 恒为字符串的等价兜底）
            String technicalDetail = aborted ? null
                    : error.getMessage() != null ? error.getMessage() : error.toString();
            cardUpdater.finish(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                    adapter.displayName(),
                    aborted ? TaskCards.TaskStatus.CANCELLED : TaskCards.TaskStatus.FAILED,
                    aborted ? "需求整理已停止。你可以继续在当前话题里补充。"
                            : "需求整理没有完成。请在当前话题里重试。",
                    progress.snapshot(), null, null,
                    technicalDetail, null)));
        } finally {
            // TS 220-226：clearInterval（shutdownNow 统一覆盖 try/catch 各路径）+ activeRuns
            // 身份清理（token 引用相等才删，防误删他人注册）+ 标记会话空闲（声明上抛 IOException）
            heartbeat.shutdownNow();
            updateExecutor.shutdown();
            ActiveRun activeRun = options.runtime().activeRuns().get(session.id());
            if (activeRun != null && activeRun.token() == options.run()) {
                options.runtime().activeRuns().remove(session.id());
            }
            SessionView.markSessionIdle(options.runtime().sessions(), session.id());
        }
    }

    /**
     * TS onEvent 回调（76-91 与 142-149 两处重复逻辑）：只放行 tool_start/tool_end/context
     * 三类事件喂进度跟踪器并立即渲染，其余事件（result/stats/error 等）不进进度。
     */
    private static Consumer<CliEvent> progressConsumer(TaskProgressTracker progress,
            Runnable renderProgress) {
        return event -> {
            if (!(event instanceof CliEvent.ToolStartEvent)
                    && !(event instanceof CliEvent.ToolEndEvent)
                    && !(event instanceof CliEvent.ContextEvent)) {
                return;
            }
            progress.accept(event);
            renderProgress.run();
        };
    }

    /**
     * TS 93-98 与 153-164 两处重复的回写：CLI 会话 ID 回写（空串为 falsy 不写）+
     * 上下文窗口回写（裁决 12：stats 缺失、字段缺失或为 0 均不回写，对齐 TS 真值检查）。
     */
    private static void observeResult(AppRuntime runtime, Session session, CliRunResult result)
            throws IOException {
        if (result.sessionId() != null && !result.sessionId().isEmpty()) {
            runtime.sessions().setCliSessionId(session.id(), result.sessionId());
        }
        if (result.stats() != null && result.stats().contextWindowTokens() != null
                && result.stats().contextWindowTokens() != 0) {
            runtime.contextWindows().put(session.id(), result.stats().contextWindowTokens());
        }
    }

    /** TS setInterval + unref 的 Java 形：daemon 单线程调度器，不阻止 JVM 退出。 */
    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "clarification-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.dto.cli.CliEvent;
import com.leftrr.agentscheduling.dto.cli.CliRunResult;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.dto.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.dto.core.TaskProgressSnapshot;
import com.leftrr.agentscheduling.core.TaskProgressTracker;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
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
 * 澄清续跑：用户答完澄清问题后按答案续跑 CLI，按结果分流三类收尾——
 * 分支 A（grill-me 且结果又带澄清请求）落新一轮澄清流程并发澄清卡；
 * 分支 B（to-spec / lark-doc）走产品方案提交校验并发确认卡；
 * 分支 C 兜底发结果卡与长答案续传。异常按取消/失败两版收尾卡。全部文案为产品文案。
 */
public final class ClarificationRunner {

    /** 续跑所需上下文；run 为本次执行的取消令牌。 */
    public record ClarificationContinuation(
            AppRuntime runtime,
            Bot bot,
            BotRegistry.BotConfig config,
            Clarification.ClarificationFlow flow,
            CancellationToken run,
            ProductDeliveryMode defaultDeliveryMode) {}

    private ClarificationRunner() {}

    public static void continueClarificationFlow(ClarificationContinuation options)
            throws IOException, InterruptedException {
        Session session = options.runtime().sessions().get(options.flow().sessionId());
        if (session == null) {
            throw new RuntimeException("需求澄清对应的会话已经失效");
        }
        // adapter 按注册表解析，心跳自建 daemon 调度器
        continueClarificationFlowBody(options, session,
                CliAdapterRegistry.get(session.cliId()), newHeartbeatExecutor());
    }

    /** 主体控制流。 */
    private static void continueClarificationFlowBody(ClarificationContinuation options,
            Session session, CliAdapter adapter, ScheduledExecutorService heartbeat)
            throws IOException, InterruptedException {
        Bot bot = options.bot();
        Clarification.ClarificationFlow flow = options.flow();
        // accept 在 CLI 事件回调、snapshot 在心跳线程，跨线程访问靠 tracker 内部 synchronized
        TaskProgressTracker progress = new TaskProgressTracker(System::currentTimeMillis,
                options.runtime().contextWindows().get(session.id()), false);

        // 先发进度卡；此刻还没进 try，失败直接抛、无清理可做
        String progressCardMessageId = bot.replyCard(flow.originalMessageId(),
                TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                        adapter.displayName(), TaskCards.TaskStatus.RUNNING,
                        "正在基于你的选择整理需求", progress.snapshot(),
                        null, null, null, session.id())),
                flow.replyInThread());
        if (progressCardMessageId == null) {
            throw new RuntimeException("飞书没有返回需求整理进度卡片的 message_id");
        }

        ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
        ThrottledCardUpdater cardUpdater = new ThrottledCardUpdater(
                card -> bot.updateCard(progressCardMessageId, card),
                ScheduledCardScheduler.getInstance(), updateExecutor, 1_000);
        Runnable renderProgress = () -> {
            TaskProgressSnapshot snapshot = progress.snapshot();
            cardUpdater.push(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                    adapter.displayName(), TaskCards.TaskStatus.RUNNING,
                    snapshot.current() != null && !snapshot.current().isEmpty()
                            ? snapshot.current() : "正在整理需求",
                    snapshot, null, null, null, session.id())));
        };
        // 心跳回调只 push 绝不 finish——finish 只能收尾路径调（ScheduledCardScheduler 约定）
        heartbeat.scheduleAtFixedRate(renderProgress, 1_000, 1_000, TimeUnit.MILLISECONDS);

        try {
            Consumer<CliEvent> onEvent = progressConsumer(progress, renderProgress);
            CliRunResult result = AppExecutions.executeCli(adapter,
                    Clarification.formatClarificationAnswers(flow),
                    session.workspaceDir(), session.cliSessionId(), options.run(), onEvent);
            observeResult(options.runtime(), session, result);

            // 分支 A：又一轮澄清
            Clarification.ClarificationRequest nextRequest =
                    options.config().skills().contains("grill-me")
                            ? Clarification.findClarificationRequest(result.toolCalls())
                            : null;
            if (nextRequest != null) {
                // 新流程的卡片消息指向本次进度卡
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

            // 分支 B：产品方案
            boolean managesProductSpec = options.config().skills().contains("to-spec")
                    || options.config().skills().contains("lark-doc");
            if (managesProductSpec) {
                // 缺 request_spec_approval 时用补交提示词重试一轮，重试事件继续喂进度
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
                    // local 交付先校验文档真实存在
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

            // 分支 C：兜底结果卡
            cardUpdater.finish(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                    adapter.displayName(), TaskCards.TaskStatus.SUCCESS,
                    "需求已经整理完成", progress.snapshot(), result.answer(), result.stats(),
                    null, null)));
            if (CardText.answerNeedsContinuation(result.answer())) {
                for (String chunk : CardText.splitLongText(CardText.answerContinuation(result.answer()))) {
                    bot.reply(flow.originalMessageId(), chunk, flow.replyInThread());
                }
            }
            Notifications.sendResultNotification(bot, flow.originalMessageId(),
                    new BotIdentity(flow.ownerOpenId(), ""),
                    "需求澄清已完成，请查看上方结果。", flow.replyInThread());
        } catch (Exception error) {
            // 取消/失败两版收尾卡
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            boolean aborted = options.run().isCancelled();
            // getMessage() 可能为 null，回落 toString() 兜底
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
            // 停心跳 + activeRuns 只删自己的 run（token 引用相等，防误删他人注册）+ 标记会话空闲
            heartbeat.shutdownNow();
            updateExecutor.shutdown();
            ActiveRun activeRun = options.runtime().activeRuns().get(session.id());
            if (activeRun != null && activeRun.token() == options.run()) {
                options.runtime().activeRuns().remove(session.id());
            }
            SessionView.markSessionIdle(options.runtime().sessions(), session.id());
        }
    }

    /** 只放行 tool_start/tool_end/context 三类喂进度并渲染。 */
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

    /** 回写 CLI 会话 ID（空串不写）与上下文窗口（缺失或 0 不写）。 */
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

    /** 心跳调度器：daemon 单线程，不阻止 JVM 退出。 */
    private static ScheduledExecutorService newHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "clarification-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}

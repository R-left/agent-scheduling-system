package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.NativeCompact;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.AppPaths;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.CommandParser;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.TaskProgressTracker;
import com.leftrr.agentscheduling.core.TopicTask;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.dto.app.BotRuntime;
import com.leftrr.agentscheduling.dto.cli.CliEvent;
import com.leftrr.agentscheduling.dto.cli.CliId;
import com.leftrr.agentscheduling.dto.cli.CliRunResult;
import com.leftrr.agentscheduling.dto.core.CancelMode;
import com.leftrr.agentscheduling.dto.core.CliRequest;
import com.leftrr.agentscheduling.dto.core.MessageAddress;
import com.leftrr.agentscheduling.dto.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.dto.core.ResolvedSession;
import com.leftrr.agentscheduling.dto.core.SessionStatus;
import com.leftrr.agentscheduling.dto.core.SlashCommand;
import com.leftrr.agentscheduling.dto.core.TaskProgressSnapshot;
import com.leftrr.agentscheduling.dto.core.TopicAddress;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import com.leftrr.agentscheduling.dto.im.ExtractedResource;
import com.leftrr.agentscheduling.dto.im.IncomingMessage;
import com.leftrr.agentscheduling.dto.im.Mention;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.CardScheduler;
import com.leftrr.agentscheduling.im.CardText;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.MessageParser;
import com.leftrr.agentscheduling.im.ProductSpecCards;
import com.leftrr.agentscheduling.im.ScheduledCardScheduler;
import com.leftrr.agentscheduling.im.SessionCards;
import com.leftrr.agentscheduling.im.TaskCards;
import com.leftrr.agentscheduling.im.ThrottledCardUpdater;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * onMessage 主流程编排：协作识别 → 会话解析 → 附件下载 → 任务卡 → CLI 执行 →
 * 收尾三分支（澄清 / 产品方案 / 正常完成）。执行体在当前线程同步阻塞——
 * 「CLI 在后台执行」由调用方把每条消息投到独立虚拟线程来表达。
 *
 * <p>收尾链是嵌套 try/catch/finally，穿越序固定：执行主体 → catch（取消/失败两版
 * 收尾）→ finally（容错清理）→ 外层兜底，三层抛出的异常统一落兜底 catch；
 * 分支内裸 return 只走 finally、不触发兜底。改动收尾顺序时保持这个穿越关系。</p>
 */
public final class BotMessageOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotMessageOrchestrator.class);

    /** 协作识别正则；全角冒号是协作文案的一部分，别顺手改成半角。 */
    private static final Pattern DISPATCH_ID_PATTERN = Pattern.compile("任务编号：([a-f0-9]{12})");

    /** 进度心跳间隔（1s）。 */
    static final long PROGRESS_HEARTBEAT_INTERVAL_MS = 1_000;

    private final AppRuntime runtime;
    private final BotRegistry.BotConfig config;
    private final CollaborationService collaborationService;
    private final ProductDeliveryMode defaultProductDeliveryMode;
    private final Function<CliId, CliAdapter> adapterResolver;

    public BotMessageOrchestrator(AppRuntime runtime, BotRegistry.BotConfig config,
            CollaborationService collaborationService,
            ProductDeliveryMode defaultProductDeliveryMode) {
        this.runtime = runtime;
        this.config = config;
        this.collaborationService = collaborationService;
        this.defaultProductDeliveryMode = defaultProductDeliveryMode;
        this.adapterResolver = CliAdapterRegistry::get;
    }

    /** 主流程主体：协作识别 → 澄清 → 会话 → 附件 → 命令分流 → 任务卡 → 执行 → 收尾三分支。 */
    public void onMessage(IncomingMessage msg, Bot bot) throws IOException, InterruptedException {
        String resolved = MessageParser.resolveMentions(msg.text(), msg.mentions());
        String taskId = TopicTask.topicTaskId(new TopicAddress(
                msg.messageId(), msg.chatId(), msg.threadId(), msg.rootId()));

        // bot→bot 协作识别
        BotRuntime senderRuntime = null; // catch 分支通知用；null = 无协作来源
        Collaboration.CollaborationMessage collaboration = null;
        if (msg.senderType().equals("app") || msg.senderType().equals("bot")) {
            BotRuntime currentRuntime = runtime.botRuntimes().get(config.id());
            boolean mentionedCurrentBot = false;
            if (currentRuntime != null) {
                for (Mention mention : msg.mentions()) {
                    if (mention.openId().equals(currentRuntime.identity().openId())) {
                        mentionedCurrentBot = true;
                        break;
                    }
                }
            }
            Matcher dispatchMatcher = DISPATCH_ID_PATTERN.matcher(msg.text());
            String dispatchId = dispatchMatcher.find() ? dispatchMatcher.group(1) : null;
            Collaboration.CollaborationMessage pending =
                    msg.messageType().equals("post") && mentionedCurrentBot && dispatchId != null
                            ? runtime.collaborationInbox().consume(dispatchId, config.id())
                            : null;
            if (pending == null) {
                LOGGER.info("[协作] 忽略非目标 bot 消息 sender={} target={}",
                        msg.senderOpenId(), config.id());
                return;
            }
            senderRuntime = runtime.botRuntimes().get(pending.fromBotId());
            if (senderRuntime == null) {
                LOGGER.info("[协作] 找不到来源 bot: {}", pending.fromBotId());
                return;
            }
            String turnKey = Collaboration.collaborationTurnKey(pending);
            // 消息是并发进来的，「先查再加」两步会重复放行，用 add 返回值一步判定
            if (!runtime.processedCollaborationTurns().add(turnKey)) {
                LOGGER.info("[协作] 忽略重复消息 {}", turnKey);
                return;
            }
            collaboration = pending;
        }

        boolean hasThread = isPresent(msg.threadId()) || isPresent(msg.rootId());
        SlashCommand command = CommandParser.parseCommand(resolved).orElse(null);
        CliRequest cliRequest = CommandParser.parseCliRequest(resolved).orElse(null);
        if (cliRequest != null && cliRequest.prompt().isEmpty()) {
            bot.reply(msg.messageId(),
                    "请在 /" + cliRequest.cliId().id() + " 后面写下任务，例如：/"
                            + cliRequest.cliId().id() + " 检查项目状态",
                    hasThread);
            return;
        }

        Clarification.ClarificationFlow pendingClarification =
                !"app".equals(msg.senderType()) && !"bot".equals(msg.senderType()) && command == null
                        ? runtime.clarificationFlows().findForTask(taskId, config.id())
                        : null;

        // 会话解析；IncomingMessage 没实现 MessageAddress，这里手工构造
        ResolvedSession resolvedSession = runtime.sessions().resolve(
                new MessageAddress(msg.messageId(), msg.chatId(), msg.threadId(), msg.rootId()),
                cliRequest != null ? cliRequest.cliId() : config.defaultCliId(),
                config.id(),
                collaboration != null ? collaboration.workspaceDir() : config.workspaceDir());
        Session session = resolvedSession.session();
        boolean isNew = resolvedSession.isNew();
        if (command != null && isNew && session.status() == SessionStatus.CREATING) {
            session = runtime.sessions().transition(session.id(), SessionStatus.IDLE);
        }

        CliAdapter cliAdapter = adapterResolver.apply(session.cliId());
        boolean isCompacting = command instanceof SlashCommand.CompactCommand;

        // 附件下载：单个失败不中断整体
        List<ExtractedResource> resources =
                MessageParser.extractResourceKeys(msg.messageType(), msg.rawContent());
        List<String> downloadedFiles = new ArrayList<>();
        for (ExtractedResource res : resources) {
            try {
                String savePath = bot.downloadResource(msg.messageId(), res.key(), res.type().id(),
                        AppPaths.downloadsDirectory(), res.fileName());
                String absPath = Path.of(savePath).toAbsolutePath().normalize().toString();
                downloadedFiles.add(absPath);
                LOGGER.info("  [下载] {} → {}", res.type().id(), absPath);
            } catch (IOException | RuntimeException e) {
                // 下载失败会以 LarkException（非受检）抛出，只接 IOException 会漏——
                // 这里必须保证单个附件挂了不炸整条消息
                LOGGER.error("  [下载失败] {}: {}", res.key(), e.getMessage());
            }
        }

        String taskText = composeTaskText(pendingClarification, collaboration, cliRequest,
                resolved, downloadedFiles);
        String prompt = BotRegistry.buildBotPrompt(config, taskText,
                runtime.teamRegistry().contextFor(config.id()), defaultProductDeliveryMode);
        String taskCardTitle = isCompacting ? "整理上下文" : cliAdapter.displayName();

        LOGGER.info("[收到] chat={} threadId={} rootId={} sender={}",
                msg.chatId(), msg.threadId(), msg.rootId(), msg.senderOpenId());
        LOGGER.info("  原文: {}", msg.text());
        LOGGER.info("  还原: {}", resolved);
        LOGGER.info("  mentions: {}", mentionsLine(msg.mentions()));
        LOGGER.info("  [会话] {} id={} status={}",
                isNew ? "新建" : "复用", session.id(), session.status().id());

        // 命令分流
        CommandHandler.CommandOutcome commandOutcome = CommandHandler.handleSessionCommand(
                new CommandHandler.HandleSessionCommandOptions(
                        runtime, config, msg, bot, session, cliAdapter, command,
                        cliRequest, isNew, hasThread));
        if (commandOutcome == CommandHandler.CommandOutcome.HANDLED) {
            return;
        }

        // 三个状态守卫
        if (session.status() == SessionStatus.CLOSED) {
            bot.reply(msg.messageId(), "这个话题的会话已经关闭，请新开一个话题继续。", hasThread);
            return;
        }
        if (!isNew && session.status() == SessionStatus.CREATING) {
            bot.reply(msg.messageId(), "当前会话正在准备，请稍后再追问。", hasThread);
            return;
        }
        if (session.status() == SessionStatus.ACTIVE) {
            bot.reply(msg.messageId(), "当前会话还在执行，请等任务结束后再追问。", hasThread);
            return;
        }

        // 旧澄清作废
        if (pendingClarification != null) {
            runtime.clarificationFlows().delete(pendingClarification.token());
            if (pendingClarification.cardMessageId() != null) {
                try {
                    bot.updateCard(pendingClarification.cardMessageId(),
                            ClarificationCards.buildClarificationSupersededCard(pendingClarification));
                } catch (RuntimeException e) {
                    LOGGER.warn("[澄清] 旧卡片更新失败，继续处理用户的新消息: {}", e.getMessage());
                }
            }
        }

        // 协作工作区切换；session 要重新赋值，后续 lambda 只捕 effectively final
        if (collaboration != null
                && !session.workspaceDir().equals(collaboration.workspaceDir())) {
            Workspace.ensureWorkspaceDirectory(collaboration.workspaceDir());
            session = runtime.sessions().setWorkspaceDir(session.id(), collaboration.workspaceDir());
        }

        // 进入执行
        runtime.sessions().transition(session.id(), SessionStatus.ACTIVE);
        CancellationToken run = new CancellationToken();
        ActiveRun activeRun = new ActiveRun(run,
                collaboration != null ? collaboration.ownerOpenId() : msg.senderOpenId());
        runtime.activeRuns().put(session.id(), activeRun);

        // 先发任务卡占位，让用户看到任务已进入队列
        String cardId;
        try {
            cardId = bot.replyCard(msg.messageId(),
                    TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                            taskCardTitle, TaskCards.TaskStatus.RUNNING,
                            initialDetail(isCompacting, cliAdapter, command),
                            null, null, null, null, session.id())),
                    hasThread);
        } catch (RuntimeException error) {
            removeActiveRun(session.id(), run);
            SessionView.markSessionIdle(runtime.sessions(), session.id());
            throw error;
        }

        if (cardId == null) {
            LOGGER.error("[卡片] 响应里没有 message_id，无法继续更新");
            removeActiveRun(session.id(), run);
            SessionView.markSessionIdle(runtime.sessions(), session.id());
            return;
        }
        LOGGER.info("[卡片] 已发送 message_id={} inThread={}", cardId, hasThread);

        // 进度心跳 + 执行 + 收尾三分支

        // 协作工作区切换后 session 不再重新赋值；lambda 只捕 effectively final，这里取最终快照
        Session execSession = session;

        // 时间源只用于算差值，用哪个 wall clock 都行；CLI 会话 ID 空/null 都算新会话
        TaskProgressTracker progress = new TaskProgressTracker(System::currentTimeMillis,
                runtime.contextWindows().get(execSession.id()),
                !isPresent(execSession.cliSessionId()));
        // ThrottledCardUpdater 必须配进程级单例 scheduler + 单线程 executor（更新要串行）；
        // executor 每任务新建、finally 收尾
        ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
        ThrottledCardUpdater cardUpdater = new ThrottledCardUpdater(
                card -> bot.updateCard(cardId, card),
                ScheduledCardScheduler.getInstance(), updateExecutor, 1_000);
        // compact 心跳 detail 用固定文案、不推进度字段；非 compact 取快照并推
        Runnable renderProgress = () -> {
            TaskProgressSnapshot snapshot = progress.snapshot();
            cardUpdater.push(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                    taskCardTitle, TaskCards.TaskStatus.RUNNING,
                    isCompacting
                            ? "正在调用 " + cliAdapter.displayName() + " 原生上下文整理"
                            : snapshot.current(),
                    !isCompacting ? snapshot : null,
                    null, null, null, execSession.id())));
        };
        // scheduler 不支持 fixed-rate，靠自重排表达固定间隔；daemon 线程不挡 JVM 退出
        ProgressHeartbeat progressHeartbeat = new ProgressHeartbeat(
                ScheduledCardScheduler.getInstance(), renderProgress,
                PROGRESS_HEARTBEAT_INTERVAL_MS);
        progressHeartbeat.start();

        // 只放行 tool_start/tool_end/context 喂进度；产品方案重试路径（分支二）复用同一消费者
        Consumer<CliEvent> onProgressEvent = event -> {
            if (!isProgressEvent(event)) {
                return;
            }
            progress.accept(event);
            renderProgress.run();
        };

        // 收尾链模板见类注释：主体 → catch（取消/失败）→ finally（容错清理）→ 外层兜底
        try {
            try {
                // compact 走 NativeCompact，否则 executeCli
                CliRunResult result = isCompacting
                        ? compactRunResult(NativeCompact.compactCliSession(
                                new NativeCompact.Options(
                                        cliAdapter,
                                        // compact 必已有 CLI 会话，非空是前提，违反就快速失败
                                        Objects.requireNonNull(session.cliSessionId()),
                                        session.workspaceDir(),
                                        command instanceof SlashCommand.CompactCommand compactCommand
                                                ? compactCommand.instructions()
                                                : null,
                                        run,
                                        null)))
                        : AppExecutions.executeCli(cliAdapter, prompt, session.workspaceDir(),
                                session.cliSessionId(), run, onProgressEvent);

                // ---- 停心跳 + 回写会话（compact 不回写；token 数为 0 视为缺失）----
                progressHeartbeat.stop();
                if (!isCompacting && isPresent(result.sessionId())) {
                    runtime.sessions().setCliSessionId(session.id(), result.sessionId());
                }
                if (!isCompacting && result.stats() != null
                        && result.stats().contextWindowTokens() != null
                        && result.stats().contextWindowTokens() != 0) {
                    runtime.contextWindows().put(session.id(),
                            result.stats().contextWindowTokens());
                }

                // ---- 分支一：澄清卡收尾 ----
                Clarification.ClarificationRequest clarificationRequest =
                        !isCompacting && config.skills().contains("grill-me")
                                ? Clarification.findClarificationRequest(result.toolCalls())
                                : null;
                if (clarificationRequest != null) {
                    Clarification.ClarificationFlow flow = runtime.clarificationFlows().create(
                            new Clarification.CreateFlowOptions(
                                    taskId,
                                    config.id(),
                                    session.id(),
                                    collaboration != null
                                            ? collaboration.ownerOpenId() : msg.senderOpenId(),
                                    collaboration != null
                                            ? collaboration.ownerUnionId() : msg.senderUnionId(),
                                    collaboration != null
                                            ? Collaboration.collaborationOrigin(collaboration)
                                            : null,
                                    msg.messageId(),
                                    cardId,
                                    hasThread,
                                    clarificationRequest));
                    removeActiveRun(session.id(), run);
                    SessionView.markSessionIdle(runtime.sessions(), session.id());
                    cardUpdater.finish(ClarificationCards.buildClarificationCard(flow));
                    Notifications.sendResultNotification(bot, msg.messageId(),
                            new BotIdentity(flow.ownerOpenId(), ""),
                            "需要你确认 " + clarificationRequest.questions().size()
                                    + " 个问题，请在上方卡片中选择。",
                            hasThread);
                    LOGGER.info("[澄清] 已发送交互卡片 questions={}",
                            clarificationRequest.questions().size());
                    return;
                }

                // ---- 分支二：产品方案收尾 + dispatch 合法性校验 ----
                CliRunResult finalResult = result;
                ProductSpec.ProductSpecRequest productSpecRequest = null;
                boolean managesProductSpec = !isCompacting
                        && (config.skills().contains("to-spec")
                                || config.skills().contains("lark-doc"));
                if (managesProductSpec) {
                    ProductSpecSubmission submission = ProductSpecSubmission
                            .ensureProductSpecSubmission(result, defaultProductDeliveryMode,
                                    (retryPrompt, resultSessionId) -> AppExecutions.executeCli(
                                            cliAdapter,
                                            retryPrompt,
                                            execSession.workspaceDir(),
                                            // 只有 null 才回落原 CLI ID；空串是有效值不能回落
                                            resultSessionId != null
                                                    ? resultSessionId : execSession.cliSessionId(),
                                            run,
                                            onProgressEvent));
                    finalResult = submission.result();
                    productSpecRequest = submission.request();
                    if (isPresent(finalResult.sessionId())) {
                        runtime.sessions().setCliSessionId(session.id(), finalResult.sessionId());
                    }
                    if (finalResult.stats() != null
                            && finalResult.stats().contextWindowTokens() != null
                            && finalResult.stats().contextWindowTokens() != 0) {
                        runtime.contextWindows().put(session.id(),
                                finalResult.stats().contextWindowTokens());
                    }
                }
                Collaboration.DispatchTaskRequest dispatchRequest = !isCompacting
                        ? Collaboration.findDispatchTaskRequest(finalResult.toolCalls())
                        : null;
                if (dispatchRequest != null) {
                    // dispatch 校验；TeamRegistry 与本配置同源（同一 teamLeaderId 构造），
                    // leaderBotId() 即真值
                    if (!config.id().equals(runtime.teamRegistry().leaderBotId())) {
                        throw new RuntimeException(
                                "只有 CEO 助理可以调用 dispatch_task 派发团队任务");
                    }
                    if (runtime.teamRegistry().get(dispatchRequest.targetBotId()) == null) {
                        throw new RuntimeException(
                                "团队成员未注册或未启用: " + dispatchRequest.targetBotId());
                    }
                    if (dispatchRequest.targetBotId().equals(config.id())) {
                        throw new RuntimeException(
                                "不能把团队任务派发给当前 bot: " + config.id());
                    }
                    if (collaboration != null
                            && collaboration.round() >= collaboration.maxRounds()) {
                        throw new RuntimeException("协作任务已达到轮次上限 "
                                + collaboration.maxRounds() + "，不能继续派发");
                    }
                }
                if (productSpecRequest != null && dispatchRequest != null) {
                    throw new RuntimeException("不能同时提交产品方案和派发团队任务");
                }
                if (productSpecRequest != null) {
                    if (productSpecRequest
                            instanceof ProductSpec.ProductSpecRequest.Local localRequest) {
                        // 只有 local 交付需要校验文档真实存在
                        ProductSpecDocuments.assertProductSpecDocuments(session.workspaceDir(),
                                localRequest);
                    }
                    removeActiveRun(session.id(), run);
                    SessionView.markSessionIdle(runtime.sessions(), session.id());
                    ProductSpec.ProductSpecFlow flow = runtime.productSpecFlows().create(
                            new ProductSpec.CreateFlowOptions(
                                    taskId,
                                    config.id(),
                                    session.id(),
                                    collaboration != null
                                            ? collaboration.ownerOpenId() : msg.senderOpenId(),
                                    collaboration != null
                                            ? collaboration.ownerUnionId() : msg.senderUnionId(),
                                    collaboration != null
                                            ? Collaboration.collaborationOrigin(collaboration)
                                            : null,
                                    productSpecRequest));
                    cardUpdater.finish(ProductSpecCards.buildProductSpecApprovalCard(flow));
                    Notifications.sendResultNotification(bot, msg.messageId(),
                            new BotIdentity(collaboration != null
                                    ? collaboration.ownerOpenId() : msg.senderOpenId(), ""),
                            "产品方案已生成，请查看上方确认卡。",
                            hasThread);
                    LOGGER.info("[产品文档] 已展示待确认产物");
                    return;
                }

                // ---- 分支三：正常完成 + 派发/协作续转 ----
                TaskProgressSnapshot snapshot = progress.snapshot();
                cardUpdater.finish(isCompacting
                        ? SessionCards.buildSessionNoticeCard(
                                new SessionCards.SessionNoticeCardOptions(
                                        isPresent(finalResult.answer())
                                                ? "暂时无需整理" : "上下文已整理",
                                        // answer 为 null/空串时走两段兜底文案
                                        isPresent(finalResult.answer())
                                                ? finalResult.answer()
                                                : String.join("\n\n",
                                                        cliAdapter.displayName()
                                                                + " 已在当前 CLI 会话内完成原生压缩。",
                                                        "CLI 会话 ID 保持不变，下一条任务会继续使用"
                                                                + "整理后的上下文。"),
                                        isPresent(finalResult.answer()) ? "grey" : "green"))
                        : TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                                taskCardTitle, TaskCards.TaskStatus.SUCCESS,
                                "执行完成", snapshot,
                                finalResult.answer(), finalResult.stats(), null, null)));
                if (!isCompacting && CardText.answerNeedsContinuation(finalResult.answer())) {
                    for (String chunk : CardText.splitLongText(
                            CardText.answerContinuation(finalResult.answer()))) {
                        bot.reply(msg.messageId(), chunk, hasThread);
                    }
                }
                // 注意：这里必须用原始 result，不能拿 finalResult
                LOGGER.info("[CLI] {} 完成 session_id={}", cliAdapter.id().id(),
                        result.sessionId() != null ? result.sessionId() : "(无)");
                if (collaboration == null) {
                    Notifications.sendResultNotification(bot, msg.messageId(),
                            new BotIdentity(msg.senderOpenId(), ""),
                            isCompacting ? "上下文整理已完成，请查看上方结果。"
                                    : "任务已完成，请查看上方结果。",
                            hasThread);
                }
                if (!isCompacting) {
                    try {
                        if (dispatchRequest != null) {
                            collaborationService.dispatch(
                                    new CollaborationService.CollaborationDispatch(
                                            config, bot, msg.messageId(),
                                            dispatchRequest.targetBotId(),
                                            taskId,
                                            collaboration != null
                                                    ? collaboration.ownerOpenId()
                                                    : msg.senderOpenId(),
                                            collaboration != null
                                                    ? collaboration.ownerUnionId()
                                                    : msg.senderUnionId(),
                                            collaboration != null
                                                    ? collaboration.reportToBotId()
                                                    : config.id(),
                                            dispatchRequest.objective(),
                                            dispatchRequest.instruction(),
                                            dispatchRequest.expectedOutput(),
                                            collaboration != null
                                                    ? collaboration.round() + 1 : 1,
                                            collaboration != null
                                                    ? collaboration.maxRounds()
                                                    : config.collaborationMaxRounds(),
                                            session.workspaceDir()));
                            if (collaboration == null) {
                                BotRuntime targetRuntime =
                                        runtime.botRuntimes().get(dispatchRequest.targetBotId());
                                String targetName = targetRuntime != null
                                        ? targetRuntime.identity().name()
                                        : dispatchRequest.targetBotId();
                                Notifications.sendResultNotification(bot, msg.messageId(),
                                        new BotIdentity(msg.senderOpenId(), ""),
                                        "任务已交给 " + targetName + "，请查看上方协作消息。",
                                        hasThread);
                            }
                        } else if (collaboration != null) {
                            if (collaboration.reportToBotId().equals(config.id())) {
                                Notifications.sendResultNotification(bot, msg.messageId(),
                                        new BotIdentity(collaboration.ownerOpenId(), ""),
                                        "协作任务“" + collaboration.objective()
                                                + "”已经完成，请查看上方结果。",
                                        hasThread);
                            } else if (collaboration.round() >= collaboration.maxRounds()) {
                                Notifications.sendResultNotification(bot, msg.messageId(),
                                        new BotIdentity(collaboration.ownerOpenId(), ""),
                                        "协作任务“" + collaboration.objective() + "”已达到 "
                                                + collaboration.maxRounds()
                                                + " 轮上限，请查看上方结果并决定下一步。",
                                        hasThread);
                            } else {
                                // 回派 reportToBotId
                                BotRuntime selfRuntime = runtime.botRuntimes().get(config.id());
                                String selfName = selfRuntime != null
                                        ? selfRuntime.identity().name() : config.id();
                                collaborationService.dispatch(
                                        new CollaborationService.CollaborationDispatch(
                                                config, bot, msg.messageId(),
                                                collaboration.reportToBotId(),
                                                collaboration.taskId(),
                                                collaboration.ownerOpenId(),
                                                collaboration.ownerUnionId(),
                                                collaboration.reportToBotId(),
                                                collaboration.objective(),
                                                String.join("\n\n",
                                                        selfName
                                                                + " 已完成当前协作任务，下面是它的结果：",
                                                        finalResult.answer(),
                                                        "请基于这份结果继续组织后续工作：仍需其他成员参与时"
                                                                + "使用 dispatch_task 继续派发；"
                                                                + "已经可以交付时，直接向用户汇总结论。"),
                                                "继续推进原任务，或在已经完成时向用户给出最终结论。",
                                                collaboration.round() + 1,
                                                collaboration.maxRounds(),
                                                session.workspaceDir()));
                            }
                        }
                    } catch (RuntimeException dispatchError) {
                        LOGGER.error("[协作] 派发失败: {}", dispatchError.getMessage());
                        bot.reply(msg.messageId(),
                                "协作派发失败：" + dispatchError.getMessage(), hasThread);
                    }
                }
            } catch (Throwable error) {
                // ---- catch：先停心跳，再按 token 取消状态分岔 ----
                progressHeartbeat.stop();
                if (run.isCancelled()) {
                    LOGGER.info("[CLI] 任务已取消");
                    cardUpdater.finish(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                            taskCardTitle, TaskCards.TaskStatus.CANCELLED,
                            // detail 三态文案是产品文案
                            activeRun.cancelMode() == CancelMode.CLOSE
                                    ? "本次任务已停止，当前会话已经关闭。"
                                    : isCompacting
                                            ? "整理已停止，当前 CLI 会话没有改变。"
                                            : "本次任务已停止。你可以继续在当前话题里提问。",
                            progress.snapshot(), null, null, null, null)));
                    // 有协作来源时通知 bot 身份，否则通知消息发送者
                    Notifications.sendResultNotification(bot, msg.messageId(),
                            senderRuntime != null
                                    ? senderRuntime.identity()
                                    : new BotIdentity(msg.senderOpenId(), ""),
                            "任务已停止，请查看上方状态。",
                            hasThread);
                    return;
                }
                String message = error.getMessage();
                LOGGER.error("[CLI] 执行失败: {}", message);
                cardUpdater.finish(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                        taskCardTitle, TaskCards.TaskStatus.FAILED,
                        isCompacting ? "上下文整理失败，当前 CLI 会话没有改变。"
                                : "执行没有完成。你可以调整指令后，在当前话题里重试。",
                        progress.snapshot(), null, null, message, null)));
                Notifications.sendResultNotification(bot, msg.messageId(),
                        senderRuntime != null
                                ? senderRuntime.identity()
                                : new BotIdentity(msg.senderOpenId(), ""),
                        "任务执行失败，请查看上方错误信息。",
                        hasThread);
            } finally {
                // ---- finally：停心跳 + 条件 delete + markSessionIdle 容错 ----
                progressHeartbeat.stop();
                removeActiveRun(session.id(), run);
                try {
                    SessionView.markSessionIdle(runtime.sessions(), session.id());
                } catch (IOException | RuntimeException idleError) {
                    LOGGER.error("[会话] 保存空闲状态失败: {}", idleError.getMessage());
                }
                updateExecutor.shutdownNow();
            }
        } catch (Throwable error) {
            // 兜底：主体/catch/finally 抛的异常统一落这里。取消走 token 不走线程中断，
            // 链路上没有 InterruptedException，不用恢复中断标志
            LOGGER.error("[任务] 回传或收尾失败: {}", error.getMessage());
        }
    }

    /**
     * taskText 三选一（澄清答案 / 协作提示词 / 原文）+ 附件段。
     */
    static String composeTaskText(Clarification.ClarificationFlow pendingClarification,
            Collaboration.CollaborationMessage collaboration, CliRequest cliRequest,
            String resolved, List<String> downloadedFiles) {
        String taskText;
        if (pendingClarification != null) {
            taskText = Clarification.formatClarificationMessage(pendingClarification,
                    cliRequest != null ? cliRequest.prompt() : resolved);
        } else if (collaboration != null) {
            taskText = Collaboration.buildCollaborationPrompt(collaboration);
        } else {
            taskText = cliRequest != null ? cliRequest.prompt() : resolved;
        }
        if (!downloadedFiles.isEmpty()) {
            taskText += "\n\n【用户上传的附件/图片】\n"
                    + downloadedFiles.stream().map(p -> "- " + p)
                            .collect(Collectors.joining("\n"))
                    + "\n请直接从上述本地路径读取并分析。";
        }
        return taskText;
    }

    /** 任务卡初始 detail；compact 且 Codex 带 instructions 时有专门文案。 */
    private static String initialDetail(boolean isCompacting, CliAdapter cliAdapter,
            SlashCommand command) {
        if (!isCompacting) {
            return "正在理解任务";
        }
        String instructions = command instanceof SlashCommand.CompactCommand compact
                ? compact.instructions()
                : null;
        if (cliAdapter.id() == CliId.CODEX && instructions != null) {
            return "Codex 正在使用原生默认策略整理上下文";
        }
        return "正在调用 " + cliAdapter.displayName() + " 原生上下文整理";
    }

    /** 调试行：mentions 拼成 key=name(openId)，空回落 (无)。 */
    private static String mentionsLine(List<Mention> mentions) {
        String joined = mentions.stream()
                .map(m -> m.key() + "=" + m.name() + "(" + m.openId() + ")")
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? "(无)" : joined;
    }

    /** null/空串均视为缺失。 */
    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }

    /** 只删自己的 run：token 引用相等才删，别误清别人新注册的。 */
    private void removeActiveRun(String sessionId, CancellationToken run) {
        ActiveRun current = runtime.activeRuns().get(sessionId);
        if (current != null && current.token() == run) {
            runtime.activeRuns().remove(sessionId);
        }
    }

    /** 只有这三类事件参与进度聚合（包私有供直测）。 */
    static boolean isProgressEvent(CliEvent event) {
        return event instanceof CliEvent.ToolStartEvent
                || event instanceof CliEvent.ToolEndEvent
                || event instanceof CliEvent.ContextEvent;
    }

    /** 把 compact 结果对齐成统一执行结果形状：message null 补空串，stats/toolCalls 置 null。 */
    private static CliRunResult compactRunResult(NativeCompact.Result result) {
        return new CliRunResult(result.message() == null ? "" : result.message(),
                result.sessionId(), null, null);
    }

    /**
     * 固定间隔心跳：scheduler 无 fixed-rate，靠任务尾部自重排实现；stop() 幂等。
     */
    static final class ProgressHeartbeat {
        private final CardScheduler scheduler;
        private final Runnable render;
        private final long intervalMs;
        private volatile boolean stopped;
        private volatile CardScheduler.Cancellable pending;

        ProgressHeartbeat(CardScheduler scheduler, Runnable render, long intervalMs) {
            this.scheduler = scheduler;
            this.render = render;
            this.intervalMs = intervalMs;
        }

        void start() {
            scheduleNext();
        }

        /** 幂等停止：置标志 + 取消已挂起的下一次调度。 */
        void stop() {
            stopped = true;
            CardScheduler.Cancellable current = pending;
            if (current != null) {
                current.cancel();
            }
        }

        private void scheduleNext() {
            if (!stopped) {
                pending = scheduler.schedule(this::tick, intervalMs);
            }
        }

        private void tick() {
            if (stopped) {
                return;
            }
            render.run();
            // 渲染期间可能已 stop()，重排前再查一次标志
            scheduleNext();
        }
    }
}

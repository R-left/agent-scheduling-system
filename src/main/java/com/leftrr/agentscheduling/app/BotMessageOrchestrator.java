package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.NativeCompact;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.CancelMode;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.CliRequest;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.CommandParser;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.ResolvedSession;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.SlashCommand;
import com.leftrr.agentscheduling.core.TaskProgressSnapshot;
import com.leftrr.agentscheduling.core.TaskProgressTracker;
import com.leftrr.agentscheduling.core.TopicAddress;
import com.leftrr.agentscheduling.core.TopicTask;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.CardScheduler;
import com.leftrr.agentscheduling.im.CardText;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.ExtractedResource;
import com.leftrr.agentscheduling.im.IncomingMessage;
import com.leftrr.agentscheduling.im.Mention;
import com.leftrr.agentscheduling.im.MessageParser;
import com.leftrr.agentscheduling.im.ProductSpecCards;
import com.leftrr.agentscheduling.im.ScheduledCardScheduler;
import com.leftrr.agentscheduling.im.SessionCards;
import com.leftrr.agentscheduling.im.TaskCards;
import com.leftrr.agentscheduling.im.ThrottledCardUpdater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * onMessage 主流程编排。对齐 TS 版 src/index.ts L150-766 startConfiguredBot 内的
 * onMessage 闭包；M7 Task 2 实现前半（L151-377：协作识别 → 任务卡发送），
 * Task 3 实现后半（L379-765：进度心跳、执行与收尾链三分支）。
 *
 * <p>Task 3 结构申报：TS {@code void execution.then().catch().finally().catch()} 收尾链
 * 按裁决 6 直译为嵌套 try/catch/finally 模板（then/catch/finally 抛出的异常统一落外层
 * 兜底 catch）；TS 分支内 {@code return} 落 Java 裸 {@code return}（finally 照常执行、
 * 不触发外层兜底，语义等价）；执行体（executeCli/compactCliSession）阻塞当前线程——
 * 按 R11/裁决 5，Task 5 将每条消息投递到独立虚拟线程，同步阻塞即 TS 后台化 Promise 的
 * 等价表达（事件回调由 CliRunner 读循环线程消费，回调尽快返回的语义不变）。
 * 心跳（裁决 7）与 ThrottledCardUpdater（M4 备案组装义务）见方法内注释。
 * adapter 解析经包私有构造注入缝（ClarificationRunner 先例），生产路径按注册表解析。</p>
 *
 * <p>闭包捕获集（构造注入）：TS 闭包捕获 config、bot（方法参）、runtime（内含
 * sessions/teamRegistry/activeRuns/contextWindows/botRuntimes/processedCollaborationTurns/
 * collaborationInbox/clarificationFlows/productSpecFlows 九分量）、collaborationService
 * （后半 dispatch 用，本任务先注入）、agentOsConfig.defaultProductDeliveryMode。
 * 裁决 9：IncomingMessage 未实现 MessageAddress/TopicAddress 接口，两处手工构造。
 * 裁决 11：协作识别正则 {@code /任务编号：([a-f0-9]{12})/} 全角冒号逐字；
 * botRuntimes 注册竞态窗口期消息被忽略 = TS 对齐行为。</p>
 *
 * <p>TS→Java 差异（裁决 5 与申报项）：</p>
 * <ul>
 * <li>受检异常：本方法签名 {@code throws IOException/InterruptedException}（会话读写等
 *     await reject 对位）；虚拟线程投递 + catch-all 包装（文案对齐收尾链兜底
 *     {@code [任务] 回传或收尾失败:}）放 Task 5 组装 BotOptions.MessageListener 时。</li>
 * <li>TS 无值 {@code return} → Java void 方法裸 {@code return}（无调用方消费返回值，
 *     不引入布尔返回）。</li>
 * <li>日志：TS console.log/error/warn → SLF4J info/error/warn（Task 0 裁决 2）。
 *     插值差异备案：TS 模板串对 nullish 打印 {@code undefined}，SLF4J {@code {}}
 *     打印 {@code null}（repo 惯例 TS undefined → null，如 [收到] 的 threadId/rootId）。</li>
 * <li>{@code activeRuns.get(id)?.controller === run} 的 AbortController 同一性比较 →
 *     CancellationToken 引用相等；回滚双分支（replyCard 抛错 / 无 message_id）共用
 *     条件删除 + markSessionIdle。</li>
 * </ul>
 */
public final class BotMessageOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotMessageOrchestrator.class);

    /** 对齐 TS /任务编号：([a-f0-9]{12})/：全角冒号逐字（裁决 11），String.match → Matcher.find。 */
    private static final Pattern DISPATCH_ID_PATTERN = Pattern.compile("任务编号：([a-f0-9]{12})");

    /** TS setInterval(renderProgress, 1_000) 的 1s 心跳间隔（裁决 7）。 */
    static final long PROGRESS_HEARTBEAT_INTERVAL_MS = 1_000;

    private final AppRuntime runtime;
    private final BotRegistry.BotConfig config;
    private final CollaborationService collaborationService;
    private final ProductDeliveryMode defaultProductDeliveryMode;

    public BotMessageOrchestrator(AppRuntime runtime, BotRegistry.BotConfig config,
            CollaborationService collaborationService,
            ProductDeliveryMode defaultProductDeliveryMode) {
        this(runtime, config, collaborationService, defaultProductDeliveryMode,
                CliAdapterRegistry::get);
    }

    /** 包私有重载：adapter 解析函数可注入（ClarificationRunner 先例），生产路径按注册表解析。 */
    BotMessageOrchestrator(AppRuntime runtime, BotRegistry.BotConfig config,
            CollaborationService collaborationService,
            ProductDeliveryMode defaultProductDeliveryMode,
            Function<CliId, CliAdapter> adapterResolver) {
        this.runtime = runtime;
        this.config = config;
        this.collaborationService = collaborationService;
        this.defaultProductDeliveryMode = defaultProductDeliveryMode;
        this.adapterResolver = adapterResolver;
    }

    private final Function<CliId, CliAdapter> adapterResolver;

    /** TS onMessage 闭包主体（index.ts:150-766）。 */
    public void onMessage(IncomingMessage msg, Bot bot) throws IOException, InterruptedException {
        onMessage(msg, bot, adapterResolver);
    }

    /** 主体控制流；adapterResolver 供测试替身替换（M7 Task 3 执行段起消费）。 */
    void onMessage(IncomingMessage msg, Bot bot, Function<CliId, CliAdapter> adapterResolver)
            throws IOException, InterruptedException {
        // L151-152
        String resolved = MessageParser.resolveMentions(msg.text(), msg.mentions());
        String taskId = TopicTask.topicTaskId(new TopicAddress(
                msg.messageId(), msg.chatId(), msg.threadId(), msg.rootId()));

        // L153-186 bot→bot 协作识别
        BotRuntime senderRuntime = null; // 后半 catch 分支通知目标用（Task 3），TS 初始 undefined
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
            // Task 2 审查 Minor 1 修复：contains→add 两步改原子 add。TS 侧 check→add 位于
            // 同一同步段（单事件循环）天然原子；Java 每消息一虚拟线程，并发重复协作消息
            // 在两步间可双通过，CHM keySet 的 add 原子性对齐 TS 同段语义（false = 已存在）
            if (!runtime.processedCollaborationTurns().add(turnKey)) {
                LOGGER.info("[协作] 忽略重复消息 {}", turnKey);
                return;
            }
            collaboration = pending;
        }

        // L187-197
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

        // L198-203
        Clarification.ClarificationFlow pendingClarification =
                !"app".equals(msg.senderType()) && !"bot".equals(msg.senderType()) && command == null
                        ? runtime.clarificationFlows().findForTask(taskId, config.id())
                        : null;

        // L204-214 会话解析（裁决 9：手工构造 MessageAddress）
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

        // L215-216
        CliAdapter cliAdapter = adapterResolver.apply(session.cliId());
        boolean isCompacting = command instanceof SlashCommand.CompactCommand;

        // L217-235 图片/文件下载（单文件失败不中断整体）
        List<ExtractedResource> resources =
                MessageParser.extractResourceKeys(msg.messageType(), msg.rawContent());
        List<String> downloadedFiles = new ArrayList<>();
        for (ExtractedResource res : resources) {
            try {
                String savePath = bot.downloadResource(msg.messageId(), res.key(), res.type().id(),
                        Path.of("data", "downloads").toString(), res.fileName());
                String absPath = Path.of(savePath).toAbsolutePath().normalize().toString();
                downloadedFiles.add(absPath);
                LOGGER.info("  [下载] {} → {}", res.type().id(), absPath);
            } catch (IOException | RuntimeException e) {
                // 终审 Major-1：生产实现 LarkBot.downloadResource → OapiLarkApi.getResource
                // 对 REST 失败抛 LarkException（RuntimeException 子类，对齐 TS Error）——
                // 只接 IOException 会让真实下载失败（token 过期/网络错误/资源过期）击穿
                // 「单文件失败不中断整体」（TS index.ts L232 下载循环为 catch-all，仅记
                // 日志）。IOException 保留落盘失败语义，RuntimeException 面覆盖
                // LarkException 与其他非受检下载失败。
                LOGGER.error("  [下载失败] {}: {}", res.key(), e.getMessage());
            }
        }

        // L237-255 taskText 三选一 + 附件段 + buildBotPrompt
        String taskText = composeTaskText(pendingClarification, collaboration, cliRequest,
                resolved, downloadedFiles);
        String prompt = BotRegistry.buildBotPrompt(config, taskText,
                runtime.teamRegistry().contextFor(config.id()), defaultProductDeliveryMode);
        // L256-258
        String taskCardTitle = isCompacting ? "整理上下文" : cliAdapter.displayName();

        // L259-269 调试日志 5 条
        LOGGER.info("[收到] chat={} threadId={} rootId={} sender={}",
                msg.chatId(), msg.threadId(), msg.rootId(), msg.senderOpenId());
        LOGGER.info("  原文: {}", msg.text());
        LOGGER.info("  还原: {}", resolved);
        LOGGER.info("  mentions: {}", mentionsLine(msg.mentions()));
        LOGGER.info("  [会话] {} id={} status={}",
                isNew ? "新建" : "复用", session.id(), session.status().id());

        // L271-283 命令分流
        CommandHandler.CommandOutcome commandOutcome = CommandHandler.handleSessionCommand(
                new CommandHandler.HandleSessionCommandOptions(
                        runtime, config, msg, bot, session, cliAdapter, command,
                        cliRequest, isNew, hasThread));
        if (commandOutcome == CommandHandler.CommandOutcome.HANDLED) {
            return;
        }

        // L285-308 三个状态守卫
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

        // L310-325 旧澄清作废
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

        // L327-336 协作工作区切换（session 重新赋值，后半闭包用新值）
        if (collaboration != null
                && !session.workspaceDir().equals(collaboration.workspaceDir())) {
            Workspace.ensureWorkspaceDirectory(collaboration.workspaceDir());
            session = runtime.sessions().setWorkspaceDir(session.id(), collaboration.workspaceDir());
        }

        // L338-344 进入执行
        runtime.sessions().transition(session.id(), SessionStatus.ACTIVE);
        CancellationToken run = new CancellationToken();
        ActiveRun activeRun = new ActiveRun(run,
                collaboration != null ? collaboration.ownerOpenId() : msg.senderOpenId());
        runtime.activeRuns().put(session.id(), activeRun);

        // L346-377 先回复一张卡片，让用户知道任务已经进入执行队列。
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

        // ================================================================
        // M7 Task 3：进度心跳 + 执行 + 收尾三分支（index.ts L379-765）
        // ================================================================

        // session 在 L327-336 协作工作区切换后不再重新赋值；Java lambda 只捕 effectively
        // final 局部变量，此处取最终快照（即 TS let 绑定在闭包执行期的同一取值）
        Session execSession = session;

        // L379-385 进度跟踪器。Date.now → System::currentTimeMillis（申报：tracker 仅以
        // 差值计耗时，wall clock 直译即等价）；`!session.cliSessionId` 按 TS truthiness
        // 落 isPresent（null/空串都算新会话）。
        TaskProgressTracker progress = new TaskProgressTracker(System::currentTimeMillis,
                runtime.contextWindows().get(execSession.id()),
                !isPresent(execSession.cliSessionId()));
        // M4 备案组装义务：ThrottledCardUpdater 必须配 ScheduledCardScheduler 进程级单例
        // 与单线程 updateExecutor（串行化）；updateExecutor per-task 新建、finally 收尾
        // （ClarificationRunner 同构先例）。
        ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
        ThrottledCardUpdater cardUpdater = new ThrottledCardUpdater(
                card -> bot.updateCard(cardId, card),
                ScheduledCardScheduler.getInstance(), updateExecutor, 1_000);
        // L386-396 renderProgress：compact 心跳 detail 固定文案且不推 progress 字段，
        // 非 compact detail 取快照 current 并推 progress 字段（L396 条件展开）。
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
        // L397-398 setInterval(renderProgress, 1_000) + unref() → 裁决 7：ScheduledCardScheduler
        // 无 fixed-rate，以 self-rescheduling 表达；daemon 调度线程 = unref 等价。
        ProgressHeartbeat progressHeartbeat = new ProgressHeartbeat(
                ScheduledCardScheduler.getInstance(), renderProgress,
                PROGRESS_HEARTBEAT_INTERVAL_MS);
        progressHeartbeat.start();

        // L427-434 事件过滤：非 {tool_start, tool_end, context} 不 accept，accept 后推卡。
        // 产品方案重试路径（分支二）复用同一消费者（TS 同款内联过滤逐字对应）。
        Consumer<CliEvent> onProgressEvent = event -> {
            if (!isProgressEvent(event)) {
                return;
            }
            progress.accept(event);
            renderProgress.run();
        };

        // L404-765 执行 + 收尾链（裁决 6 嵌套模板）。TS `void execution.then(...)` 的后台化
        // 在 Java 落为每消息虚拟线程上的同步阻塞（裁决 5：Task 5 负责投递）；事件回调由
        // CliRunner 读循环线程消费后尽快返回，"CLI 在后台继续执行"的语义不变。
        // 模板形状：try { try { then 主体（三分支 return 自然落入 finally） }
        //   catch { 取消/失败 }  finally { clearInterval + 条件 delete + markSessionIdle 容错 } }
        // catch { 兜底 log }——then/catch/finally 抛出的异常统一落外层兜底。
        try {
            try {
                // L404-434 执行体：compact 走 NativeCompact（结果映射裁决 8），否则 executeCli
                CliRunResult result = isCompacting
                        ? compactRunResult(NativeCompact.compactCliSession(
                                new NativeCompact.Options(
                                        cliAdapter,
                                        // 裁决 8：对齐 TS `session.cliSessionId!` 非空断言
                                        // （compact 必已有 CLI 会话，不可达即崩溃语义对齐）
                                        Objects.requireNonNull(session.cliSessionId()),
                                        session.workspaceDir(),
                                        command instanceof SlashCommand.CompactCommand compactCommand
                                                ? compactCommand.instructions()
                                                : null,
                                        run,
                                        null)))
                        : AppExecutions.executeCli(cliAdapter, prompt, session.workspaceDir(),
                                session.cliSessionId(), run, onProgressEvent);

                // ---- then 前置（L437-444）：clearInterval + 会话回写（均 !isCompacting；
                //      contextWindowTokens 真值判断（0 不回写）沿裁决 12 口径）----
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

                // ---- 分支一：澄清卡收尾（L445-480）----
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

                // ---- 分支二：产品方案收尾 + dispatch 合法性校验（L482-585）----
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
                                            // TS `resultSessionId ?? session.cliSessionId`：
                                            // ?? 只回落 null/undefined（空串不回落）
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
                    // L523-546 dispatch 合法性校验（四处 throw 文案逐字）
                    // TS 读 agentOsConfig.teamLeaderId——TeamRegistry 即以该值构造（L73），
                    // Java 侧经 runtime.teamRegistry().leaderBotId() 取同一真值（申报）
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
                        // TS `deliveryMode === 'local'`：sealed 两态即两交付模式
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

                // ---- 分支三：正常完成 + 派发/协作续转（L586-701）----
                TaskProgressSnapshot snapshot = progress.snapshot();
                cardUpdater.finish(isCompacting
                        ? SessionCards.buildSessionNoticeCard(
                                new SessionCards.SessionNoticeCardOptions(
                                        isPresent(finalResult.answer())
                                                ? "暂时无需整理" : "上下文已整理",
                                        // TS `answer || [两段].join('\n\n')`：空串为 falsy 走兜底
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
                // 注意用原始 result 而非 finalResult（TS L615 同款）
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
                                // targetName = botRuntimes.get(target)?.identity.name ?? targetBotId
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
                                // 回派 reportToBotId：instruction 三段 join('\n\n') 文案逐字
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
                // ---- catch（L703-751）：先 clearInterval，再按 token 取消状态分岔 ----
                progressHeartbeat.stop();
                if (run.isCancelled()) {
                    LOGGER.info("[CLI] 任务已取消");
                    cardUpdater.finish(TaskCards.buildTaskCard(new TaskCards.TaskCardOptions(
                            taskCardTitle, TaskCards.TaskStatus.CANCELLED,
                            // cancelMode 三态 detail 文案逐字
                            activeRun.cancelMode() == CancelMode.CLOSE
                                    ? "本次任务已停止，当前会话已经关闭。"
                                    : isCompacting
                                            ? "整理已停止，当前 CLI 会话没有改变。"
                                            : "本次任务已停止。你可以继续在当前话题里提问。",
                            progress.snapshot(), null, null, null, null)));
                    // 协作来源 bot 时通知的是 bot 身份（senderRuntime），否则消息发送者
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
                // ---- finally（L752-762）：clearInterval + 条件 delete + markSessionIdle 容错 ----
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
            // 兜底 catch（L763-765）：then/catch/finally 抛出的异常统一落这里
            // （终审 Minor-4 备案：本代码库取消走 token 而非线程中断，该链路无
            // InterruptedException 可达源，故不恢复中断状态）
            LOGGER.error("[任务] 回传或收尾失败: {}", error.getMessage());
        }
    }

    /**
     * TS L237-249 taskText 三选一 + 附件段。包私有供测试直测三选一分支与附件段文案
     * （前半不落任何下游，编排内不可观测）。
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

    /** TS L353-358 任务卡初始 detail：compact 两态（Codex 有 instructions 走默认策略文案）。 */
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

    /** TS `mentions.map(...).join(', ') || '(无)'`。 */
    private static String mentionsLine(List<Mention> mentions) {
        String joined = mentions.stream()
                .map(m -> m.key() + "=" + m.name() + "(" + m.openId() + ")")
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? "(无)" : joined;
    }

    /** TS !!string：null/空串为假。 */
    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }

    /** TS `activeRuns.get(id)?.controller === run` 才 delete（两处回滚共用）。 */
    private void removeActiveRun(String sessionId, CancellationToken run) {
        ActiveRun current = runtime.activeRuns().get(sessionId);
        if (current != null && current.token() == run) {
            runtime.activeRuns().remove(sessionId);
        }
    }

    /** L427-434 事件过滤谓词：仅 {tool_start, tool_end, context} 参与进度聚合（包私有供直测）。 */
    static boolean isProgressEvent(CliEvent event) {
        return event instanceof CliEvent.ToolStartEvent
                || event instanceof CliEvent.ToolEndEvent
                || event instanceof CliEvent.ContextEvent;
    }

    /**
     * L405-417 compact 结果映射成统一形状（裁决 8）：message null → {@code ""}（TS
     * {@code result.message ?? ''}）、stats/toolCalls 置 null（TS undefined）。
     */
    private static CliRunResult compactRunResult(NativeCompact.Result result) {
        return new CliRunResult(result.message() == null ? "" : result.message(),
                result.sessionId(), null, null);
    }

    /**
     * 进度心跳（裁决 7）：ScheduledCardScheduler 无 fixed-rate，以 self-rescheduling 表达
     * TS {@code setInterval(renderProgress, 1_000)}——task 尾部重排 schedule(1s)；
     * {@code stop()} = clearInterval 等价（volatile 停止标志 + cancel 挂起句柄，幂等）。
     * 调度线程为 ScheduledCardScheduler 的 daemon 单例线程，对齐 TS {@code unref()}。
     * 包私有供测试以手动调度器钉住自重排/停止机制。
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

        /** clearInterval 等价：置停止标志 + 取消挂起中的下一次调度。幂等。 */
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
            // 渲染期间 stop() 也可能到来：再查一次标志，杜绝停止后继续自重排
            scheduleNext();
        }
    }
}

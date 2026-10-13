package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.CliRequest;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.CommandParser;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ResolvedSession;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.SlashCommand;
import com.leftrr.agentscheduling.core.TopicAddress;
import com.leftrr.agentscheduling.core.TopicTask;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.ExtractedResource;
import com.leftrr.agentscheduling.im.IncomingMessage;
import com.leftrr.agentscheduling.im.Mention;
import com.leftrr.agentscheduling.im.MessageParser;
import com.leftrr.agentscheduling.im.TaskCards;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * onMessage 主流程编排。对齐 TS 版 src/index.ts L150-766 startConfiguredBot 内的
 * onMessage 闭包；M7 Task 2 实现前半（L151-377：协作识别 → 任务卡发送），
 * Task 3 在本方法尾部续写后半（L379-765：进度心跳、执行与收尾链）。
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

    private final AppRuntime runtime;
    private final BotRegistry.BotConfig config;
    private final CollaborationService collaborationService;
    private final ProductDeliveryMode defaultProductDeliveryMode;

    public BotMessageOrchestrator(AppRuntime runtime, BotRegistry.BotConfig config,
            CollaborationService collaborationService,
            ProductDeliveryMode defaultProductDeliveryMode) {
        this.runtime = runtime;
        this.config = config;
        this.collaborationService = collaborationService;
        this.defaultProductDeliveryMode = defaultProductDeliveryMode;
    }

    /** TS onMessage 闭包主体（index.ts:150-766）。 */
    public void onMessage(IncomingMessage msg, Bot bot) throws IOException, InterruptedException {
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
            if (runtime.processedCollaborationTurns().contains(turnKey)) {
                LOGGER.info("[协作] 忽略重复消息 {}", turnKey);
                return;
            }
            runtime.processedCollaborationTurns().add(turnKey);
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
        CliAdapter cliAdapter = CliAdapterRegistry.get(session.cliId());
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
            } catch (IOException e) {
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

        // M7 Task 3：进度心跳 + 执行 + 收尾三分支（index.ts L379-765）由此续写；
        // prompt / collaboration / senderRuntime / taskId / isCompacting / cliAdapter
        // / downloadedFiles 等前置变量均已就绪。
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
}

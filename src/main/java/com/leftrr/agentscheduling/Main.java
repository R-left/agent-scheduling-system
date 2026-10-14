package com.leftrr.agentscheduling;

import com.leftrr.agentscheduling.app.AppRuntime;
import com.leftrr.agentscheduling.app.BotMessageOrchestrator;
import com.leftrr.agentscheduling.dto.app.BotRuntime;
import com.leftrr.agentscheduling.app.CollaborationService;
import com.leftrr.agentscheduling.app.DocumentCommentScheduler;
import com.leftrr.agentscheduling.app.CardActionHandler;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.core.AppPaths;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.JsonProductSpecFlowStore;
import com.leftrr.agentscheduling.core.JsonSessionStore;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.dto.im.BotIdentity;
import com.leftrr.agentscheduling.im.BotOptions;
import com.leftrr.agentscheduling.im.FeishuBots;
import com.leftrr.agentscheduling.mcp.HttpAppToolsServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 入口：装配全部 bot 并并行启动，onMessage 主流程在 {@link BotMessageOrchestrator}。
 *
 * <p>bot 配置固定读 {@code ~/.agentos/bots.json}（首次启动缺文件时自动生成模板并退出，
 * workspace 相对路径按 ~/.agentos 解析）；持久化落 {@code ~/.agentos/data/}
 * （会话、产品流程、附件下载）——配置与数据都在仓库与 CWD 之外，fat jar 从任意目录
 * 启动行为一致。凭证缺一即启动失败，没有降级运行形态。</p>
 *
 * <p>进程保活：所有相关线程都是 daemon，Main 线程跑完进程就退出——
 * 所以用 {@code new CountDownLatch(1).await()} 把 Main 线程永久阻塞，
 * 靠飞书 WS 长连接线程维持常驻。没有优雅停机，终止就是硬杀。</p>
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Bootstrap context = initialize();
        startMcpServer();
        startAllBots(context);
        keepAlive();
    }

    /**
     * 初始化信息上下文
     *
     * @return
     * @throws IOException
     */
    static Bootstrap initialize() throws IOException {
        AppPaths.ensureDataDirectory();
        BotRegistry.AgentOsConfig agentOsConfig = BotRegistry.loadAgentOsConfig();
        List<BotRegistry.BotConfig> botConfigs = agentOsConfig.bots();
        // TeamRegistry 必须与 agentOsConfig 用同一个 teamLeaderId 构造，
        // orchestrator 的 leader 判定才和配置同源
        TeamRegistry teamRegistry = new TeamRegistry(agentOsConfig.teamLeaderId(), botConfigs);
        // 工作目录只校验不创建，第一个失败就整体失败
        for (BotRegistry.BotConfig config : botConfigs) {
            Workspace.ensureWorkspaceDirectory(config.workspaceDir());
        }
        for (TeamRegistry.MissingSkill missing : teamRegistry.findMissingSkills()) {
            LOGGER.warn("[Skill] bot={} 找不到 {}，请安装到工作区或用户级 Skills 目录",
                    missing.botId(), missing.skill());
        }
        Map<String, String> defaultWorkspaces = new LinkedHashMap<>();
        for (BotRegistry.BotConfig config : botConfigs) {
            defaultWorkspaces.put(config.id(), config.workspaceDir());
        }
        // 旧格式迁移的 botId 取第一个 bot（bots 校验保证至少一个，不会越界）
        SessionManager sessions = SessionManager.open(new JsonSessionStore(
                AppPaths.sessionsFile(),
                botConfigs.get(0).id(),
                defaultWorkspaces));
        // 内存态容器统一并发形态；productSpecFlows 用持久化实现
        AppRuntime runtime = new AppRuntime(
                sessions,
                teamRegistry,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                ConcurrentHashMap.newKeySet(),
                new Collaboration.CollaborationInbox(),
                new Clarification.ClarificationFlowStore(),
                new JsonProductSpecFlowStore(AppPaths.productSpecFlowsFile()));
        CollaborationService collaborationService = new CollaborationService(runtime);
        // DocumentCommentScheduler 必须全局单实例：防重集和串行队列跨 bot 共享，
        // per-bot 各建会把防重作用域割裂
        DocumentCommentScheduler documentCommentScheduler = new DocumentCommentScheduler(runtime);

        // bot id 大写必须用 Locale.ROOT：配置 id 受 [a-z0-9_-] 约束，转换不能依赖 host locale
        LOGGER.info("Agent OS 启动，正在建立飞书长连接…");
        LOGGER.info("[配置] 已注册 {} 个 bot，Team Leader={}，已恢复 {} 个会话",
                botConfigs.size(), teamRegistry.leaderBotId(), sessions.size());
        for (CliAdapter adapter : CliAdapterRegistry.list()) {
            LOGGER.info("[CLI] id={} command={}", adapter.id().id(), adapter.command());
        }
        for (BotRegistry.BotConfig config : botConfigs) {
            LOGGER.info("[Bot {}] default_cli={} workspace={}",
                    config.id().toUpperCase(Locale.ROOT),
                    config.defaultCliId().id(), config.workspaceDir());
        }
        return new Bootstrap(agentOsConfig, teamRegistry, sessions, runtime,
                collaborationService, documentCommentScheduler);
    }

    /**
     * 启动内置McpServer
     */
    static void startMcpServer() {
        HttpAppToolsServer appToolsServer = HttpAppToolsServer.start();
        // 停机钩子,关闭tomcat服务
        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().unstarted(appToolsServer::stop));
        LOGGER.info("[MCP] agent_scheduling 工具端点 {}（内嵌 HTTP，长驻）", appToolsServer.url());
    }

    /**
     * 每 bot 并行启动（identity REST 调用互不等待），收齐全部结果后抛首个失败。
     */
    static void startAllBots(Bootstrap context) throws InterruptedException {
        List<BotRegistry.BotConfig> botConfigs = context.agentOsConfig().bots();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (BotRegistry.BotConfig config : botConfigs) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    startConfiguredBot(context, config);
                } catch (Throwable error) {
                    failures.add(error);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            if (first instanceof RuntimeException runtimeError) {
                throw runtimeError;
            }
            throw new RuntimeException(first);
        }
    }

    /**
     * 启动飞书机器人长连接
     *
     * @param context 连接上下文
     * @param config
     */
    static void startConfiguredBot(Bootstrap context, BotRegistry.BotConfig config) {
        BotMessageOrchestrator orchestrator = new BotMessageOrchestrator(
                context.runtime(), config, context.collaborationService(),
                context.agentOsConfig().defaultProductDeliveryMode());
        BotOptions options = new BotOptions(
                config.appId(),
                config.appSecret(),
                // 虚拟线程处理消息回调
                (msg, bot) -> Thread.ofVirtual().start(() -> {
                    try {
                        orchestrator.onMessage(msg, bot);
                    } catch (Throwable error) {
                        LOGGER.error("[任务] 回传或收尾失败: {}", error.getMessage());
                    }
                }),
                CardActionHandler.createCardActionHandler(
                        new CardActionHandler.CardActionHandlerOptions(
                                context.runtime(), config, context.collaborationService(),
                                context.agentOsConfig().defaultProductDeliveryMode())),
                // 只给 lark-drive 挂评论回调；下面的 subscribeToDocumentComments
                // 是第二个条件，两处缺一不可
                config.skills().contains("lark-drive")
                        ? (comment, bot) -> context.documentCommentScheduler()
                                .scheduleDocumentComment(config, bot, comment)
                        : null);
        Bot startedBot = FeishuBots.start(options);
        BotIdentity identity = startedBot.getIdentity();
        context.runtime().botRuntimes().put(config.id(),
                new BotRuntime(config, startedBot, identity));
        if (config.skills().contains("lark-drive")) {
            startedBot.subscribeToDocumentComments();
        }
        LOGGER.info("[Bot {}] 已连接 name={} open_id={}",
                config.id().toUpperCase(Locale.ROOT), identity.name(), identity.openId());
    }

    /** 进程保活（机制见类注释）；Main 线程永久阻塞。 */
    static void keepAlive() throws InterruptedException {
        new CountDownLatch(1).await();
    }

    record Bootstrap(
            BotRegistry.AgentOsConfig agentOsConfig,
            TeamRegistry teamRegistry,
            SessionManager sessions,
            AppRuntime runtime,
            CollaborationService collaborationService,
            DocumentCommentScheduler documentCommentScheduler) {}
}

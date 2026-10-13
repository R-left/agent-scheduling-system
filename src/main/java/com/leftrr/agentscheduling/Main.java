package com.leftrr.agentscheduling;

import com.leftrr.agentscheduling.app.AppRuntime;
import com.leftrr.agentscheduling.app.BotMessageOrchestrator;
import com.leftrr.agentscheduling.app.BotRuntime;
import com.leftrr.agentscheduling.app.CollaborationService;
import com.leftrr.agentscheduling.app.DocumentCommentScheduler;
import com.leftrr.agentscheduling.app.CardActionHandler;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.JsonProductSpecFlowStore;
import com.leftrr.agentscheduling.core.JsonSessionStore;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.BotOptions;
import com.leftrr.agentscheduling.im.FeishuBots;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Agent OS Java 版入口。对齐 TS 版 src/index.ts 的模块顶层初始化序列（L68-128）与
 * startConfiguredBot 装配（L130-149 + L768-777）+ 全 bot 并行启动（L868-870）。
 * onMessage 闭包主体在 {@link BotMessageOrchestrator}（M7 Task 2/3），文档评论串行队列在
 * {@link DocumentCommentScheduler}（M7 Task 4）；本类只做装配与保活。
 *
 * <p><b>运行形态（研究 R8，累积裁决 c）</b>：进程 CWD 必须是 {@code agent-scheduling-system/}
 * 仓库根——{@code config/bots.json}、{@code data/sessions.json}、
 * {@code data/product-spec-flows.json}、{@code data/downloads} 与 {@code BOTS_CONFIG}
 * 覆盖值全部按 TS {@code join(...)} 语义相对 CWD 解析（本类 initialize 的 baseDirectory
 * 即 CWD，参数化仅为测试可注入）。从其他 CWD 以 {@code java -jar} 启动会把 data/ 落错
 * 位置、找不到 config/——仅支持仓库根 CWD 形态，不支持任意安装目录。</p>
 *
 * <p><b>进程保活（裁决 4，R2）</b>：TS 顶层 await 完成后事件循环靠飞书 WS 长连接与
 * 心跳存活；Java 全部相关线程皆 daemon（lark-ws daemon、ScheduledCardScheduler daemon、
 * 虚拟线程恒 daemon），Main 线程跑完即整进程退出——故以
 * {@code new CountDownLatch(1).await()} 让 Main 线程（唯一非 daemon 线程）永久阻塞，
 * 等价承载 TS 的常驻事件循环。无 graceful shutdown、无 signal handler，对齐 TS 全文
 * 无 {@code process.on}；终止即硬杀（研究 R3：Ctrl+C 孤儿子进程为对齐范围，不新增义务）。</p>
 *
 * <p><b>凭证口径（R4）</b>：appId/appSecret 经合并环境变量传入
 * {@link BotRegistry#loadAgentOsConfig}，缺任一即抛「bot 配置文件格式错误: bot xx 缺少
 * 环境变量 XX」启动失败——与 TS 顶层 await reject 进程崩同构，<b>无降级运行形态</b>；
 * 无凭证即无法启动，端到端验收跳过并向用户说明（研究 R4）。</p>
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = mergedEnvironment();
        Bootstrap context = initialize(Path.of("").toAbsolutePath().toString(), env);
        startAllBots(context);
        keepAlive();
    }

    /**
     * TS index.ts L68-128 初始化序列逐语句。baseDirectory 对齐 TS 各相对路径的
     * process.cwd() 基准（参数化仅为测试注入；生产传 CWD，见类 javadoc R8）。
     * baseDirectory 为 null 时回落 CWD（对齐 TS 默认参数 process.cwd()）——
     * data/ 两持久化路径同样挂 baseDirectory（修复轮 I-1：与 bots.json 一致，
     * 测试传 tempDir 即全隔离；生产 baseDirectory=CWD 时与 TS join('data',…)
     * 相对 CWD 语义不变）。
     */
    static Bootstrap initialize(String baseDirectory, Map<String, String> env) throws IOException {
        String base = baseDirectory != null
                ? baseDirectory
                : Path.of("").toAbsolutePath().toString();
        // L68-70 botConfigPath = resolve(process.env.BOTS_CONFIG ?? join('config','bots.json'))
        //（?? 只跳过 null：空串也覆盖——Path.resolve('') → cwd，Java Path.resolve('') 同构）
        String configuredPath = env.get("BOTS_CONFIG");
        String botConfigPath = resolveAgainstBase(
                configuredPath != null ? configuredPath : Path.of("config", "bots.json").toString(),
                base);
        // L71-73（Java loadAgentOsConfig 多 env/baseDirectory 两参，研究 §3.1）
        BotRegistry.AgentOsConfig agentOsConfig =
                BotRegistry.loadAgentOsConfig(botConfigPath, env, base);
        List<BotRegistry.BotConfig> botConfigs = agentOsConfig.bots();
        // Task 3 审查 Minor ㊃ 装配义务：与 agentOsConfig 同一 teamLeaderId 构造，
        // orchestrator 的 leader 判定（runtime.teamRegistry().leaderBotId()）才与
        // TS agentOsConfig.teamLeaderId 等价
        TeamRegistry teamRegistry = new TeamRegistry(agentOsConfig.teamLeaderId(), botConfigs);
        // L74-76 Promise.all 并行 ensureWorkspaceDirectory：首个失败即整体失败，
        // 顺序执行的失败点与可观测结果一致（ensure 只校验不创建，见 core/Workspace）
        for (BotRegistry.BotConfig config : botConfigs) {
            Workspace.ensureWorkspaceDirectory(config.workspaceDir());
        }
        // L77-81 missing skills warn 文案逐字
        for (TeamRegistry.MissingSkill missing : teamRegistry.findMissingSkills()) {
            LOGGER.warn("[Skill] bot={} 找不到 {}，请安装到工作区或用户级 Skills 目录",
                    missing.botId(), missing.skill());
        }
        // L82-84 defaultWorkspaces = Object.fromEntries(bots.map(c => [c.id, c.workspaceDir]))
        Map<String, String> defaultWorkspaces = new LinkedHashMap<>();
        for (BotRegistry.BotConfig config : botConfigs) {
            defaultWorkspaces.put(config.id(), config.workspaceDir());
        }
        // L85-91 JsonSessionStore 三参构造（旧格式迁移 botId 取第一个 bot、默认工作区表）；
        // bots 校验保证至少一个（bots 至少需要配置一个），botConfigs[0]?.id 的空表分支不可达。
        // data 路径挂 baseDirectory（修复轮 I-1，与 bots.json 同法；生产=CWD 语义不变）
        SessionManager sessions = SessionManager.open(new JsonSessionStore(
                resolveAgainstBase(Path.of("data", "sessions.json").toString(), base),
                botConfigs.get(0).id(),
                defaultWorkspaces));
        // L92-103 内存态容器：并发形态按 AppRuntime 装配口径（M6 裁决 2）落 CHM，
        // productSpecFlows 落 JsonProductSpecFlowStore（TS 持久化版，研究 §3.1）
        AppRuntime runtime = new AppRuntime(
                sessions,
                teamRegistry,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                ConcurrentHashMap.newKeySet(),
                new Collaboration.CollaborationInbox(),
                new Clarification.ClarificationFlowStore(),
                new JsonProductSpecFlowStore(resolveAgainstBase(
                        Path.of("data", "product-spec-flows.json").toString(), base)));
        // L115
        CollaborationService collaborationService = new CollaborationService(runtime);
        // Task 4 审查 Minor-5 装配义务：全局单实例——TS 防重集与串行队列是模块级状态
        // （index.ts L101-102），跨 bot 共享；按 bot 各建实例会把防重作用域割裂成 per-bot
        DocumentCommentScheduler documentCommentScheduler = new DocumentCommentScheduler(runtime);

        // L117-128 启动日志 5 条文案逐字（bot id 大写用 Locale.ROOT：配置 id 受
        // [a-z0-9_-] 约束，ROOT 对齐 JS toUpperCase 的非 host locale 行为）
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

    /** 生产入口：经 FeishuBots.start 真连飞书。 */
    static void startAllBots(Bootstrap context) throws InterruptedException {
        startAllBots(context, FeishuBots::start);
    }

    /**
     * TS L868-870 {@code await Promise.all(botConfigs.map(c => startConfiguredBot(c, ...)))}：
     * 每 bot 并行启动（identity REST 调用互不等待），全部完成后返回；任一失败即抛出
     * （对齐 Promise.all reject → TS 顶层 await reject 进程崩；Java 收齐全部结果后抛
     * 首个失败，startup 窗口内其余 bot 的启动副作用与 TS 尾部 Promise 的命运差异备案：
     * TS 先败即停等，Java 跑完才抛，窗口秒级、失败后果同为进程不进入保活段）。
     * starter 注入缝供装配接线测试用 fake 记录，不真连飞书。
     */
    static void startAllBots(Bootstrap context, BotStarter starter) throws InterruptedException {
        List<BotRegistry.BotConfig> botConfigs = context.agentOsConfig().bots();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (BotRegistry.BotConfig config : botConfigs) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    startConfiguredBot(context, config, starter);
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

    /** TS startConfiguredBot（L130-149 装配 + L768-777 装配后），生产经 FeishuBots.start。 */
    static void startConfiguredBot(Bootstrap context, BotRegistry.BotConfig config) {
        startConfiguredBot(context, config, FeishuBots::start);
    }

    /** BotOptions → Bot 的启动缝（Task 3 adapterResolver / Task 4 CommentRunner 先例）：
     *  装配接线测试用 fake starter 记录 BotOptions，不真连飞书。 */
    @FunctionalInterface
    interface BotStarter {
        Bot start(BotOptions options);
    }

    /** TS startConfiguredBot 装配面（裁决 12）；starter 注入缝见上。 */
    static void startConfiguredBot(Bootstrap context, BotRegistry.BotConfig config,
            BotStarter starter) {
        BotMessageOrchestrator orchestrator = new BotMessageOrchestrator(
                context.runtime(), config, context.collaborationService(),
                context.agentOsConfig().defaultProductDeliveryMode());
        // L134-149 startBot({...})
        BotOptions options = new BotOptions(
                config.appId(),
                config.appSecret(),
                // 裁决 5（R1/R11）：FeishuBots 在 SDK WS 线程同步直调 onMessage（核实：无任何
                // try/catch，handle 声明 throws Exception，异常直进 SDK 事件分发器）→
                // 每条消息投递独立虚拟线程执行编排，分钟级阻塞 IO 不钉死回调线程；
                // catch-all 兜底文案对齐 TS 收尾链末位 .catch 的 [任务] 回传或收尾失败:
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
                // L143-149：仅 lark-drive skill 挂载 onDocumentComment（装配后
                // subscribeToDocumentComments 为第二处条件，两处缺一不可，裁决 12）
                config.skills().contains("lark-drive")
                        ? (comment, bot) -> context.documentCommentScheduler()
                                .scheduleDocumentComment(config, bot, comment)
                        : null);
        Bot startedBot = starter.start(options);
        // L768-770 identity → botRuntimes.set
        BotIdentity identity = startedBot.getIdentity();
        context.runtime().botRuntimes().put(config.id(),
                new BotRuntime(config, startedBot, identity));
        // L771-773
        if (config.skills().contains("lark-drive")) {
            startedBot.subscribeToDocumentComments();
        }
        // L774-776
        LOGGER.info("[Bot {}] 已连接 name={} open_id={}",
                config.id().toUpperCase(Locale.ROOT), identity.name(), identity.openId());
    }

    /** 裁决 4：保活等价（机制与对齐说明见类 javadoc）；Main 线程永久阻塞不返回。 */
    static void keepAlive() throws InterruptedException {
        new CountDownLatch(1).await();
    }

    /**
     * 裁决 3：对齐 TS {@code import 'dotenv/config'}（不覆盖已有环境变量）——
     * System.getenv 全量先入，.env 条目 putIfAbsent 补缺（System.getenv 优先，
     * 单测钉住）；.env 缺席静默（TS dotenv/config 同）。合并 Map 同时作为
     * BOTS_CONFIG 取值源与 loadAgentOsConfig 的 env 参（.env 的 appIdEnv/appSecretEnv
     * 由此生效）。
     */
    static Map<String, String> mergedEnvironment() {
        return mergeEnvironment(System.getenv(),
                loadDotEnvFile(Path.of("").toAbsolutePath().normalize()));
    }

    /** .env 解析（路线图钦点 dotenv-java）；仅取文件内声明条目，缺文件返回空表。 */
    static Map<String, String> loadDotEnvFile(Path directory) {
        Dotenv dotenv = Dotenv.configure()
                .directory(directory.toString())
                .ignoreIfMissing()
                .load();
        Map<String, String> entries = new LinkedHashMap<>();
        for (DotenvEntry entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
            entries.put(entry.getKey(), entry.getValue());
        }
        return entries;
    }

    /** dotenv 合并顺序：systemEnv 全量在前，fileEntries 只补没有的 key（putIfAbsent）。 */
    static Map<String, String> mergeEnvironment(Map<String, String> systemEnv,
            Map<String, String> fileEntries) {
        Map<String, String> merged = new LinkedHashMap<>(systemEnv);
        for (Map.Entry<String, String> entry : fileEntries.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(merged);
    }

    /** TS path.resolve(value)（对 cwd）同构：绝对路径原样，否则挂 base（'' → base 自身）。 */
    private static String resolveAgainstBase(String value, String baseDirectory) {
        Path path = Path.of(value);
        Path resolved = path.isAbsolute() ? path : Path.of(baseDirectory).resolve(path);
        return resolved.normalize().toString();
    }

    /** L68-128 初始化产物。runtime 九分量含内存态容器；botRuntimes 经 runtime 携带。 */
    record Bootstrap(
            BotRegistry.AgentOsConfig agentOsConfig,
            TeamRegistry teamRegistry,
            SessionManager sessions,
            AppRuntime runtime,
            CollaborationService collaborationService,
            DocumentCommentScheduler documentCommentScheduler) {}
}

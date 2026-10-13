package com.leftrr.agentscheduling;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.im.BotOptions;
import com.leftrr.agentscheduling.im.IncomingMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 Task 5：Main 初始化序列与装配接线测试。
 *
 * <p>拆分形态申报：Main 可测部分函数化——initialize(baseDirectory, env)（L68-128 逐语句）、
 * startConfiguredBot/startAllBots 的 starter 注入缝、mergedEnvironment 拆成
 * loadDotEnvFile + mergeEnvironment 两纯函数；keepAlive 的 latch 阻塞语义不在单测内
 * 真测（真测=挂死测试线程），其等价性论证见 Main javadoc（裁决 4）。</p>
 */
class MainTest {

    @TempDir
    Path tempDir;

    // ---------- fixture ----------

    private static final String CONFIG = """
            {
              "teamLeader": "leader",
              "bots": [
                {
                  "id": "leader",
                  "appIdEnv": "LEADER_APP_ID",
                  "appSecretEnv": "LEADER_APP_SECRET",
                  "defaultCli": "claude",
                  "role": "组长",
                  "skills": %SKILLS%
                }
              ]
            }
            """;

    private static Map<String, String> envOf(String... pairs) {
        Map<String, String> env = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            env.put(pairs[i], pairs[i + 1]);
        }
        return env;
    }

    private static Map<String, String> credentialEnv() {
        return envOf("LEADER_APP_ID", "appid", "LEADER_APP_SECRET", "secret");
    }

    /** 缺省装配环境：bots.json 落 config/、workspace 回退 "."（= tempDir 自身，天然存在）。 */
    private Path writeConfig(String skillsJson) throws IOException {
        Path configDir = Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(configDir.resolve("bots.json"),
                CONFIG.replace("%SKILLS%", skillsJson), StandardCharsets.UTF_8);
        return configDir.resolve("bots.json");
    }

    private static void awaitTrue(BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待超时: " + message);
            }
            Thread.sleep(20);
        }
    }

    // ---------- dotenv 合并（裁决 3） ----------

    @Test
    void dotenv合并_SystemEnv优先() {
        Map<String, String> merged = Main.mergeEnvironment(
                envOf("A", "sys-a", "B", "sys-b"),
                envOf("B", "file-b", "C", "file-c"));
        assertEquals(3, merged.size());
        assertEquals("sys-a", merged.get("A"));
        // System.getenv 已有的 key 不被 .env 覆盖（对齐 TS dotenv/config 不覆盖语义）
        assertEquals("sys-b", merged.get("B"));
        assertEquals("file-c", merged.get("C"));
    }

    @Test
    void loadDotEnvFile_解析条目_缺文件返回空表() throws IOException {
        Path envDir = Files.createDirectories(tempDir.resolve("envdir"));
        Files.writeString(envDir.resolve(".env"),
                "LEADER_APP_ID=from-file\n# COMMENTED=skip\n", StandardCharsets.UTF_8);
        Map<String, String> loaded = Main.loadDotEnvFile(envDir);
        assertEquals("from-file", loaded.get("LEADER_APP_ID"));
        assertFalse(loaded.containsKey("COMMENTED"));

        // .env 缺席静默（对齐 TS dotenv/config）
        Path emptyDir = Files.createDirectories(tempDir.resolve("emptydir"));
        assertTrue(Main.loadDotEnvFile(emptyDir).isEmpty());
    }

    // ---------- 初始化序列（L68-128） ----------

    @Test
    void initialize_缺botsjson_文案逐字() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv()));
        assertEquals("找不到 bot 配置文件: " + tempDir.resolve("config").resolve("bots.json")
                + "。请复制 config/bots.example.json 后填写配置。", error.getMessage());
    }

    @Test
    void initialize_BOTS_CONFIG_env覆盖配置路径() throws IOException {
        // 相对路径按 baseDirectory（= CWD 对位）解析
        Files.writeString(tempDir.resolve("custom.json"),
                CONFIG.replace("%SKILLS%", "[]"), StandardCharsets.UTF_8);
        Map<String, String> env = credentialEnv();
        env.put("BOTS_CONFIG", "custom.json");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), env);
        assertEquals(1, context.agentOsConfig().bots().size());
    }

    @Test
    void initialize_缺凭证_启动失败文案逐字() throws IOException {
        // R4：无降级运行形态——缺环境变量即抛（对齐 TS 顶层 await reject 进程崩）
        writeConfig("[]");
        Map<String, String> env = envOf("LEADER_APP_ID", "appid");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Main.initialize(tempDir.toAbsolutePath().toString(), env));
        assertEquals("bot 配置文件格式错误: bot leader 缺少环境变量 LEADER_APP_SECRET",
                error.getMessage());
    }

    @Test
    void initialize_完整序列_启动日志与接线() throws IOException {
        writeConfig("[\"no-such-skill-probe-xyz\"]");
        MainLogCapture capture = MainLogCapture.attach(Main.class);
        try {
            Main.Bootstrap context =
                    Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv());
            List<String> logs = capture.messages();
            // missing skills warn 文案逐字（先于启动日志，对齐 TS L77-81 → L117 语句序）
            assertEquals("[Skill] bot=leader 找不到 no-such-skill-probe-xyz，"
                    + "请安装到工作区或用户级 Skills 目录", logs.get(0));
            // 启动日志 5 条文案逐字
            assertEquals("Agent OS 启动，正在建立飞书长连接…", logs.get(1));
            assertEquals("[配置] 已注册 1 个 bot，Team Leader=leader，已恢复 0 个会话",
                    logs.get(2));
            List<CliAdapter> adapters = CliAdapterRegistry.list();
            for (int i = 0; i < adapters.size(); i++) {
                assertEquals("[CLI] id=" + adapters.get(i).id().id()
                        + " command=" + adapters.get(i).command(), logs.get(i + 3));
            }
            assertEquals("[Bot LEADER] default_cli=claude workspace="
                            + tempDir.toAbsolutePath().normalize().toString(),
                    logs.get(adapters.size() + 3));
            assertEquals(adapters.size() + 4, logs.size());

            // 九分量接线：同一 teamLeaderId 构造（Task 3 审查 Minor ㊃）、runtime 携带
            assertEquals("leader", context.teamRegistry().leaderBotId());
            assertSame(context.teamRegistry(), context.runtime().teamRegistry());
            assertSame(context.sessions(), context.runtime().sessions());
            assertEquals(0, context.runtime().sessions().size());
            assertNotNull(context.runtime().collaborationInbox());
            assertNotNull(context.runtime().clarificationFlows());
            assertNotNull(context.runtime().productSpecFlows());
            assertNotNull(context.collaborationService());
            assertNotNull(context.documentCommentScheduler());
            assertTrue(context.runtime().activeRuns().isEmpty());
            assertTrue(context.runtime().botRuntimes().isEmpty());
        } finally {
            capture.detach();
        }
    }

    // ---------- startConfiguredBot 装配（裁决 12） ----------

    @Test
    void 装配接线_fakeStarter记录BotOptions_非larkDrive不挂评论回调() throws IOException {
        writeConfig("[]");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv());
        AtomicReference<BotOptions> recorded = new AtomicReference<>();
        FakeBot fakeBot = new FakeBot("组长", "ou_leader");
        Main.startConfiguredBot(context, context.agentOsConfig().bots().get(0), options -> {
            recorded.set(options);
            return fakeBot;
        });

        BotOptions options = recorded.get();
        assertNotNull(options);
        assertEquals("appid", options.appId());
        assertEquals("secret", options.appSecret());
        assertNotNull(options.onMessage());
        assertNotNull(options.onCardAction());
        // 仅 lark-drive skill 挂载（裁决 12 两处条件的第一处）
        assertNull(options.onDocumentComment());
        assertEquals(0, fakeBot.subscribeCalls.get());

        // 装配后：identity → botRuntimes.set
        assertEquals(1, context.runtime().botRuntimes().size());
        assertSame(fakeBot, context.runtime().botRuntimes().get("leader").bot());
        assertEquals("ou_leader",
                context.runtime().botRuntimes().get("leader").identity().openId());
    }

    @Test
    void 装配接线_larkDrive_挂评论回调并订阅() throws IOException {
        writeConfig("[\"lark-drive\"]");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv());
        AtomicReference<BotOptions> recorded = new AtomicReference<>();
        FakeBot fakeBot = new FakeBot("组长", "ou_leader");
        Main.startConfiguredBot(context, context.agentOsConfig().bots().get(0), options -> {
            recorded.set(options);
            return fakeBot;
        });
        assertNotNull(recorded.get().onDocumentComment());
        assertEquals(1, fakeBot.subscribeCalls.get());
    }

    @Test
    void onMessage_经虚拟线程投递到orchestrator() throws Exception {
        writeConfig("[]");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv());
        List<BotOptions> recordedOptions = new ArrayList<>();
        FakeBot fakeBot = new FakeBot("组长", "ou_leader");
        Main.startConfiguredBot(context, context.agentOsConfig().bots().get(0), options -> {
            recordedOptions.add(options);
            return fakeBot;
        });
        // /claude 空 prompt → 走 orchestrator 前半的引导回复路径（不触达 CLI 执行）
        IncomingMessage msg = new IncomingMessage("m1", "chat1", "group", "text",
                "/claude", null, null, "user", "ou_user", null, List.of(), "/claude");
        recordedOptions.get(0).onMessage().onMessage(msg, fakeBot);
        awaitTrue(() -> !fakeBot.replies.isEmpty(), "虚拟线程应完成编排回复");
        assertEquals("请在 /claude 后面写下任务，例如：/claude 检查项目状态",
                fakeBot.replies.get(0));
    }

    @Test
    void onMessage_编排异常落catchAll兜底文案() throws Exception {
        writeConfig("[]");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv());
        List<BotOptions> recordedOptions = new ArrayList<>();
        FakeBot failBot = new FakeBot("组长", "ou_leader");
        failBot.failReply = true;
        Main.startConfiguredBot(context, context.agentOsConfig().bots().get(0), options -> {
            recordedOptions.add(options);
            return failBot;
        });
        MainLogCapture capture = MainLogCapture.attach(Main.class);
        try {
            IncomingMessage msg = new IncomingMessage("m1", "chat1", "group", "text",
                    "/claude", null, null, "user", "ou_user", null, List.of(), "/claude");
            recordedOptions.get(0).onMessage().onMessage(msg, failBot);
            awaitTrue(() -> capture.messages().stream()
                    .anyMatch(text -> text.equals("[任务] 回传或收尾失败: reply boom")),
                    "应有 catch-all 兜底日志");
        } finally {
            capture.detach();
        }
    }

    // ---------- startAllBots（L868-870 Promise.all 对位） ----------

    @Test
    void startAllBots_全部bot并行启动() throws IOException, InterruptedException {
        Path configDir = Files.createDirectories(tempDir.resolve("config"));
        Files.createDirectories(tempDir.resolve("ws1"));
        Files.createDirectories(tempDir.resolve("ws2"));
        Files.writeString(configDir.resolve("bots.json"), """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {
                      "id": "leader",
                      "appIdEnv": "LEADER_APP_ID",
                      "appSecretEnv": "LEADER_APP_SECRET",
                      "defaultCli": "claude",
                      "role": "组长",
                      "workspace": "ws1"
                    },
                    {
                      "id": "dev",
                      "appIdEnv": "DEV_APP_ID",
                      "appSecretEnv": "DEV_APP_SECRET",
                      "defaultCli": "codex",
                      "role": "开发",
                      "workspace": "ws2"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        Map<String, String> env = credentialEnv();
        env.put("DEV_APP_ID", "appid-dev");
        env.put("DEV_APP_SECRET", "secret-dev");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), env);
        Map<String, FakeBot> started = new HashMap<>();
        Main.startAllBots(context, options -> {
            FakeBot fakeBot = new FakeBot("bot", options.appId());
            started.put(options.appId(), fakeBot);
            return fakeBot;
        });
        assertEquals(2, context.runtime().botRuntimes().size());
        assertSame(started.get("appid"),
                context.runtime().botRuntimes().get("leader").bot());
        assertSame(started.get("appid-dev"),
                context.runtime().botRuntimes().get("dev").bot());
    }

    @Test
    void startAllBots_启动失败抛出对齐PromiseAllReject() throws IOException {
        writeConfig("[]");
        Main.Bootstrap context =
                Main.initialize(tempDir.toAbsolutePath().toString(), credentialEnv());
        RuntimeException failure =
                new RuntimeException("bot leader 缺少环境变量 LEADER_APP_ID");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> Main.startAllBots(context, options -> {
                    throw failure;
                }));
        assertSame(failure, thrown);
    }
}

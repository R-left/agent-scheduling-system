package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.CancelMode;
import com.leftrr.agentscheduling.core.CliRequest;
import com.leftrr.agentscheduling.core.MessageAddress;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.SlashCommand;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.BotIdentity;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.im.IncomingMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 5：handleSessionCommand 测试（本任务最大测试面）。
 * 对齐 TS 版 src/app/command-handler.ts（249 行）：全部 reply 文案逐字（含中文标点）、
 * /help 11 行帮助、/team 成员行五字段映射（displayName 兜底 member.id、isLeader、ready）、
 * /new 三态守卫 + clearCliSessionId + 通知卡参数、/resume 守卫 + Claude 分支文件系统替身 +
 * catch 文案、/compact 三守卫 + 通过后 CONTINUE、/status、/cd 四分支、/close 双路径
 * （cancel 触发瞬间采样 cancelMode 已为 CLOSE —— 钉住先 setCancelMode 后 cancel 的写序，
 * 见 ActiveRun javadoc 警告；与 TaskAbort 的先 cancel 后写相反）、兜底 CONTINUE。
 *
 * <p>TS→Java 测试形态差异：TS 的 ActiveRun 是普通对象可直接 spy 调用序；Java 版 ActiveRun
 * 是 final 类且 token 为 final 字段，无法子类化记录 setCancelMode/cancel 的调用序——
 * 改用「注册取消监听器，在 cancel() 触发监听器的瞬间采样 active.cancelMode()」：
 * 采样到 CLOSE 即证明写入发生在 cancel() 之前（volatile 写先于 cancel，监听器必然可见）；
 * 若实现误走 TaskAbort 的先 cancel 后写序，监听器采样到的是 null。</p>
 *
 * <p>/resume 成功路径走 NativeSessions 的 Claude 分支（纯文件系统读，无子进程）：
 * 测试把 System.setProperty("user.home") 指到临时目录伪造 ~/.claude/projects 布局
 * （env CLAUDE_CONFIG_DIR 不可注入，故先 assumeTrue 该环境变量未设置）；失败路径把
 * 项目目录建成普通文件让 Files.list 抛 IOException，进入 catch 文案分支。
 * Codex 分支（真实子进程）无注入机制，按计划裁决 14 不测，留 M7。</p>
 */
class CommandHandlerTest {

    // ---------- 夹具 ----------

    private static BotRegistry.BotConfig config(String id, CliId cliId, String role,
            List<String> skills) {
        return new BotRegistry.BotConfig(id, "app-" + id, "secret", cliId, role, skills,
                null, "C:/ws/" + id, 3);
    }

    /** 记录 reply / replyCard 调用参数的 fake Bot（本任务两参默认重载未被 handler 使用）。 */
    private static final class FakeBot implements Bot {
        record ReplyCall(String messageId, String text, boolean replyInThread) {}
        record CardCall(String messageId, JsonNode card, boolean replyInThread) {}
        final List<ReplyCall> replies = new ArrayList<>();
        final List<CardCall> cards = new ArrayList<>();

        @Override
        public BotIdentity getIdentity() {
            return new BotIdentity("ou_self", "自己");
        }

        @Override
        public String reply(String messageId, String text, boolean replyInThread) {
            replies.add(new ReplyCall(messageId, text, replyInThread));
            return "om-reply-1";
        }

        @Override
        public String replyCard(String messageId, JsonNode card, boolean replyInThread) {
            cards.add(new CardCall(messageId, card, replyInThread));
            return "om-card-1";
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
        }

        @Override
        public void setDocumentCommentWorking(IncomingDocumentComment comment, boolean active) {
        }

        @Override
        public String downloadResource(String messageId, String fileKey, String type,
                String saveDir, String fileName) {
            return null;
        }
    }

    /** 装配好的运行时（leader 为当前 bot，worker 仅在名册）。 */
    private record Fixture(AppRuntime runtime, FakeBot bot, SessionManager sessions,
            BotRegistry.BotConfig config, Session session) {
    }

    private static Fixture fixture(String workspaceDir) throws IOException {
        SessionManager sessions = new SessionManager();
        Session session = sessions.resolve(new MessageAddress("om-1", "oc-1", "ot-1", null),
                CliId.CLAUDE, "leader", workspaceDir).session();
        AppRuntime runtime = new AppRuntime(sessions, new TeamRegistry("leader", List.of(
                config("leader", CliId.CLAUDE, "统筹", List.of("to-spec")),
                config("worker", CliId.CODEX, "开发", List.of()))));
        FakeBot bot = new FakeBot();
        runtime.botRuntimes().put("leader", new BotRuntime(
                config("leader", CliId.CLAUDE, "统筹", List.of("to-spec")), bot,
                new BotIdentity("ou_leader", "队长")));
        return new Fixture(runtime, bot, sessions,
                config("leader", CliId.CLAUDE, "统筹", List.of("to-spec")), session);
    }

    private static Fixture fixture() throws IOException {
        return fixture("D:/ws");
    }

    private static IncomingMessage msg() {
        return new IncomingMessage("om-1", "oc-1", "group", "text", "/cmd",
                null, "ot-1", "member", "ou_user", "ouu_user", List.of(), null);
    }

    /** 默认入参：无 command / 无 cliRequest / 非新话题 / 有话题（对齐 TS options 对象）。 */
    private static CommandHandler.CommandOutcome handle(Fixture f, SlashCommand command)
            throws IOException {
        return handle(f, command, null, false);
    }

    private static CommandHandler.CommandOutcome handle(Fixture f, SlashCommand command,
            CliRequest cliRequest, boolean isNew) throws IOException {
        // TS index.ts 每条消息先 resolve 再传入最新 session；测试取管理器中的当前会话
        // 对齐该形态（fixture 捕获的快照会被 transition/setWorkspaceDir 更新为新对象）
        Session session = f.sessions().get(f.session().id());
        return CommandHandler.handleSessionCommand(
                new CommandHandler.HandleSessionCommandOptions(
                        f.runtime(), f.config(), msg(), f.bot(), session,
                        CliAdapterRegistry.get(session.cliId()),
                        command, cliRequest, isNew, true));
    }

    private static void transitionTo(Fixture f, SessionStatus status) throws IOException {
        f.sessions().transition(f.session().id(), status);
    }

    // ---------- 引擎不匹配守卫（TS L47-54） ----------

    @Test
    void 引擎不匹配守卫文案逐字且handled() throws IOException {
        Fixture f = fixture();
        CommandHandler.CommandOutcome outcome = handle(f, null,
                new CliRequest(CliId.CODEX, "任务"), false);

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals(1, f.bot().replies.size());
        FakeBot.ReplyCall reply = f.bot().replies.get(0);
        assertEquals("当前话题已经在使用 Claude Code。如需切换执行引擎，请新开一个话题。",
                reply.text());
        assertEquals("om-1", reply.messageId());
        assertTrue(reply.replyInThread(), "hasThread 应透传给 reply");
        assertEquals(0, f.bot().cards.size());
    }

    @Test
    void 新话题或引擎一致时不触发守卫() throws IOException {
        // isNew=true：cliId 不一致也放行（对齐 TS !isNew 短路）
        Fixture f1 = fixture();
        assertEquals(CommandHandler.CommandOutcome.CONTINUE,
                handle(f1, null, new CliRequest(CliId.CODEX, "任务"), true));
        assertEquals(0, f1.bot().replies.size());
        // isNew=false 但 cliId 与会话一致：放行
        Fixture f2 = fixture();
        assertEquals(CommandHandler.CommandOutcome.CONTINUE,
                handle(f2, null, new CliRequest(CliId.CLAUDE, "任务"), false));
        assertEquals(0, f2.bot().replies.size());
    }

    // ---------- /help（TS L56-75） ----------

    @Test
    void help帮助文本十一行逐字() throws IOException {
        Fixture f = fixture();

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.HELP));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals(1, f.bot().replies.size());
        assertEquals(String.join("\n",
                "/status 查看当前会话",
                "/team 查看当前 Agent 团队",
                "/new 开启一个全新的 CLI 会话",
                "/resume 选择当前工作目录中的 CLI 会话",
                "/compact [要求] 使用当前引擎原生整理上下文",
                "/cd 查看当前工作目录",
                "/cd <目录> 切换当前话题的工作目录",
                "/close 关闭当前会话",
                "/help 查看命令",
                "/claude <任务> 新话题使用 Claude Code",
                "/codex <任务> 新话题使用 Codex"), f.bot().replies.get(0).text());
    }

    // ---------- /team（TS L77-97） ----------

    @Test
    void team卡片成员行五字段映射逐字() throws IOException {
        Fixture f = fixture(); // leader 已注册（identity 队长），worker 未注册

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.TEAM));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals(1, f.bot().cards.size());
        FakeBot.CardCall cardCall = f.bot().cards.get(0);
        assertEquals("om-1", cardCall.messageId());
        JsonNode card = cardCall.card();
        // card.ts 摘要与副标题：leader 在册 → displayName 取 identity.name 且有「负责统筹」后缀
        assertEquals("Agent 团队：2 位成员",
                card.get("config").get("summary").get("content").asText());
        assertEquals("2 位成员 · 队长 负责统筹",
                card.get("header").get("subtitle").get("content").asText());
        // 成员行：displayName 兜底 member.id、isLeader、ready、cliName、skills 五字段
        JsonNode body = card.get("body").get("elements");
        assertEquals(4, body.size(), "说明 + hr + 两行成员");
        assertEquals("**队长**  _Team Leader · 已连接_\n统筹\n引擎：Claude Code　Skill：$to-spec",
                body.get(2).get("content").asText());
        assertEquals("**worker**  _未连接_\n开发\n引擎：Codex　Skill：无",
                body.get(3).get("content").asText());
    }

    // ---------- /new（TS L99-119） ----------

    @Test
    void new在active时守卫文案逐字() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.ACTIVE);

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.NEW));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals("当前任务结束后才能新建会话。", f.bot().replies.get(0).text());
        assertEquals(0, f.bot().cards.size());
    }

    @Test
    void new在closed时守卫文案逐字() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.CLOSED);

        handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.NEW));

        assertEquals("当前话题的会话已经关闭。", f.bot().replies.get(0).text());
        assertEquals(0, f.bot().cards.size());
    }

    @Test
    void new通过守卫后清空CLI会话并回复通知卡() throws IOException {
        Fixture f = fixture();
        f.sessions().setCliSessionId(f.session().id(), "cli-1");

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.NEW));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        // clearCliSessionId 已落：后续会话取回应为 null
        assertNull(f.sessions().get(f.session().id()).cliSessionId());
        assertEquals(1, f.bot().cards.size());
        JsonNode card = f.bot().cards.get(0).card();
        assertEquals("新会话已就绪", card.get("header").get("title").get("content").asText());
        assertEquals("green", card.get("header").get("template").asText());
        // detail 逐字：含 /resume 反引号与空行（TS 模板串 \n\n）
        assertEquals("下一条任务会由 Claude Code 开启全新的 CLI 会话。\n\n"
                + "旧会话仍然保留，可以随时用 `/resume` 找回来。",
                card.get("body").get("elements").get(0).get("content").asText());
    }

    // ---------- /resume（TS L121-153） ----------

    @Test
    void resume在active与closed时守卫文案逐字() throws IOException {
        Fixture f1 = fixture();
        transitionTo(f1, SessionStatus.ACTIVE);
        handle(f1, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.RESUME));
        assertEquals("当前任务结束后才能切换会话。", f1.bot().replies.get(0).text());
        assertEquals(0, f1.bot().cards.size());

        Fixture f2 = fixture();
        transitionTo(f2, SessionStatus.CLOSED);
        handle(f2, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.RESUME));
        assertEquals("当前话题的会话已经关闭。", f2.bot().replies.get(0).text());
        assertEquals(0, f2.bot().cards.size());
    }

    /** 把 user.home 指向临时目录（还原逻辑见 finally）；env CLAUDE_CONFIG_DIR 不可注入，缺席才可测。 */
    private static void withFakeHome(Path tempHome, Runnable action) {
        Assumptions.assumeTrue(
                System.getenv("CLAUDE_CONFIG_DIR") == null
                        || System.getenv("CLAUDE_CONFIG_DIR").isEmpty(),
                "CLAUDE_CONFIG_DIR 已设置，无法注入 user.home 替身");
        String original = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        try {
            action.run();
        } finally {
            System.setProperty("user.home", original);
        }
    }

    /** 镜像 NativeSessions.claudeProjectDirectory 的目录键规则（cwd 中非字母数字 → '-'）。 */
    private static Path claudeProjectDir(Path home, String cwd) {
        return home.resolve(".claude").resolve("projects")
                .resolve(cwd.replaceAll("[^A-Za-z0-9]", "-"));
    }

    @Test
    void resume成功路径回历史会话卡(@TempDir Path tempHome) throws Exception {
        Fixture f = fixture(tempHome.resolve("ws-a").toString());
        withFakeHome(tempHome, () -> {
            try {
                Path projectDir = claudeProjectDir(tempHome, f.session().workspaceDir());
                Files.createDirectories(projectDir);
                // 经 Jackson 序列化：Windows 路径的反斜杠需要 JSON 转义，手工拼接会产出非法 JSON
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                String line = mapper.writeValueAsString(mapper.createObjectNode()
                        .put("sessionId", "native-1")
                        .put("cwd", f.session().workspaceDir())
                        .put("type", "last-prompt")
                        .put("lastPrompt", "整理会议纪要"));
                Files.writeString(projectDir.resolve("native-1.jsonl"), line + "\n",
                        StandardCharsets.UTF_8);

                CommandHandler.CommandOutcome outcome =
                        handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.RESUME));

                assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
                assertEquals(0, f.bot().replies.size());
                assertEquals(1, f.bot().cards.size());
                JsonNode card = f.bot().cards.get(0).card();
                assertEquals("恢复历史会话", card.get("header").get("title").get("content").asText());
                assertEquals("Claude Code", card.get("header").get("subtitle").get("content").asText());
                // 会话行：标题 + 恢复按钮（agentSessionId / cliSessionId 回填到卡片 value）
                JsonNode body = card.get("body").get("elements");
                assertEquals("选择后，当前话题会继续使用对应的 CLI 上下文。",
                        body.get(0).get("content").asText());
                JsonNode button = body.get(1).get("columns").get(1).get("elements").get(0);
                JsonNode value = button.get("behaviors").get(0).get("value");
                assertEquals("resume_cli_session", value.get("action").asText());
                assertEquals(f.session().id(), value.get("agentSessionId").asText());
                assertEquals("native-1", value.get("cliSessionId").asText());
                assertTrue(body.get(1).get("columns").get(0).get("elements").get(0)
                        .get("content").asText().startsWith("**整理会议纪要**"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void resume空目录走卡片空态文案(@TempDir Path tempHome) throws Exception {
        Fixture f = fixture(tempHome.resolve("ws-b").toString());
        withFakeHome(tempHome, () -> {
            try {
                CommandHandler.CommandOutcome outcome =
                        handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.RESUME));

                assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
                assertEquals(1, f.bot().cards.size());
                // buildResumeCard 的 body：[0] 首行说明，空态文案在 [1]
                assertEquals("当前工作目录里还没有可以恢复的 CLI 会话。先完成一次任务，"
                                + "再用 `/new` 开启新会话。",
                        f.bot().cards.get(0).card().get("body").get("elements")
                                .get(1).get("content").asText());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void resume读取失败走catch文案(@TempDir Path tempHome) throws Exception {
        Fixture f = fixture(tempHome.resolve("ws-c").toString());
        withFakeHome(tempHome, () -> {
            try {
                // 项目目录位置建成普通文件：Files.list 抛 NotDirectoryException（IOException 子类）
                Path projectDir = claudeProjectDir(tempHome, f.session().workspaceDir());
                Files.createDirectories(projectDir.getParent());
                Files.writeString(projectDir, "not a directory", StandardCharsets.UTF_8);

                CommandHandler.CommandOutcome outcome =
                        handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.RESUME));

                assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
                assertEquals(0, f.bot().cards.size());
                assertEquals(1, f.bot().replies.size());
                assertTrue(f.bot().replies.get(0).text()
                                .startsWith("无法读取 Claude Code 会话："),
                        "catch 文案前缀应逐字，实际: " + f.bot().replies.get(0).text());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ---------- /compact（TS L155-177） ----------

    @Test
    void compact三守卫文案逐字() throws IOException {
        // 守卫一：active
        Fixture f1 = fixture();
        transitionTo(f1, SessionStatus.ACTIVE);
        assertEquals(CommandHandler.CommandOutcome.HANDLED,
                handle(f1, new SlashCommand.CompactCommand("精简")));
        assertEquals("当前任务结束后才能整理上下文。", f1.bot().replies.get(0).text());

        // 守卫二：closed
        Fixture f2 = fixture();
        transitionTo(f2, SessionStatus.CLOSED);
        handle(f2, new SlashCommand.CompactCommand("精简"));
        assertEquals("当前话题的会话已经关闭。", f2.bot().replies.get(0).text());

        // 守卫三：无 CLI 会话
        Fixture f3 = fixture();
        transitionTo(f3, SessionStatus.IDLE);
        handle(f3, new SlashCommand.CompactCommand("精简"));
        assertEquals("当前还没有可整理的 CLI 会话。先完成一次任务，再使用 /compact。",
                f3.bot().replies.get(0).text());
    }

    @Test
    void compact通过守卫后返回CONTINUE且回复() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.IDLE);
        f.sessions().setCliSessionId(f.session().id(), "cli-1");

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.CompactCommand("精简"));

        assertEquals(CommandHandler.CommandOutcome.CONTINUE, outcome);
        assertEquals(0, f.bot().replies.size(), "通过守卫后不回复（执行在 M7 主流程）");
        assertEquals(0, f.bot().cards.size());
    }

    // ---------- /status（TS L179-186） ----------

    @Test
    void status回复formatSessionStatus产物() throws IOException {
        Fixture f = fixture();

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.STATUS));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals(1, f.bot().replies.size());
        String[] lines = f.bot().replies.get(0).text().split("\n", -1);
        assertEquals("机器人：leader", lines[0]); // config.id（非 session.botId 的常量）
        assertEquals("会话：" + f.session().id(), lines[1]);
        assertEquals("状态：创建中", lines[2]);
        assertEquals("执行引擎：Claude Code", lines[3]);
        assertEquals("工作目录：D:/ws", lines[5]);
        assertEquals(f.bot().replies.get(0).text(),
                SessionView.formatSessionStatus(f.session(), f.config().id()));
    }

    // ---------- /cd（TS L188-228） ----------

    @Test
    void cd无path时回复当前目录() throws IOException {
        Fixture f1 = fixture();
        handle(f1, new SlashCommand.CdCommand(null));
        assertEquals("当前工作目录：D:/ws", f1.bot().replies.get(0).text());

        // TS `!command.path` 对空串同样命中
        Fixture f2 = fixture();
        handle(f2, new SlashCommand.CdCommand(""));
        assertEquals("当前工作目录：D:/ws", f2.bot().replies.get(0).text());
    }

    @Test
    void cd在active时守卫文案逐字() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.ACTIVE);

        handle(f, new SlashCommand.CdCommand("D:/other"));

        assertEquals("当前任务仍在执行，结束后再切换工作目录。", f.bot().replies.get(0).text());
    }

    @Test
    void cd成功且目录变化时回复已切换并更新会话(@TempDir Path base) throws IOException {
        Path sub = base.resolve("sub");
        Files.createDirectories(sub);
        Fixture f = fixture(base.toString());
        f.sessions().setCliSessionId(f.session().id(), "cli-1");

        CommandHandler.CommandOutcome outcome = handle(f, new SlashCommand.CdCommand(sub.toString()));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals("工作目录已切换到：" + sub + "\n下一条任务会在这里建立新的 CLI 会话。",
                f.bot().replies.get(0).text());
        // setWorkspaceDir 落库：目录更新 + CLI 会话选择一并清空（withWorkspaceDir 语义）
        Session updated = f.sessions().get(f.session().id());
        assertEquals(sub.toString(), updated.workspaceDir());
        assertNull(updated.cliSessionId());
    }

    @Test
    void cd成功但目录未变化时回复已经是(@TempDir Path base) throws IOException {
        // cd 到当前工作目录自身：changed=false → 另一版文案，setWorkspaceDir 同值短路
        Fixture f = fixture(base.toString());

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.CdCommand(base.toString()));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals("当前工作目录已经是：" + base, f.bot().replies.get(0).text());
    }

    @Test
    void cd目录不存在时走catch文案(@TempDir Path base) throws IOException {
        Fixture f = fixture();
        // ⑪：改用 TempDir 子路径（不创建），不依赖真实盘符路径；「目录不存在」语义不变
        String missing = base.resolve("no-such-dir").toString();

        CommandHandler.CommandOutcome outcome = handle(f, new SlashCommand.CdCommand(missing));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        // IllegalArgumentException（工作目录不存在: …）被 catch 承接为前缀文案
        assertEquals("无法切换工作目录：工作目录不存在: " + missing, f.bot().replies.get(0).text());
    }

    @Test
    void cd纯空白path时resolve抛错走catch文案() throws IOException {
        Fixture f = fixture();

        handle(f, new SlashCommand.CdCommand("   "));

        // resolveWorkspacePath trim 后为空 → IllegalArgumentException「工作目录不能为空」
        assertEquals("无法切换工作目录：工作目录不能为空", f.bot().replies.get(0).text());
    }

    // ---------- /close（TS L230-245，双路径语义） ----------

    @Test
    void close对运行中任务先置CLOSE再取消并关闭会话() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.IDLE);
        ActiveRun active = new ActiveRun(new CancellationToken(), "ou_owner");
        // cancel 触发监听器的瞬间采样 cancelMode：读到 CLOSE 即证明写入先于 cancel（见类注释）
        List<String> cancelTimeMode = new ArrayList<>();
        active.token().register(() -> cancelTimeMode.add(String.valueOf(active.cancelMode())));
        f.runtime().activeRuns().put(f.session().id(), active);

        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.CLOSE));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertEquals(List.of("CLOSE"), cancelTimeMode,
                "cancel() 触发监听器时 cancelMode 应已是 CLOSE（先 set 后 cancel）");
        assertTrue(active.token().isCancelled());
        assertEquals(CancelMode.CLOSE, active.cancelMode());
        assertEquals(SessionStatus.CLOSED, f.sessions().get(f.session().id()).status());
        assertEquals("当前会话已关闭。需要继续时，请新开一个话题。", f.bot().replies.get(0).text());
    }

    @Test
    void close对已关闭会话跳过transition但仍回复() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.CLOSED);
        ActiveRun active = new ActiveRun(new CancellationToken(), "ou_owner");
        f.runtime().activeRuns().put(f.session().id(), active);

        // 若实现误调 transition，CLOSED → CLOSED 会被 SessionManager 拒绝并抛
        // IllegalArgumentException，本测试不抛即钉住 `status !== closed` 守卫
        CommandHandler.CommandOutcome outcome =
                handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.CLOSE));

        assertEquals(CommandHandler.CommandOutcome.HANDLED, outcome);
        assertTrue(active.token().isCancelled(), "运行中任务仍应被取消");
        assertEquals("当前会话已关闭。需要继续时，请新开一个话题。", f.bot().replies.get(0).text());
        assertEquals(SessionStatus.CLOSED, f.sessions().get(f.session().id()).status());
    }

    @Test
    void close无运行中任务时仅关闭会话() throws IOException {
        Fixture f = fixture();
        transitionTo(f, SessionStatus.IDLE);

        handle(f, new SlashCommand.SimpleCommand(SlashCommand.SimpleName.CLOSE));

        assertEquals(SessionStatus.CLOSED, f.sessions().get(f.session().id()).status());
        assertEquals("当前会话已关闭。需要继续时，请新开一个话题。", f.bot().replies.get(0).text());
    }

    // ---------- 兜底（TS L247） ----------

    @Test
    void 无命令且无直达请求时返回CONTINUE() throws IOException {
        Fixture f = fixture();

        CommandHandler.CommandOutcome outcome = handle(f, null);

        assertEquals(CommandHandler.CommandOutcome.CONTINUE, outcome);
        assertEquals(0, f.bot().replies.size());
        assertEquals(0, f.bot().cards.size());
    }

}

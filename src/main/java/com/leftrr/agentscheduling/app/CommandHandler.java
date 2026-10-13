package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliSessionSummary;
import com.leftrr.agentscheduling.cli.NativeSessions;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancelMode;
import com.leftrr.agentscheduling.core.CliRequest;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.SlashCommand;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.IncomingMessage;
import com.leftrr.agentscheduling.im.SessionCards;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 斜杠命令处理（/help /team /new /resume /compact /status /cd /close 与引擎守卫）。
 * 对齐 TS 版 src/app/command-handler.ts（249 行），全部 reply 文案逐字（含中文标点）。
 *
 * <p>TS→Java 差异：</p>
 * <ul>
 *   <li>TS 的 {@code command?.name === "help"} 判别联合判定 → sealed SlashCommand 的
 *       switch pattern matching（guarded case 保持 TS 的分支序：help → team → new →
 *       resume → compact → status → cd → close → 兜底）。</li>
 *   <li>受检异常按 M6 计划裁决 1 逐分支对照 TS 的 await 是否有 try/catch：
 *       /new 的 clearCliSessionId 与 /close 的 transition 在 TS 无 try/catch →
 *       IOException 上抛由调用方兜底；/resume 的 listNativeCliSessions 与 /cd 的
 *       setWorkspaceDir 在 TS 各自分支内 catch 转文案，故在此分支内 catch 不外抛——
 *       因此外抛的只有 IOException，签名无需声明 InterruptedException。</li>
 *   <li>ensureWorkspaceDirectory 抛不受检 IllegalArgumentException（向 TS reject 对齐），
 *       /cd 分支的 catch 承接；/close 双路径（裁决 6）先 setCancelMode(CLOSE) 再
 *       cancel()，与 TaskAbort.requestTaskAbort 的先 cancel 后写相反（ActiveRun
 *       javadoc 有警告）——volatile 写先于 cancel，收尾分支与监听器都能读到 CLOSE。</li>
 * </ul>
 */
public final class CommandHandler {

    private CommandHandler() {}

    /** 对齐 TS CommandOutcome = "handled" | "continue"：handled 即主流程 return。 */
    public enum CommandOutcome { HANDLED, CONTINUE }

    /**
     * 对齐 TS handleSessionCommand 的 options 对象（command-handler.ts:22-33）。
     * command / cliRequest 为 null 表示 TS 的 undefined（可选传参）。
     */
    public record HandleSessionCommandOptions(
            AppRuntime runtime,
            BotRegistry.BotConfig config,
            IncomingMessage msg,
            Bot bot,
            Session session,
            CliAdapter cliAdapter,
            SlashCommand command,
            CliRequest cliRequest,
            boolean isNew,
            boolean hasThread) {}

    public static CommandOutcome handleSessionCommand(HandleSessionCommandOptions options)
            throws IOException {
        AppRuntime runtime = options.runtime();
        Bot bot = options.bot();
        IncomingMessage msg = options.msg();
        Session session = options.session();
        CliAdapter cliAdapter = options.cliAdapter();
        boolean hasThread = options.hasThread();

        // L47-54 引擎不匹配守卫：非新话题的 /claude /codex 直达请求且与会话引擎不一致
        if (!options.isNew() && options.cliRequest() != null
                && options.cliRequest().cliId() != session.cliId()) {
            bot.reply(msg.messageId(),
                    "当前话题已经在使用 " + cliAdapter.displayName()
                            + "。如需切换执行引擎，请新开一个话题。",
                    hasThread);
            return CommandOutcome.HANDLED;
        }

        // L56-245 命令分支（guarded case 序 = TS if 链序）
        SlashCommand command = options.command();
        switch (command) {
            case null -> {
                // 兜底：无命令落到 L247 return "continue"
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.HELP -> {
                // L56-75：11 行帮助文本逐字
                bot.reply(msg.messageId(), String.join("\n",
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
                        "/codex <任务> 新话题使用 Codex"), hasThread);
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.TEAM -> {
                // L77-97：成员行五字段映射（displayName 兜底 member.id、isLeader、ready）
                List<SessionCards.TeamCardMember> members = new ArrayList<>();
                for (BotRegistry.BotConfig member : runtime.teamRegistry().members()) {
                    BotRuntime memberRuntime = runtime.botRuntimes().get(member.id());
                    members.add(new SessionCards.TeamCardMember(
                            member.id(),
                            memberRuntime != null && memberRuntime.identity() != null
                                    ? memberRuntime.identity().name() : member.id(),
                            member.role(),
                            CliAdapterRegistry.get(member.defaultCliId()).displayName(),
                            member.skills(),
                            member.id().equals(runtime.teamRegistry().leaderBotId()),
                            memberRuntime != null));
                }
                bot.replyCard(msg.messageId(),
                        SessionCards.buildTeamCard(new SessionCards.TeamCardOptions(members)),
                        hasThread);
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.NEW -> {
                // L99-119：三态守卫 + 清空 CLI 会话选择 + 新会话通知卡
                if (session.status() == SessionStatus.ACTIVE) {
                    bot.reply(msg.messageId(), "当前任务结束后才能新建会话。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                if (session.status() == SessionStatus.CLOSED) {
                    bot.reply(msg.messageId(), "当前话题的会话已经关闭。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                // TS await 无 try/catch → IOException 上抛由调用方兜底
                runtime.sessions().clearCliSessionId(session.id());
                bot.replyCard(msg.messageId(), SessionCards.buildSessionNoticeCard(
                                new SessionCards.SessionNoticeCardOptions("新会话已就绪",
                                        "下一条任务会由 " + cliAdapter.displayName()
                                                + " 开启全新的 CLI 会话。\n\n"
                                                + "旧会话仍然保留，可以随时用 `/resume` 找回来。",
                                        "green")),
                        hasThread);
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.RESUME -> {
                // L121-153：两守卫 + 原生历史会话卡 + catch 转文案
                if (session.status() == SessionStatus.ACTIVE) {
                    bot.reply(msg.messageId(), "当前任务结束后才能切换会话。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                if (session.status() == SessionStatus.CLOSED) {
                    bot.reply(msg.messageId(), "当前话题的会话已经关闭。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                try {
                    List<CliSessionSummary> nativeSessions = NativeSessions.listNativeCliSessions(
                            new NativeSessions.Options(cliAdapter, session.workspaceDir()));
                    bot.replyCard(msg.messageId(), SessionCards.buildResumeCard(
                                    new SessionCards.ResumeCardOptions(session.id(),
                                            cliAdapter.displayName(), session.cliSessionId(),
                                            nativeSessions)),
                            hasThread);
                } catch (IOException | InterruptedException e) {
                    // TS catch 转 reply 文案；中断路径按裁决 1 先恢复中断标记再走同一文案
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    bot.reply(msg.messageId(),
                            "无法读取 " + cliAdapter.displayName() + " 会话：" + e.getMessage(),
                            hasThread);
                }
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.CompactCommand compact -> {
                // L155-177：三守卫全过后返回 CONTINUE（执行在 index.ts 主流程）
                if (session.status() == SessionStatus.ACTIVE) {
                    bot.reply(msg.messageId(), "当前任务结束后才能整理上下文。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                if (session.status() == SessionStatus.CLOSED) {
                    bot.reply(msg.messageId(), "当前话题的会话已经关闭。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                // TS `!session.cliSessionId` 对 undefined 与空串均为真（空串原样透传的语义见 Session）
                if (session.cliSessionId() == null || session.cliSessionId().isEmpty()) {
                    bot.reply(msg.messageId(),
                            "当前还没有可整理的 CLI 会话。先完成一次任务，再使用 /compact。",
                            hasThread);
                    return CommandOutcome.HANDLED;
                }
                return CommandOutcome.CONTINUE;
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.STATUS -> {
                // L179-186
                bot.reply(msg.messageId(),
                        SessionView.formatSessionStatus(session, options.config().id()),
                        hasThread);
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.CdCommand cd -> {
                // L188-228：无 path 查看当前目录；active 守卫；成功两版文案；异常 catch
                if (cd.path() == null || cd.path().isEmpty()) {
                    bot.reply(msg.messageId(), "当前工作目录：" + session.workspaceDir(), hasThread);
                    return CommandOutcome.HANDLED;
                }
                if (session.status() == SessionStatus.ACTIVE) {
                    bot.reply(msg.messageId(), "当前任务仍在执行，结束后再切换工作目录。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                try {
                    String workspaceDir = Workspace.resolveWorkspacePath(cd.path(),
                            session.workspaceDir());
                    Workspace.ensureWorkspaceDirectory(workspaceDir);
                    boolean changed = !workspaceDir.equals(session.workspaceDir());
                    runtime.sessions().setWorkspaceDir(session.id(), workspaceDir);
                    bot.reply(msg.messageId(), changed
                            ? "工作目录已切换到：" + workspaceDir
                                    + "\n下一条任务会在这里建立新的 CLI 会话。"
                            : "当前工作目录已经是：" + workspaceDir, hasThread);
                } catch (IOException | RuntimeException e) {
                    // TS catch（resolve/ensure 的 reject + setWorkspaceDir 的 reject 同在此承接）
                    bot.reply(msg.messageId(), "无法切换工作目录：" + e.getMessage(), hasThread);
                }
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.CLOSE -> {
                // L230-245：双路径（裁决 6）——运行中任务先置 CLOSE 再取消（写序与
                // TaskAbort.requestTaskAbort 的先 cancel 后写相反），随后会话未关闭才 transition
                ActiveRun active = runtime.activeRuns().get(session.id());
                if (active != null) {
                    active.setCancelMode(CancelMode.CLOSE); // 必须在 cancel() 之前（volatile 写先可见）
                    active.token().cancel();
                }
                if (session.status() != SessionStatus.CLOSED) {
                    // TS await 无 try/catch → IOException 上抛由调用方兜底
                    runtime.sessions().transition(session.id(), SessionStatus.CLOSED);
                }
                bot.reply(msg.messageId(), "当前会话已关闭。需要继续时，请新开一个话题。", hasThread);
                return CommandOutcome.HANDLED;
            }
            default -> {
                // 未识别的 SimpleName 落兜底（当前六名全覆盖，仅为模式匹配完备性保留）
            }
        }

        // L247 兜底
        return CommandOutcome.CONTINUE;
    }
}

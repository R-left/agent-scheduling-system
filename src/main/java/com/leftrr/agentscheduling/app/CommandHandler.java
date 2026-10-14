package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.NativeSessions;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.Workspace;
import com.leftrr.agentscheduling.dto.app.BotRuntime;
import com.leftrr.agentscheduling.dto.cli.CliSessionSummary;
import com.leftrr.agentscheduling.dto.core.CancelMode;
import com.leftrr.agentscheduling.dto.core.CliRequest;
import com.leftrr.agentscheduling.dto.core.SessionStatus;
import com.leftrr.agentscheduling.dto.core.SlashCommand;
import com.leftrr.agentscheduling.dto.im.IncomingMessage;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.SessionCards;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 斜杠命令处理（/help /team /new /resume /compact /status /cd /close 与引擎守卫），
 * 全部回复文案为产品文案。/new /close 的 IOException 上抛由调用方兜底；
 * /resume /cd 在分支内 catch 转文案，故签名只声明 IOException。
 */
public final class CommandHandler {

    private CommandHandler() {}

    /** HANDLED = 消息已消费、主流程 return；CONTINUE = 继续走主流程。 */
    public enum CommandOutcome { HANDLED, CONTINUE }

    /** command / cliRequest 为 null 表示无对应解析结果。 */
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

        // 非新话题的引擎切换请求：会话已绑定引擎，要求新开话题
        if (!options.isNew() && options.cliRequest() != null
                && options.cliRequest().cliId() != session.cliId()) {
            bot.reply(msg.messageId(),
                    "当前话题已经在使用 " + cliAdapter.displayName()
                            + "。如需切换执行引擎，请新开一个话题。",
                    hasThread);
            return CommandOutcome.HANDLED;
        }

        SlashCommand command = options.command();
        switch (command) {
            case null -> {
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.HELP -> {
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
                List<SessionCards.TeamCardMember> members = new ArrayList<>();
                for (BotRegistry.BotConfig member : runtime.teamRegistry().members()) {
                    BotRuntime memberRuntime = runtime.botRuntimes().get(member.id());
                    // identity() 判空是防御性留置，正常注册即带 identity
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
                if (session.status() == SessionStatus.ACTIVE) {
                    bot.reply(msg.messageId(), "当前任务结束后才能新建会话。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                if (session.status() == SessionStatus.CLOSED) {
                    bot.reply(msg.messageId(), "当前话题的会话已经关闭。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                // 本分支不 catch：IOException 上抛由调用方兜底
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
                    // 失败转文案；中断先恢复标志
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
                // 守卫全过返回 CONTINUE，真正执行在主流程编排
                if (session.status() == SessionStatus.ACTIVE) {
                    bot.reply(msg.messageId(), "当前任务结束后才能整理上下文。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                if (session.status() == SessionStatus.CLOSED) {
                    bot.reply(msg.messageId(), "当前话题的会话已经关闭。", hasThread);
                    return CommandOutcome.HANDLED;
                }
                // null 和空串都算没有可整理的会话
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
                bot.reply(msg.messageId(),
                        SessionView.formatSessionStatus(session, options.config().id()),
                        hasThread);
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.CdCommand cd -> {
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
                    bot.reply(msg.messageId(), "无法切换工作目录：" + e.getMessage(), hasThread);
                }
                return CommandOutcome.HANDLED;
            }
            case SlashCommand.SimpleCommand simple when simple.name()
                    == SlashCommand.SimpleName.CLOSE -> {
                // 运行中任务先置 CLOSE 再取消——写序与 TaskAbort.requestTaskAbort 相反，
                // volatile 写必须先于 cancel()，收尾分支才能读到 CLOSE
                ActiveRun active = runtime.activeRuns().get(session.id());
                if (active != null) {
                    active.setCancelMode(CancelMode.CLOSE); // 必须在 cancel() 之前
                    active.token().cancel();
                }
                if (session.status() != SessionStatus.CLOSED) {
                    // 本分支不 catch：IOException 上抛由调用方兜底
                    runtime.sessions().transition(session.id(), SessionStatus.CLOSED);
                }
                bot.reply(msg.messageId(), "当前会话已关闭。需要继续时，请新开一个话题。", hasThread);
                return CommandOutcome.HANDLED;
            }
            default -> {
                // 六名已全覆盖，仅为 switch 完备性保留
            }
        }

        return CommandOutcome.CONTINUE;
    }
}

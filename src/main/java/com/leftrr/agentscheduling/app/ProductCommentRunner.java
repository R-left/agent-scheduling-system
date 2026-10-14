package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.dto.cli.CliRunResult;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.dto.core.SessionStatus;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档评论改稿：用户在待确认的飞书产品方案文档评论里 @bot，按评论续跑 CLI 精确修改
 * 原文档，最终回答写回原评论。无进度卡（事件回调空操作）；失败抛 RuntimeException
 * （文案为产品文案）。
 */
public final class ProductCommentRunner {

    /** 一次评论改稿所需上下文。 */
    public record ProductDocumentComment(
            AppRuntime runtime,
            Bot bot,
            ProductSpec.ProductSpecFlow flow,
            IncomingDocumentComment comment) {}

    private ProductCommentRunner() {}

    public static void runProductDocumentComment(ProductDocumentComment options)
            throws IOException, InterruptedException {
        Session session = options.runtime().sessions().get(options.flow().sessionId());
        if (session == null || session.status() == SessionStatus.CLOSED) {
            throw new RuntimeException("评论对应的产品会话已经失效");
        }
        if (session.status() != SessionStatus.IDLE) {
            throw new RuntimeException("评论对应的产品会话仍在执行其他任务");
        }
        if (session.cliSessionId() == null || session.cliSessionId().isEmpty()) {
            throw new RuntimeException("评论对应的产品 CLI 会话不存在");
        }
        // adapter 解析放公开入口而非重载内：解析失败对调用方的异常形态不变，收尾清理路径一致
        runProductDocumentComment(options, session, CliAdapterRegistry.get(session.cliId()));
    }

    /** 主体控制流。 */
    static void runProductDocumentComment(ProductDocumentComment options, Session session,
            CliAdapter adapter) throws IOException, InterruptedException {
        CancellationToken run = new CancellationToken();
        options.runtime().sessions().transition(session.id(), SessionStatus.ACTIVE);
        options.runtime().activeRuns().put(session.id(),
                new ActiveRun(run, options.flow().ownerOpenId()));
        try {
            // 无进度卡；prompt 在 try 内求值，lark 守卫 throw 同样走 finally 清理
            CliRunResult result = AppExecutions.executeCli(adapter,
                    documentCommentPrompt(options.flow(), options.comment()),
                    session.workspaceDir(), session.cliSessionId(), run, event -> {});
            // 回写评论
            observeAndReply(options, session, result);
        } finally {
            // activeRuns 只删自己的 run（token 引用相等，防误删他人注册）+ 标记会话空闲
            ActiveRun activeRun = options.runtime().activeRuns().get(session.id());
            if (activeRun != null && activeRun.token() == run) {
                options.runtime().activeRuns().remove(session.id());
            }
            SessionView.markSessionIdle(options.runtime().sessions(), session.id());
        }
    }

    /**
     * 回写 + 回复段：正常路径 answer 恒非空（CliRunner 对空 answer
     * 按真值过滤），兜底文案是防御分支。
     */
    static void observeAndReply(ProductDocumentComment options, Session session,
            CliRunResult result) throws IOException {
        if (result.sessionId() != null && !result.sessionId().isEmpty()) {
            options.runtime().sessions().setCliSessionId(session.id(), result.sessionId());
        }
        if (result.stats() != null && result.stats().contextWindowTokens() != null
                && result.stats().contextWindowTokens() != 0) {
            options.runtime().contextWindows().put(session.id(),
                    result.stats().contextWindowTokens());
        }
        String answer = result.answer() == null || result.answer().isEmpty()
                ? "已按评论更新原文档，请复查。" : result.answer();
        options.bot().replyToDocumentComment(options.comment(), answer);
    }

    /** 评论改稿提示词（产品文案）；replyId 为空则不加该段。 */
    private static String documentCommentPrompt(ProductSpec.ProductSpecFlow flow,
            IncomingDocumentComment comment) {
        if (!(flow.request() instanceof ProductSpec.ProductSpecRequest.Lark lark)) {
            throw new RuntimeException("本地产品方案不能处理飞书文档评论");
        }
        List<String> segments = new ArrayList<>();
        segments.add("用户在待确认的飞书产品方案中通过评论明确提及了你。");
        segments.add("文档 URL：" + lark.documentUrl());
        segments.add("文档类型：" + comment.fileType());
        segments.add("评论 ID：" + comment.commentId());
        if (comment.replyId() != null && !comment.replyId().isEmpty()) {
            segments.add("触发回复 ID：" + comment.replyId());
        }
        segments.add("使用 lark-drive 读取这一条评论、完整回复和正文位置，再使用 lark-doc 精确修改原文档。");
        segments.add("修改成功后，最终回答只写一段给评论者看的简短说明，讲清楚具体改了什么。Agent OS 会把最终回答写回原评论。");
        segments.add("不要调用评论回复或解决接口，评论是否解决由用户复查后决定。");
        segments.add("不要调用 request_spec_approval，不要生成新的确认卡；原待确认卡继续有效。");
        return String.join("\n\n", segments);
    }
}

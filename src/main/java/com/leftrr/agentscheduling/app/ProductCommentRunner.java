package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档评论改稿编排：用户在待确认的飞书产品方案文档的评论中 @bot 后，按评论续跑 CLI 精确修改
 * 原文档，最终回答回写原评论。对齐 TS 版 src/app/product-comment-runner.ts（80 行）。
 *
 * <p>TS→Java：run = new AbortController() → CancellationToken；TS onEvent 空操作
 * （() => undefined，无进度卡）→ 空 Consumer；TS Error throw → RuntimeException（文案逐字）；
 * M6 裁决 1/10：编排函数 throws IOException, InterruptedException，CLI 执行直调
 * AppExecutions.executeCli。lark-doc 判定用 instanceof ProductSpecRequest.Lark（M3 sealed 类型，
 * M6 裁决 3）。</p>
 */
public final class ProductCommentRunner {

    /** 对齐 TS options 对象（product-comment-runner.ts:8-13），字段名逐字。 */
    public record ProductDocumentComment(
            AppRuntime runtime,
            Bot bot,
            ProductSpec.ProductSpecFlow flow,
            IncomingDocumentComment comment) {}

    private ProductCommentRunner() {}

    /** TS runProductDocumentComment（product-comment-runner.ts:8-59）。 */
    public static void runProductDocumentComment(ProductDocumentComment options)
            throws IOException, InterruptedException {
        Session session = options.runtime().sessions().get(options.flow().sessionId());
        // TS 16-18：无会话或已关闭
        if (session == null || session.status() == SessionStatus.CLOSED) {
            throw new RuntimeException("评论对应的产品会话已经失效");
        }
        // TS 19-21
        if (session.status() != SessionStatus.IDLE) {
            throw new RuntimeException("评论对应的产品会话仍在执行其他任务");
        }
        // TS 22-24：TS !session.cliSessionId 的空串 falsy → isEmpty 一并拦截
        if (session.cliSessionId() == null || session.cliSessionId().isEmpty()) {
            throw new RuntimeException("评论对应的产品 CLI 会话不存在");
        }
        // 包私有重载注入 adapter，供测试替身替换（ClarificationRunner 同款测试缝）；
        // 生产路径按注册表解析
        runProductDocumentComment(options, session, CliAdapterRegistry.get(session.cliId()));
    }

    /** 主体控制流（TS 26-58）。session/adapter 由入口解析或测试注入。 */
    static void runProductDocumentComment(ProductDocumentComment options, Session session,
            CliAdapter adapter) throws IOException, InterruptedException {
        // TS 26-31：transition(active) + activeRuns.set（ownerOpenId 取 flow）
        CancellationToken run = new CancellationToken();
        options.runtime().sessions().transition(session.id(), SessionStatus.ACTIVE);
        options.runtime().activeRuns().put(session.id(),
                new ActiveRun(run, options.flow().ownerOpenId()));
        try {
            // TS 33-42：executeCli（onEvent 空操作——无进度卡；documentCommentPrompt 在
            // try 内求值，lark 守卫 throw 同样走 finally 清理，对齐 TS 控制流）
            CliRunResult result = AppExecutions.executeCli(adapter,
                    documentCommentPrompt(options.flow(), options.comment()),
                    session.workspaceDir(), session.cliSessionId(), run, event -> {});
            // TS 43-52：回写 + 回复（包私有 observeAndReply，见其 javadoc）
            observeAndReply(options, session, result);
        } finally {
            // TS 53-58：activeRuns 身份清理（token 引用相等才删，防误删他人注册）+
            // 标记会话空闲（声明上抛 IOException，M6 裁决 1）
            ActiveRun activeRun = options.runtime().activeRuns().get(session.id());
            if (activeRun != null && activeRun.token() == run) {
                options.runtime().activeRuns().remove(session.id());
            }
            SessionView.markSessionIdle(options.runtime().sessions(), session.id());
        }
    }

    /**
     * TS 43-52 的回写 + 回复段（product-comment-runner.ts:43-52）。包私有作测试缝：
     * CliRunner 对空 answer 的 result 事件按真值过滤不落 finalResult（TS runner.ts:111-113
     * 同款行为），executeCli 交付的 answer 恒非空，TS `result.answer || 兜底` 与 Java
     * `answer == null || isEmpty → 兜底`（裁决 12）均为防御分支——子进程替身路径不可达，
     * 仅能经本缝注入构造结果直达。
     */
    static void observeAndReply(ProductDocumentComment options, Session session,
            CliRunResult result) throws IOException {
        // TS 43-45：CLI 会话 ID 回写（TS 真值检查 → null/空串不回写）
        if (result.sessionId() != null && !result.sessionId().isEmpty()) {
            options.runtime().sessions().setCliSessionId(session.id(), result.sessionId());
        }
        // TS 46-48：上下文窗口回写（0 亦为 falsy 不回写）
        if (result.stats() != null && result.stats().contextWindowTokens() != null
                && result.stats().contextWindowTokens() != 0) {
            options.runtime().contextWindows().put(session.id(),
                    result.stats().contextWindowTokens());
        }
        // TS 49-52：result.answer || 兜底文案（裁决 12：null/空串均兜底）
        String answer = result.answer() == null || result.answer().isEmpty()
                ? "已按评论更新原文档，请复查。" : result.answer();
        options.bot().replyToDocumentComment(options.comment(), answer);
    }

    /**
     * TS documentCommentPrompt（product-comment-runner.ts:61-79）。
     * deliveryMode 判定：TS 字符串比较 → instanceof ProductSpecRequest.Lark（M6 裁决 3，
     * parseRequest 构造的 Lark 其 deliveryMode 恒为 "lark-doc"）；
     * 九个数组项 filter(Boolean).join("\n\n")：replyId 空串/null falsy → 该段丢弃。
     */
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

package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.util.StringUtil;
import com.leftrr.agentscheduling.cli.NativeSessions;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.TaskAbort;
import com.leftrr.agentscheduling.dto.app.BotRuntime;
import com.leftrr.agentscheduling.dto.cli.CliSessionSummary;
import com.leftrr.agentscheduling.dto.core.AbortTaskOutcome;
import com.leftrr.agentscheduling.dto.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.dto.core.SessionStatus;
import com.leftrr.agentscheduling.dto.im.CardAction;
import com.leftrr.agentscheduling.dto.im.CardActionResponse;
import com.leftrr.agentscheduling.im.BotOptions;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.ProductSpecCards;
import com.leftrr.agentscheduling.im.SessionCards;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 卡片按钮回调分流：approve_product_spec / answer_clarification / resume_cli_session /
 * abort_task 四分支，其余 action 返回 null；结果一律 toast（+ 可选卡片更新），不发消息。
 *
 * <p>SDK 回调线程不可阻塞、不容受检异常：handler 整体在虚拟线程上跑，join 后同步
 * 返回结果（join 占着 SDK WS 线程，个人系统低并发可接受）。澄清答完的续跑是
 * 分钟级 CLI 执行，交后台虚拟线程，回调先返回。</p>
 */
public final class CardActionHandler {

    /** logback.xml 将本 logger 定向 STDERR。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(CardActionHandler.class);

    public record CardActionHandlerOptions(
            AppRuntime runtime,
            BotRegistry.BotConfig config,
            CollaborationService collaborationService,
            ProductDeliveryMode defaultProductDeliveryMode) {}

    private CardActionHandler() {}

    /** 生产续跑启动器：后台虚拟线程执行续跑，异常只记日志不外抛。 */
    private static final Consumer<ClarificationRunner.ClarificationContinuation>
            PRODUCTION_CONTINUATION_STARTER = continuation -> Thread.ofVirtual().start(() -> {
                try {
                    ClarificationRunner.continueClarificationFlow(continuation);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    LOGGER.error("[澄清] 继续执行失败: {}", error.getMessage());
                } catch (Exception error) {
                    LOGGER.error("[澄清] 继续执行失败: {}", error.getMessage());
                }
            });

    /** 工厂入口：卡片回调统一走虚拟线程，阻塞调用不占 SDK WS 线程。 */
    public static BotOptions.CardActionListener createCardActionHandler(
            CardActionHandlerOptions options) {
        BotOptions.CardActionListener handler =
                action -> handleAction(options, action, PRODUCTION_CONTINUATION_STARTER);
        // handler 内部有阻塞调用（finish 排空、listNativeCliSessions），必须跑在
        // 可阻塞的虚拟线程上；join 占着 SDK WS 线程，低并发可接受
        return action -> {
            AtomicReference<CardActionResponse> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    result.set(handler.onCardAction(action));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            try {
                worker.join();
            } catch (InterruptedException error) {
                // 被中断属异常路径：恢复标志后返回 null
                Thread.currentThread().interrupt();
                return null;
            }
            Throwable error = failure.get();
            if (error != null) {
                // Error 原样重抛——OOM 之类不能被包成 RuntimeException 掩盖类型
                if (error instanceof RuntimeException runtimeError) {
                    throw runtimeError;
                }
                if (error instanceof Error fatal) {
                    throw fatal;
                }
                throw new RuntimeException(error);
            }
            return result.get();
        };
    }

    /** 四分支分流；未知 action 返回 null。 */
    private static CardActionResponse handleAction(CardActionHandlerOptions options,
            CardAction action,
            Consumer<ClarificationRunner.ClarificationContinuation> continuationStarter) {
        return switch (action.value().path("action").asText("")) {
            case "approve_product_spec" -> approveProductSpec(options, action);
            case "answer_clarification" -> answerClarification(options, action, continuationStarter);
            case "resume_cli_session" -> resumeCliSession(options, action);
            case "abort_task" -> abortTask(options, action);
            default -> null;
        };
    }

    // ---- approve_product_spec ----

    private static CardActionResponse approveProductSpec(CardActionHandlerOptions options,
            CardAction action) {
        AppRuntime runtime = options.runtime();
        BotRegistry.BotConfig config = options.config();
        String flowToken = action.value().path("flowToken").asText("");
        ProductSpec.ProductSpecFlow flow = runtime.productSpecFlows().get(flowToken);
        if (flow == null || !flow.botId().equals(config.id())
                || action.messageId() == null || action.messageId().isEmpty()) {
            return toast(CardActionResponse.ToastType.ERROR, "这份产品方案已经失效。");
        }
        if (flow.status() == ProductSpec.SpecStatus.EXPIRED) {
            return new CardActionResponse(
                    new CardActionResponse.Toast(CardActionResponse.ToastType.WARNING,
                            "这份产品方案已经失效。"),
                    ProductSpecCards.buildProductSpecExpiredCard(flow));
        }
        if (flow.status() == ProductSpec.SpecStatus.APPROVED) {
            return new CardActionResponse(
                    new CardActionResponse.Toast(CardActionResponse.ToastType.INFO,
                            "产品方案已经确认。"),
                    ProductSpecCards.buildProductSpecApprovedCard(flow));
        }
        if (!ProductSpec.isProductSpecOwner(flow.ownerOpenId(), flow.ownerUnionId(),
                action.operatorOpenId(), action.operatorUnionId())) {
            return toast(CardActionResponse.ToastType.WARNING, "只有任务发起人可以确认。");
        }
        ProductSpec.ProductSpecFlow approved;
        try {
            approved = runtime.productSpecFlows().approve(flowToken);
        } catch (IOException error) {
            // 内存实现不会失败，但 Json 子类声明了 IOException，须承接
            return toast(CardActionResponse.ToastType.ERROR, errorMessage(error));
        }
        if (approved == null) {
            return toast(CardActionResponse.ToastType.WARNING, "方案状态已经更新。");
        }
        if (approved.collaboration() != null) {
            BotRuntime botRuntime = runtime.botRuntimes().get(config.id());
            if (botRuntime == null) {
                return toast(CardActionResponse.ToastType.ERROR, "当前 Bot 尚未就绪。");
            }
            Collaboration.CollaborationOrigin collaboration = approved.collaboration();
            String productDescription;
            if (approved.request() instanceof ProductSpec.ProductSpecRequest.Lark lark) {
                productDescription = "文档 URL：" + lark.documentUrl();
            } else {
                ProductSpec.ProductSpecRequest.Local local =
                        (ProductSpec.ProductSpecRequest.Local) approved.request();
                productDescription = "Spec：" + local.specPath() + "\nTickets：" + local.ticketsPath();
            }
            Session session = runtime.sessions().get(approved.sessionId());
            String workspaceDir = session != null && session.workspaceDir() != null
                    ? session.workspaceDir() : config.workspaceDir();
            String instruction = String.join("\n\n",
                    config.role() + " 已经完成产品方案，用户确认通过。",
                    "方案标题：" + approved.request().title(),
                    "方案摘要：" + approved.request().summary(),
                    productDescription,
                    "确认记录：" + (approved.approvedAt() != null ? approved.approvedAt() : ""),
                    "请基于这份已确认方案继续组织后续工作：需要开发者实现时，"
                            + "使用 dispatch_task 把方案交给 developer。");
            try {
                options.collaborationService().dispatch(
                        new CollaborationService.CollaborationDispatch(
                                config,
                                botRuntime.bot(),
                                action.messageId(),
                                collaboration.reportToBotId(),
                                collaboration.taskId(),
                                approved.ownerOpenId(),
                                approved.ownerUnionId(),
                                collaboration.reportToBotId(),
                                "产品方案已确认：" + approved.request().title(),
                                instruction,
                                "继续推进原任务，或在已经完成时向用户给出最终结论。",
                                collaboration.round() + 1,
                                collaboration.maxRounds(),
                                workspaceDir));
            } catch (RuntimeException error) {
                return toast(CardActionResponse.ToastType.ERROR,
                        "确认结果交回失败：" + error.getMessage());
            }
        }
        return new CardActionResponse(
                new CardActionResponse.Toast(CardActionResponse.ToastType.SUCCESS,
                        "产品方案已确认。"),
                ProductSpecCards.buildProductSpecApprovedCard(approved));
    }

    // ---- answer_clarification ----

    private static CardActionResponse answerClarification(CardActionHandlerOptions options,
            CardAction action,
            Consumer<ClarificationRunner.ClarificationContinuation> continuationStarter) {
        AppRuntime runtime = options.runtime();
        BotRegistry.BotConfig config = options.config();
        String flowToken = action.value().path("flowToken").asText("");
        String questionId = action.value().path("questionId").asText("");
        Clarification.ClarificationFlow flow = runtime.clarificationFlows().get(flowToken);
        if (flow == null || !flow.botId().equals(config.id())
                || action.messageId() == null || action.messageId().isEmpty()) {
            return toast(CardActionResponse.ToastType.ERROR, "这组澄清问题已经失效。");
        }
        if (!Clarification.isClarificationOwner(flow.ownerOpenId(), flow.ownerUnionId(),
                action.operatorOpenId(), action.operatorUnionId())) {
            return toast(CardActionResponse.ToastType.WARNING, "只有任务发起人可以回答。");
        }
        Clarification.Question question =
                flow.currentIndex() < flow.request().questions().size()
                        ? flow.request().questions().get(flow.currentIndex()) : null;
        if (question == null || !question.id().equals(questionId)) {
            return toast(CardActionResponse.ToastType.WARNING, "问题已经更新，请按当前卡片作答。");
        }

        Clarification.AnswerResult answered;
        String decisionMode = action.value().path("decisionMode").asText("");
        if ("current".equals(decisionMode) || "remaining".equals(decisionMode)) {
            answered = runtime.clarificationFlows()
                    .answerWithRecommendation(flowToken, "remaining".equals(decisionMode));
        } else {
            // asBoolean 会把 "true" 强转，必须 isBoolean 严判
            JsonNode customNode = action.value().path("custom");
            boolean custom = customNode.isBoolean() && customNode.asBoolean();
            String optionId = action.value().path("optionId").asText("");
            Clarification.Option selectedOption = null;
            for (Clarification.Option option : question.options()) {
                if (option.id().equals(optionId)) {
                    selectedOption = option;
                    break;
                }
            }
            JsonNode customAnswerNode = action.formValue().path("custom_answer");
            String customAnswer =
                    customAnswerNode.isTextual() ? StringUtil.trim(customAnswerNode.asText()) : "";
            String answer = custom ? customAnswer
                    : selectedOption != null ? selectedOption.label() : "";
            if (answer.isEmpty()) {
                return toast(CardActionResponse.ToastType.WARNING,
                        custom ? "请先输入你的答案。" : "这个选项已经失效。");
            }
            answered = runtime.clarificationFlows().answer(flowToken, questionId, answer);
        }
        if (answered == null) {
            return toast(CardActionResponse.ToastType.WARNING, "答案没有保存，请重试。");
        }
        if (!answered.complete()) {
            return new CardActionResponse(
                    new CardActionResponse.Toast(CardActionResponse.ToastType.SUCCESS,
                            "已记录，继续下一题。"),
                    ClarificationCards.buildClarificationCard(answered.flow()));
        }

        Session session = runtime.sessions().get(answered.flow().sessionId());
        BotRuntime botRuntime = runtime.botRuntimes().get(config.id());
        if (session == null || botRuntime == null || session.status() == SessionStatus.CLOSED) {
            return toast(CardActionResponse.ToastType.ERROR, "对应的 CLI 会话已经失效。");
        }
        if (session.status() == SessionStatus.ACTIVE) {
            return toast(CardActionResponse.ToastType.WARNING, "当前会话仍在执行，请稍后重试。");
        }
        // 取消令牌声明在 try 外，续跑启动器要用
        CancellationToken run = new CancellationToken();
        try {
            runtime.sessions().transition(session.id(), SessionStatus.ACTIVE);
            runtime.activeRuns().put(session.id(),
                    new ActiveRun(run, answered.flow().ownerOpenId()));
            runtime.clarificationFlows().delete(flowToken);
        } catch (IOException | RuntimeException error) {
            return toast(CardActionResponse.ToastType.ERROR, errorMessage(error));
        }
        // 内存态变更已全部完成，续跑交后台线程；starter 放 try 外调用——它自己抛错
        // 不该误报失败 toast（此时内存态已前进、transition 已生效）
        continuationStarter.accept(new ClarificationRunner.ClarificationContinuation(
                runtime, botRuntime.bot(), config, answered.flow(), run,
                options.defaultProductDeliveryMode()));
        return new CardActionResponse(
                new CardActionResponse.Toast(CardActionResponse.ToastType.SUCCESS,
                        "答案已收到。"),
                ClarificationCards.buildClarificationContinuingCard(answered.flow()));
    }

    // ---- resume_cli_session ----

    private static CardActionResponse resumeCliSession(CardActionHandlerOptions options,
            CardAction action) {
        AppRuntime runtime = options.runtime();
        BotRegistry.BotConfig config = options.config();
        String agentSessionId = action.value().path("agentSessionId").asText("");
        String cliSessionId = action.value().path("cliSessionId").asText("");
        Session session = runtime.sessions().get(agentSessionId);
        if (session == null || !session.botId().equals(config.id()) || cliSessionId.isEmpty()) {
            return toast(CardActionResponse.ToastType.ERROR, "这条会话记录已经失效。");
        }
        if (session.status() == SessionStatus.ACTIVE) {
            return toast(CardActionResponse.ToastType.WARNING, "当前任务结束后才能切换会话。");
        }
        if (session.status() == SessionStatus.CLOSED) {
            return toast(CardActionResponse.ToastType.WARNING, "当前话题的会话已经关闭。");
        }
        try {
            CliAdapter cliAdapter = CliAdapterRegistry.get(session.cliId());
            List<CliSessionSummary> nativeSessions = NativeSessions.listNativeCliSessions(
                    new NativeSessions.Options(cliAdapter, session.workspaceDir()));
            boolean exists = nativeSessions.stream()
                    .anyMatch(item -> item.id().equals(cliSessionId));
            if (!exists) {
                return toast(CardActionResponse.ToastType.ERROR,
                        "这个 CLI 会话已经不在当前工作目录中。");
            }
            Session updated = runtime.sessions().setCliSessionId(session.id(), cliSessionId);
            return new CardActionResponse(
                    new CardActionResponse.Toast(CardActionResponse.ToastType.SUCCESS,
                            "已切换到选中的历史会话。"),
                    SessionCards.buildResumeCard(new SessionCards.ResumeCardOptions(
                            updated.id(), cliAdapter.displayName(), updated.cliSessionId(),
                            nativeSessions)));
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // 恢复标志后仍走同一 toast
            }
            return toast(CardActionResponse.ToastType.ERROR, errorMessage(error));
        }
    }

    // ---- abort_task ----

    private static CardActionResponse abortTask(CardActionHandlerOptions options,
            CardAction action) {
        String sessionId = action.value().path("sessionId").asText("");
        AbortTaskOutcome outcome = TaskAbort.requestTaskAbort(
                options.runtime().activeRuns(), sessionId, action.operatorOpenId());
        return switch (outcome) {
            case NOT_FOUND -> toast(CardActionResponse.ToastType.INFO, "任务已经结束，无需再次停止。");
            case FORBIDDEN -> toast(CardActionResponse.ToastType.WARNING, "无法识别操作者，无法停止任务。");
            case ALREADY_STOPPING -> toast(CardActionResponse.ToastType.INFO, "正在停止任务，请稍候。");
            case STOPPED -> toast(CardActionResponse.ToastType.SUCCESS, "已发送停止指令。");
        };
    }

    private static CardActionResponse toast(CardActionResponse.ToastType type, String content) {
        return new CardActionResponse(new CardActionResponse.Toast(type, content), null);
    }

    /** getMessage() 可能为 null（裸 NPE 之类），toast 会显示字面 "null"——回落 toString()。 */
    private static String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }
}

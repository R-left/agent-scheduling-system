package com.leftrr.agentscheduling.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliSessionSummary;
import com.leftrr.agentscheduling.cli.JsText;
import com.leftrr.agentscheduling.cli.NativeSessions;
import com.leftrr.agentscheduling.core.AbortTaskOutcome;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionStatus;
import com.leftrr.agentscheduling.core.TaskAbort;
import com.leftrr.agentscheduling.im.BotOptions;
import com.leftrr.agentscheduling.im.CardAction;
import com.leftrr.agentscheduling.im.CardActionResponse;
import com.leftrr.agentscheduling.im.ClarificationCards;
import com.leftrr.agentscheduling.im.ProductSpecCards;
import com.leftrr.agentscheduling.im.SessionCards;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 卡片按钮回调处理。对齐 TS 版 src/app/card-action-handler.ts（295 行）：
 * 按 value.action 分流四类回调——approve_product_spec / answer_clarification /
 * resume_cli_session / abort_task，其余 action 返回 null（TS undefined）。
 * 全部分支不发消息：结果一律 toast（+ 可选的卡片 JSON 更新），文案逐字照搬 TS。
 *
 * <p>TS→Java 差异（裁决 1/4/5/12）：</p>
 * <ul>
 * <li>裁决 5：CardActionListener 不容受检异常且 SDK 回调线程不可阻塞——工厂返回的监听器
 *     把 handler 全部逻辑提交到虚拟线程执行并 join 后同步返回结果。join 期间占用 SDK WS
 *     线程，与 TS await 占用等价（低并发个人系统可接受）；resume 分支最长 15s
 *     （listNativeCliSessions 超时）。</li>
 * <li>裁决 4：answer_clarification complete 路径的 queueMicrotask 时序——返回
 *     CardActionResponse 之前完成全部内存态变更（transition/activeRuns.set/flows.delete），
 *     然后交后台虚拟线程续跑 {@code continueClarificationFlow}（内含 2h 级 CLI 执行 + 阻塞
 *     finish，同步执行会挂死卡片回调）；续跑异常 catch-all 只记 stderr（对齐 TS
 *     {@code void promise.catch(console.error)}），不外抛。测试可经包私有重载注入启动器。</li>
 * <li>裁决 12：TS 的 {@code typeof x === 'string' ? x : ''} 类型守卫落为
 *     {@code value.path(key).asText("")}（Jackson asText 对非文本节点返回 ""，恰好对齐）；
 *     {@code formValue.custom_answer} 仅 isTextual 时取值并按 JS 语义 trim
 *     （{@link JsText#jsTrim}）；{@code value.custom === true} 用 isBoolean+asBoolean
 *     严判（asBoolean 会把 "true" 强转，不等价于 TS 严格相等）。</li>
 * <li>受检异常逐点 catch → toast（TS 各 catch 分支文案照搬）；TS 无 catch 的
 *     {@code productSpecFlows.approve}（内存实现不会失败）在 Java 因 Json 子类声明
 *     IOException 也须承接——按通用兜底 toast error(message) 备案。</li>
 * <li>本文件 TS 无 console 输出（无日志需求）；续跑 catch-all 的 stderr 为 TS console.error
 *     的对位（M7 统一切 SLF4J）。</li>
 * </ul>
 */
public final class CardActionHandler {

    /** 对齐 TS options 对象（card-action-handler.ts:22-27）。 */
    public record CardActionHandlerOptions(
            AppRuntime runtime,
            BotRegistry.BotConfig config,
            CollaborationService collaborationService,
            ProductDeliveryMode defaultProductDeliveryMode) {}

    private CardActionHandler() {}

    /**
     * 生产续跑启动器（裁决 4）：后台虚拟线程执行 {@code continueClarificationFlow}，
     * 异常 catch-all 记 stderr（对齐 TS queueMicrotask + void promise.catch(console.error)）。
     */
    private static final Consumer<ClarificationRunner.ClarificationContinuation>
            PRODUCTION_CONTINUATION_STARTER = continuation -> Thread.ofVirtual().start(() -> {
                try {
                    ClarificationRunner.continueClarificationFlow(continuation);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    System.err.println("[澄清] 继续执行失败: " + error.getMessage()); // M7 统一切 SLF4J
                } catch (Exception error) {
                    System.err.println("[澄清] 继续执行失败: " + error.getMessage()); // M7 统一切 SLF4J
                }
            });

    /** TS createCardActionHandler（card-action-handler.ts:22）。 */
    public static BotOptions.CardActionListener createCardActionHandler(
            CardActionHandlerOptions options) {
        return createCardActionHandler(options, PRODUCTION_CONTINUATION_STARTER);
    }

    /**
     * 测试缝（包私有）：注入续跑启动器，替换裁决 4 的后台虚拟线程启动
     * （生产路径走 {@link #PRODUCTION_CONTINUATION_STARTER}）。
     */
    static BotOptions.CardActionListener createCardActionHandler(
            CardActionHandlerOptions options,
            Consumer<ClarificationRunner.ClarificationContinuation> continuationStarter) {
        BotOptions.CardActionListener handler =
                action -> handleAction(options, action, continuationStarter);
        // 裁决 5：整体「虚拟线程执行 + join」包裹。handler 内部（finish 阻塞排空、
        // listNativeCliSessions 等）全部跑在可阻塞的虚拟线程上；join 占用 SDK WS 线程
        // 与 TS await 占用等价。TS promise reject → Java 把异常在调用线程重抛
        // （FeishuBots 的 CustomEventHandler 声明 throws Exception，传播形态对齐）。
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
                // SDK WS 线程被中断属异常路径：恢复中断标记后按 TS undefined 返回
                Thread.currentThread().interrupt();
                return null;
            }
            Throwable error = failure.get();
            if (error != null) {
                throw error instanceof RuntimeException runtimeError
                        ? runtimeError : new RuntimeException(error);
            }
            return result.get();
        };
    }

    /** 四分支分流（TS 29-293 的 if 链；未知 action 返回 null = TS undefined）。 */
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

    // ---- approve_product_spec（TS 30-113） ----

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
            // TS 内存实现 approve 不可能 reject（无 catch 分支）；Java Json 子类声明
            // IOException 须承接——按通用兜底 toast（裁决 1 逐点 catch），无 TS 对应文案
            return toast(CardActionResponse.ToastType.ERROR, error.getMessage());
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
            // TS 三元：deliveryMode === 'lark-doc' → sealed 接口仅 Lark/Local 两实现
            String productDescription;
            if (approved.request() instanceof ProductSpec.ProductSpecRequest.Lark lark) {
                productDescription = "文档 URL：" + lark.documentUrl();
            } else {
                ProductSpec.ProductSpecRequest.Local local =
                        (ProductSpec.ProductSpecRequest.Local) approved.request();
                productDescription = "Spec：" + local.specPath() + "\nTickets：" + local.ticketsPath();
            }
            // TS runtime.sessions.get(approved.sessionId)?.workspaceDir ?? config.workspaceDir
            //（?? 对 nullish 回退：会话存在但 workspaceDir 为 null 同样回落）
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
                // TS catch (error)：Error.message → RuntimeException.getMessage
                return toast(CardActionResponse.ToastType.ERROR,
                        "确认结果交回失败：" + error.getMessage());
            }
        }
        return new CardActionResponse(
                new CardActionResponse.Toast(CardActionResponse.ToastType.SUCCESS,
                        "产品方案已确认。"),
                ProductSpecCards.buildProductSpecApprovedCard(approved));
    }

    // ---- answer_clarification（TS 115-223） ----

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
            // TS action.value.custom === true 严格相等 → isBoolean + asBoolean
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
            // TS typeof action.formValue.custom_answer === 'string' ? .trim() : ''
            JsonNode customAnswerNode = action.formValue().path("custom_answer");
            String customAnswer =
                    customAnswerNode.isTextual() ? JsText.jsTrim(customAnswerNode.asText()) : "";
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
        try {
            runtime.sessions().transition(session.id(), SessionStatus.ACTIVE);
            // TS new AbortController() → per-run 令牌（对齐 M2 CancellationToken 先例）
            CancellationToken run = new CancellationToken();
            runtime.activeRuns().put(session.id(),
                    new ActiveRun(run, answered.flow().ownerOpenId()));
            runtime.clarificationFlows().delete(flowToken);
            // 裁决 4：内存态变更已全部完成，续跑交后台虚拟线程（queueMicrotask 对位）
            continuationStarter.accept(new ClarificationRunner.ClarificationContinuation(
                    runtime, botRuntime.bot(), config, answered.flow(), run,
                    options.defaultProductDeliveryMode()));
            return new CardActionResponse(
                    new CardActionResponse.Toast(CardActionResponse.ToastType.SUCCESS,
                            "答案已收到。"),
                    ClarificationCards.buildClarificationContinuingCard(answered.flow()));
        } catch (IOException | RuntimeException error) {
            // TS catch (error)：transition 抛错的通用兜底（(error as Error).message）
            return toast(CardActionResponse.ToastType.ERROR, error.getMessage());
        }
    }

    // ---- resume_cli_session（TS 225-272） ----

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
            // TS catch (error)：listNativeCliSessions / setCliSessionId 的通用兜底
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // 中断路径恢复标记后仍按 TS catch 分支 toast
            }
            return toast(CardActionResponse.ToastType.ERROR, error.getMessage());
        }
    }

    // ---- abort_task（TS 274-292） ----

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

    /** 仅 toast、无卡片更新的响应（TS { toast: {...} }）。 */
    private static CardActionResponse toast(CardActionResponse.ToastType type, String content) {
        return new CardActionResponse(new CardActionResponse.Toast(type, content), null);
    }
}

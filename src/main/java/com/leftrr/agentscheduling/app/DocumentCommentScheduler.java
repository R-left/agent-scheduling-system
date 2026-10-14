package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 文档评论串行队列：@bot 的评论先过 mentionedBot / 待确认方案 / eventKey 防重三道守卫，
 * 再按 flow.sessionId 排队串行执行，失败回写原评论。执行体是分钟级阻塞 IO
 * （CLI 子进程 + 飞书 REST），统一跑在自持的虚拟线程执行器上——
 * commonPool 的有限平台线程扛不住这种占法。
 *
 * <p>串行性由两件事共同保证：CompletableFuture 链（前序完成才跑后继）+
 * previous 读取与 put 收进同一个 synchronized(this)——并发入队若在 get 与 put 之间
 * 交错，会互读不到对方的前序。回写文案依赖原始异常 message：受检异常包装时保留之，
 * CompletionException 的壳经 {@link #unwrap} 剥掉。</p>
 */
public final class DocumentCommentScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentCommentScheduler.class);

    /** 已处理事件键的保留上限。 */
    static final int MAX_REMEMBERED_DOCUMENT_COMMENT_EVENTS = 1_000;

    private final AppRuntime runtime;
    /** 已处理评论事件键（插入序 FIFO，synchronized(this) 保护）。 */
    private final Set<String> processedDocumentCommentEvents = new LinkedHashSet<>();
    /**
     * 按 flow.sessionId 的串行队列尾。已完成项可能短暂滞留（良性竞态，
     * 见 scheduleDocumentComment 内注释），下一轮 finally 条件删除回收。
     * 声明为 ConcurrentHashMap 而非 Map：清理依赖两参 remove(key, value) 的
     * 原子条件删除，Map 接口的 default 两参 remove 是 get+remove 两步，不原子。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> documentCommentQueues =
            new ConcurrentHashMap<>();
    private final Executor executor;
    private final CommentRunner commentRunner;

    /** 串行队列执行体契约。 */
    @FunctionalInterface
    interface CommentRunner {
        void run(ProductCommentRunner.ProductDocumentComment options)
                throws IOException, InterruptedException;
    }

    /** 生产装配：虚拟线程执行器 + 真实执行体。 */
    public DocumentCommentScheduler(AppRuntime runtime) {
        this.runtime = runtime;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.commentRunner = ProductCommentRunner::runProductDocumentComment;
    }

    /** 同步入口，内部 CompletableFuture 编排。 */
    public void scheduleDocumentComment(BotRegistry.BotConfig config, Bot bot,
            IncomingDocumentComment comment) {
        if (!comment.mentionedBot()) {
            return;
        }
        ProductSpec.ProductSpecFlow flow =
                runtime.productSpecFlows().findPendingByDocument(config.id(), comment.fileToken());
        if (flow == null) {
            LOGGER.info("[产品评论] 忽略未关联待确认方案的评论 file={}", comment.fileToken());
            return;
        }

        // eventKey 防重：同一实例跨 bot 并发回调，check+remember 要在同一锁内
        String eventKey = isPresent(comment.eventId())
                ? comment.eventId()
                : comment.fileToken() + ":" + comment.commentId() + ":"
                        + (comment.replyId() == null ? "" : comment.replyId());
        synchronized (this) {
            if (processedDocumentCommentEvents.contains(eventKey)) {
                return;
            }
            rememberDocumentCommentEvent(eventKey);
        }

        // 处理中表情：失败只 warn 不阻塞
        CompletableFuture<Boolean> workingReaction = CompletableFuture
                .supplyAsync(() -> {
                    bot.setDocumentCommentWorking(comment, true);
                    return true;
                }, executor)
                .exceptionally(error -> {
                    LOGGER.warn("[产品评论] 添加处理中表情失败，继续执行: {}",
                            unwrap(error).getMessage());
                    return false;
                });

        // previous 读取与 put 必须同锁：并发入队在 get/put 之间交错会互读不到
        // 对方的前序，串行性被破坏。失败的前序归一成正常完成，不阻塞后继
        CompletableFuture<Void> queued;
        synchronized (this) {
            CompletableFuture<Void> previous = documentCommentQueues.get(flow.sessionId());
            CompletableFuture<Void> safePrevious = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);

            // allOf 保证表情先行于执行体；执行体异常带原始 message 进 queued
            queued = CompletableFuture
                    .allOf(safePrevious, workingReaction)
                    .thenAcceptAsync(ignored ->
                            runQueued(bot, flow, comment, workingReaction.join()), executor);
            documentCommentQueues.put(flow.sessionId(), queued);
        }

        // 失败回写评论（回写再失败只记日志）
        queued.whenComplete((ignored, error) -> {
            if (error != null) {
                String message = unwrap(error).getMessage();
                LOGGER.error("[产品评论] 处理失败: {}", message);
                try {
                    bot.replyToDocumentComment(comment,
                            "这条评论暂时没有处理完成：" + message);
                } catch (RuntimeException replyError) {
                    LOGGER.error("[产品评论] 回写失败: {}", replyError.getMessage());
                }
            }
            // 条件删除必须原子：get + 单参 remove 两步之间若落进同 sessionId 新任务的
            // put，会把新队列头误删。两参 remove 由 CHM 原子完成，value 按引用相等匹配
            documentCommentQueues.remove(flow.sessionId(), queued);
        });
    }

    /** 排队执行体：跑评论改稿，finally 移除处理中表情（只在表情加上时移）。 */
    private void runQueued(Bot bot, ProductSpec.ProductSpecFlow flow,
            IncomingDocumentComment comment, boolean reactionAdded) {
        try {
            commentRunner.run(
                    new ProductCommentRunner.ProductDocumentComment(runtime, bot, flow, comment));
        } catch (IOException | InterruptedException error) {
            // 受检异常包成 RuntimeException 进 queued，回写文案依赖原始 message；
            // 中断先恢复标志再抛
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(error.getMessage(), error);
        } finally {
            if (reactionAdded) {
                try {
                    bot.setDocumentCommentWorking(comment, false);
                } catch (RuntimeException error) {
                    LOGGER.warn("[产品评论] 移除处理中表情失败: {}", error.getMessage());
                }
            }
        }
    }

    /** 超上限删最早一条（插入序即 FIFO）；synchronized 与 check 同一监视器。 */
    synchronized void rememberDocumentCommentEvent(String eventKey) {
        processedDocumentCommentEvents.add(eventKey);
        if (processedDocumentCommentEvents.size() <= MAX_REMEMBERED_DOCUMENT_COMMENT_EVENTS) {
            return;
        }
        Iterator<String> iterator = processedDocumentCommentEvents.iterator();
        String oldest = iterator.next();
        if (oldest != null) {
            iterator.remove();
        }
    }

    /** null/空串均视为缺失。 */
    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }

    /** 剥掉 CompletionException 的壳取原始异常，否则 message 变成 cause.toString()。 */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

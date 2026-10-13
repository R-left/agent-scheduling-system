package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.IncomingDocumentComment;
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
 * 文档评论串行队列调度。对齐 TS 版 src/index.ts L779-866 的 scheduleDocumentComment 与
 * rememberDocumentCommentEvent（裁决 10 全部要点）：mentionedBot 守卫 →
 * findPendingByDocument 未命中忽略日志 → eventKey 防重（同 eventId/三元组静默）→
 * workingReaction（失败 warn 不阻塞记 false）→ 按 flow.sessionId 串行
 * （Map&lt;String, CompletableFuture&lt;Void&gt;&gt; + allOf(previous, workingReaction)）→
 * 异步段执行 runProductDocumentComment（try/finally 移除表情）→ 处理失败回写评论
 * （回写再失败兜底 log）→ finally 原子条件删除（两参 remove(key, value)，引用相等
 * 防误清新任务，终审 Major-2）。
 *
 * <p>线程模型（brief 第 3 点申报）：TS 同步函数内纯 Promise 编排，then/catch 回调跑在
 * microtask；Java 对位为<b>同步入口 + 内部 CompletableFuture 编排</b>——mentionedBot 检查、
 * findPendingByDocument、eventKey 防重与 TS 同处一个同步段（调用线程直跑），异步段
 * （workingReaction 表情调用、排队执行体、失败回写）统一投递到调度器自持的
 * {@code Executors.newVirtualThreadPerTaskExecutor()}。选虚拟线程而非默认
 * ForkJoinPool 的理由：M7 线程口径（裁决 5/R11）全链虚拟线程；执行体
 * runProductDocumentComment 内是分钟级阻塞 IO（CLI 子进程 + 飞书 REST），默认
 * commonPool 会占死有限平台线程；虚拟线程短命 daemon、无池化，调度器进程级常驻
 * 不 shutdown（对齐 TS 模块级 documentCommentQueues/processedDocumentCommentEvents
 * 常驻状态）。串行性由<b>两件事共同保证</b>（审查 Important-1 措辞修正）：CompletableFuture
 * 链（前序完成才跑后继）+ 入队互斥——previous 读取与 put 收在 {@code synchronized(this)}
 * （同 sessionId 并发调用对齐 TS 单事件循环同段原子性），FIFO 顺序本身依赖调用方
 * 单线程有序到达不变量（R11：WS 回调按到达序投递）。
 * 虚拟线程恒 daemon = TS Promise 不阻止进程退出的等价表达。</p>
 *
 * <p>TS→Java 差异（逐点申报）：</p>
 * <ul>
 * <li>Bot 接口同步化（M6 设计裁决）：TS setDocumentCommentWorking/replyToDocumentComment
 *     返回 Promise → Java 同步 void（失败抛 RuntimeException）。workingReaction 用
 *     supplyAsync 包装 + exceptionally（warn + 记 false，对齐 TS .then(()=&gt;true)
 *     .catch(...)）；汇合点 allOf(previous, workingReaction) 保证表情先行于执行体，
 *     与 TS Promise.all 等待双 Promise 语义一致。TS 构造 workingReaction 即同步发起
 *     REST 调用 → Java 投递到虚拟线程（微小时延，行为等价，汇合点语义不变）。</li>
 * <li>eventKey 空值语义（逐点对照 TS L796-800）：{@code comment.eventId || fallback}
 *     的 falsy = null/undefined/空串 → Java {@code eventId == null || isEmpty} 走
 *     fallback；{@code [fileToken, commentId, replyId].join(':')} 对 null/undefined
 *     元素输出空串（Array.prototype.join 语义）→ Java 对 null replyId 以空串参与拼接
 *     （IncomingDocumentComment javadoc：null/空串同表 TS 空串根评论）。fileToken/
 *     commentId 在评论事件里恒存在，无 null 分支。</li>
 * <li>processedDocumentCommentEvents 的 has→remember 原子性：TS check 与 remember 位于
 *     同一同步段天然原子；Java 多 bot 并发回调（同一 scheduler 实例跨 bot 共享，
 *     对齐 TS 模块级单例状态），synchronized(this) 包住 check+remember 对齐同段语义；
 *     LinkedHashSet 插入序当 FIFO，超 {@link #MAX_REMEMBERED_DOCUMENT_COMMENT_EVENTS}
 *     删最早一条（对齐 TS values().next()）。</li>
 * <li>测试缝（brief 第 4 点二选一申报）：runProductDocumentComment 是静态方法，选
 *     <b>可注入函数字段</b>（包私有 {@link CommentRunner} 构造注入，Task 3
 *     adapterResolver 同款先例）——串行队列用例需以 latch 精确控制执行体起停，函数
 *     注入比包私有静态重载更直接。生产构造默认绑
 *     {@code ProductCommentRunner::runProductDocumentComment}。</li>
 * <li>异常对位：TS await reject → Java 执行体受检异常包成 RuntimeException 进 queued
 *     （<b>保留原始 message</b>——回写文案 {@code 这条评论暂时没有处理完成：${message}}
 *     逐字对齐；InterruptedException 同法包装，CF 任务内无中断源，语义等价）。
 *     CompletableFuture 的 CompletionException 包装层经 {@link #unwrap} 剥壳取 cause，
 *     对齐 TS {@code (error as Error).message} 取原始消息。</li>
 * <li>良性竞态备案：queued 可能先于 map.put 完成并触发 whenComplete 的条件删除（TS
 *     微任务时序下 set 恒先于 finally 回调，该路径不可达；Java 最坏遗留一条已完成
 *     队列项当 head，行为等价于 Promise.resolve 前序，由下一轮 finally 条件删除回收，
 *     无正确性影响）。</li>
 * <li>回写兜底 catch（TS .catch(replyError)）按 ㊀ 备案惯例 catch RuntimeException
 *     （TS catch-all 与 Java RuntimeException 的既有口径差异，同 [协作] 派发失败）。</li>
 * </ul>
 */
public final class DocumentCommentScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentCommentScheduler.class);

    /** TS MAX_REMEMBERED_DOCUMENT_COMMENT_EVENTS = 1_000（index.ts L103）。 */
    static final int MAX_REMEMBERED_DOCUMENT_COMMENT_EVENTS = 1_000;

    private final AppRuntime runtime;
    /** 已处理评论事件键（插入序 FIFO，synchronized(this) 保护），对齐 TS Set 防重。 */
    private final Set<String> processedDocumentCommentEvents = new LinkedHashSet<>();
    /**
     * 按 flow.sessionId 的串行队列尾，对齐 TS documentCommentQueues。
     * 备案（审查 Minor-2）：已完成队列项可能滞留 map（queued 先于 put 完成的良性竞态
     * 窗口，见类 javadoc），有界仅一轮——滞留项行为等价于 Promise.resolve 前序，由
     * 下一轮同 sessionId 任务的 finally 条件删除回收。
     *
     * <p>声明为 ConcurrentHashMap 而非 Map 接口（终审 Major-2）：finally 清理依赖两参
     * {@code remove(key, value)} 的<b>原子</b>条件删除，Map 接口的 default 两参 remove
     * 是 get+remove 两步非原子实现，原子语义须由实现类型保证。</p>
     */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> documentCommentQueues =
            new ConcurrentHashMap<>();
    private final Executor executor;
    private final CommentRunner commentRunner;

    /** runProductDocumentComment 的注入缝（见类 javadoc 测试缝申报）。 */
    @FunctionalInterface
    interface CommentRunner {
        void run(ProductCommentRunner.ProductDocumentComment options)
                throws IOException, InterruptedException;
    }

    /** 生产构造：虚拟线程执行器 + 真实 runProductDocumentComment（Task 5 Main 装配）。 */
    public DocumentCommentScheduler(AppRuntime runtime) {
        this(runtime, Executors.newVirtualThreadPerTaskExecutor(),
                ProductCommentRunner::runProductDocumentComment);
    }

    /** 包私有重载：执行器与执行体可注入（测试替身，Task 3 先例）。 */
    DocumentCommentScheduler(AppRuntime runtime, Executor executor, CommentRunner commentRunner) {
        this.runtime = runtime;
        this.executor = executor;
        this.commentRunner = commentRunner;
    }

    /** TS scheduleDocumentComment（index.ts L779-856）：同步入口，内部 CompletableFuture 编排。 */
    public void scheduleDocumentComment(BotRegistry.BotConfig config, Bot bot,
            IncomingDocumentComment comment) {
        // L784
        if (!comment.mentionedBot()) {
            return;
        }
        // L785-794
        ProductSpec.ProductSpecFlow flow =
                runtime.productSpecFlows().findPendingByDocument(config.id(), comment.fileToken());
        if (flow == null) {
            LOGGER.info("[产品评论] 忽略未关联待确认方案的评论 file={}", comment.fileToken());
            return;
        }

        // L796-802 eventKey 防重（has→remember 同步段原子性，见类 javadoc）
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

        // L804-812 workingReaction：失败 warn 不阻塞，记 false（exceptionally 吞掉继续）
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

        // L813-814 + L837 入队段（审查 Important-1）：previous 读取与 put 收进同一
        // synchronized(this)（与 eventKey 防重同一锁对象）——同 sessionId 并发调用若在
        // get 与 put 之间交错，两者会互读不到对方的前序、串行性被破坏；TS 单事件循环下
        // 该段天然原子，Java 以互斥对齐。块内无阻塞调用（get/put 与 allOf 链构建均非
        // 阻塞，thenAcceptAsync 仅登记依赖不执行体）。
        // previous == null → TS ?? Promise.resolve()（completedFuture）；
        // previous.catch(() => undefined) → exceptionally(e -> null)，失败前序不再阻塞后继。
        CompletableFuture<Void> queued;
        synchronized (this) {
            CompletableFuture<Void> previous = documentCommentQueues.get(flow.sessionId());
            CompletableFuture<Void> safePrevious = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);

            // L815-836 排队：allOf(previous, workingReaction) 后异步段执行体（try/finally
            // 移除表情）；执行体异常进 queued（保留 message，见类 javadoc）
            queued = CompletableFuture
                    .allOf(safePrevious, workingReaction)
                    .thenAcceptAsync(ignored ->
                            runQueued(bot, flow, comment, workingReaction.join()), executor);
            // L837
            documentCommentQueues.put(flow.sessionId(), queued);
        }

        // L838-855 处理失败回写（回写再失败兜底 log）+ finally 条件删除（引用相等才删，
        // 防新任务入队后误清）
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
            // 终审 Major-2：原 get + 单参 remove 两步非原子且不在 synchronized(this) 内——
            // 同 sessionId 新任务的 put 若落在 get 与 remove 之间，单参 remove 会无条件
            // 误删新任务队列头，打穿裁决 10「条件 remove 防误清」。改两参
            // remove(key, value)：ConcurrentHashMap 原子条件删除，value 匹配以引用相等
            // 判定（CompletableFuture 未覆写 equals），语义等价于「get == queued 才删」
            // 且消除竞态窗口，无需锁。
            documentCommentQueues.remove(flow.sessionId(), queued);
        });
    }

    /**
     * L818-835 排队执行体：runProductDocumentComment + finally 移除表情（reactionAdded
     * 才移除，移除失败 warn 不吞原始异常，对齐 TS finally 内 .catch）。
     */
    private void runQueued(Bot bot, ProductSpec.ProductSpecFlow flow,
            IncomingDocumentComment comment, boolean reactionAdded) {
        try {
            commentRunner.run(
                    new ProductCommentRunner.ProductDocumentComment(runtime, bot, flow, comment));
        } catch (IOException | InterruptedException error) {
            // TS await reject 对位：进 queued 由 catch 分支回写；保留原始 message（文案逐字）。
            // 中断恢复标志（审查 Minor-4）：包装上抛会丢中断状态，先补置再抛
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

    /**
     * L858-866 rememberDocumentCommentEvent：LinkedHashSet 插入序当 FIFO，超 MAX 删最早
     * 一条。synchronized（check→remember 同步段同一监视器，重入安全；测试亦直调）。
     */
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

    /** 已处理事件查询（测试观测缝，与 remember 同一监视器）。 */
    synchronized boolean hasRememberedEvent(String eventKey) {
        return processedDocumentCommentEvents.contains(eventKey);
    }

    /** 队列头查询（测试观测缝：串行性 / 误清防护断言）。 */
    CompletableFuture<Void> queueHead(String sessionId) {
        return documentCommentQueues.get(sessionId);
    }

    /** TS !!string：null/空串为假（eventId || 的 falsy 口径）。 */
    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * 剥掉 CompletableFuture 的 CompletionException 包装层取原始异常，对齐 TS
     * {@code (error as Error).message}（不剥则 message 变成 cause.toString()，回写/日志
     * 文案被打穿）。
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

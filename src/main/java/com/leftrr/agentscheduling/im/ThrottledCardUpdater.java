package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** 一秒窗口内无论 push 多少次，只提交最新的一张卡片。
 *
 * <p>对齐 TS 版 src/im/card.ts:978-1033 ThrottledCardUpdater。调度与执行注入（计划设计裁决 4）：
 * 不用 setTimeout/wait-notify；串行化对齐 TS updateChain（多次 flush 的 update 依次提交到
 * 同一 updateExecutor，由单线程执行器保证按序），状态保护用 ReentrantLock（M3 裁决 7 同源，
 * 规避虚拟线程 pin）。
 *
 * <p>与 TS 的差异（均已逐条核对 card.ts:978-1033）：
 * <ul>
 *   <li>TS 的 updateChain 是 Promise 链；Java 侧以「在途计数 + updateExecutor」等价表达——
 *       flushPending 不检查在途状态、无条件入队（对齐 TS 在 busy 时也继续链式排队），
 *       多个排队 update 全部完成后才算排空（对齐 TS finish/cancel await 整条链）。</li>
 *   <li>TS finish 的 {@code await this.updateCard(finalCard)} 在 finish 自己的续体上执行、
 *       异常向上传播；Java 侧 finish 在排空后于调用线程直接执行 finalCard（未经执行器），
 *       异常同样向上传播。brief 注释原设想「afterUpdate 提交 finalCard 后 countDown」，
 *       若经执行器提交则异常被吞、finish 也无法等其执行完，与 TS 不符，故改为 finish 侧直提
 *       （此时 closed=true、在途已排空，无并发窗口，语义等价）。</li>
 * </ul> */
public final class ThrottledCardUpdater {
    private final Consumer<ObjectNode> updateCard;
    private final CardScheduler scheduler;
    private final Executor updateExecutor;
    private final long intervalMs;
    private final ReentrantLock lock = new ReentrantLock();

    private ObjectNode pendingCard;
    private CardScheduler.Cancellable timer;
    private boolean closed;
    private int inFlightCount;          // 已提交给 updateExecutor 但尚未执行完的 update 数（对齐 TS updateChain 链长）
    private CountDownLatch drainLatch;  // finish/cancel 等待在途 update 排空；排空时由 afterUpdate countDown

    /** 生产构造：updateCard 为卡片提交函数，scheduler 提供窗口定时，updateExecutor 串行执行
     * update（生产用单线程执行器），intervalMs 为窗口宽度。 */
    public ThrottledCardUpdater(Consumer<ObjectNode> updateCard, CardScheduler scheduler,
            Executor updateExecutor, long intervalMs) {
        this.updateCard = updateCard;
        this.scheduler = scheduler;
        this.updateExecutor = updateExecutor;
        this.intervalMs = intervalMs;
    }

    /** 测试便捷构造：updateExecutor 用 Runnable::run 直接执行（调用线程内联）+ 默认 1000ms 窗口。
     * 生产用四参构造显式注入单线程 executor；本类不隐式建线程池（R9 裁决）。 */
    public ThrottledCardUpdater(Consumer<ObjectNode> updateCard, CardScheduler scheduler) {
        this(updateCard, scheduler, Runnable::run, 1_000);
    }

    /** card.ts:989-993。closed 后静默忽略（card.ts:990）。 */
    public void push(ObjectNode card) {
        lock.lock();
        try {
            if (closed) return;                 // card.ts:990
            pendingCard = card;                 // card.ts:991
            scheduleLocked();                   // card.ts:992
        } finally {
            lock.unlock();
        }
    }

    /** card.ts:995-1003：置 closed、取消挂起窗口、丢弃 pending、阻塞等在途 update 全部完成后
     * 在调用线程提交 finalCard（不经窗口）。finalCard 的 updateCard 异常向上传播（对齐 TS
     * {@code await this.updateCard(finalCard)}）。幂等：closed 后直接返回（card.ts:996）。 */
    public void finish(ObjectNode finalCard) {
        CountDownLatch drained;
        lock.lock();
        try {
            if (closed) return;                 // card.ts:996
            closed = true;                      // card.ts:997
            cancelTimerLocked();                // card.ts:998-999
            pendingCard = null;                 // card.ts:1000
            drained = armDrainLocked();
        } finally {
            lock.unlock();
        }
        if (drained != null) {
            awaitDrained(drained);              // card.ts:1001 await this.updateChain.catch(...)
        }
        updateCard.accept(finalCard);           // card.ts:1002
    }

    /** card.ts:1005-1012：同 finish 但不提交 finalCard，只等在途 update 排空。幂等（card.ts:1006）。 */
    public void cancel() {
        CountDownLatch drained;
        lock.lock();
        try {
            if (closed) return;                 // card.ts:1006
            closed = true;                      // card.ts:1007
            cancelTimerLocked();                // card.ts:1008-1009
            pendingCard = null;                 // card.ts:1010
            drained = armDrainLocked();
        } finally {
            lock.unlock();
        }
        if (drained != null) {
            awaitDrained(drained);              // card.ts:1011
        }
    }

    /** 若仍有在途 update，布下排空 latch 供调用方阻塞等待，返回该 latch；否则返回 null。须持锁调用。 */
    private CountDownLatch armDrainLocked() {
        if (inFlightCount > 0) {
            drainLatch = new CountDownLatch(1);
            return drainLatch;
        }
        return null;
    }

    private void awaitDrained(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;         // TS 无中断语义；这里吞中断但保留标记，排空后再恢复
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // card.ts:1014-1020（setTimeout 前先查重）
    private void scheduleLocked() {
        if (timer != null) return;              // card.ts:1015
        timer = scheduler.schedule(this::flushPending, intervalMs);
    }

    private void cancelTimerLocked() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 窗口回调。须持锁取卡；即便已有 update 在途也照常入队（对齐 TS updateChain 链式排队，
     * 顺序由单线程 updateExecutor 保证）。 */
    private void flushPending() {
        ObjectNode card;
        lock.lock();
        try {
            timer = null;                       // card.ts:1017
            card = pendingCard;
            pendingCard = null;
            if (card == null || closed) return; // card.ts:1025
            inFlightCount++;
        } finally {
            lock.unlock();
        }
        try {
            updateExecutor.execute(() -> {
                try {
                    updateCard.accept(card);
                } catch (RuntimeException ignored) {
                    // 对齐 TS updateChain.catch(() => undefined)（card.ts:1001/1011 的链上吞异常）
                } finally {
                    afterUpdate();
                }
            });
        } catch (RuntimeException e) {
            // 提交失败（如执行器已关闭）：回滚在途计数，避免 finish/cancel 永久等待排空
            afterUpdate();
            throw e;
        }
    }

    /** update 执行完毕后的收尾，等价 card.ts:1029-1031 的 .finally：若又有 pending 且未 closed
     * 则再排一窗（card.ts:1030）；在途计数清零时放行 finish/cancel 的阻塞等待。 */
    private void afterUpdate() {
        CountDownLatch drained;
        lock.lock();
        try {
            inFlightCount--;
            if (inFlightCount == 0 && drainLatch != null) {
                drained = drainLatch;
                drainLatch = null;
            } else {
                drained = null;
            }
            if (pendingCard != null && !closed) {
                scheduleLocked();               // card.ts:1030
            }
        } finally {
            lock.unlock();
        }
        if (drained != null) {
            drained.countDown();
        }
    }
}

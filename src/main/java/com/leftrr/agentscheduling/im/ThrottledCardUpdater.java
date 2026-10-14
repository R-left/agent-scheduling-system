package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** 一秒窗口内 push 多少次，只发最新的一张卡。
 *
 * <p>排队的 update 全部走 updateExecutor，单线程执行器保证按序，不会互相覆盖；
 * 状态用 ReentrantLock（synchronized 会钉死虚拟线程）。
 * finish 的 finalCard 在调用线程直接提交、不经执行器——走了执行器的话
 * 异常会被吞，finish 也没法等它执行完。 */
public final class ThrottledCardUpdater {
    private final Consumer<ObjectNode> updateCard;
    private final CardScheduler scheduler;
    private final Executor updateExecutor;
    private final long intervalMs;
    private final ReentrantLock lock = new ReentrantLock();

    private ObjectNode pendingCard;
    private CardScheduler.Cancellable timer;
    private boolean closed;
    private int inFlightCount;          // 已提交给 updateExecutor 但尚未执行完的 update 数
    private CountDownLatch drainLatch;  // finish/cancel 等待在途 update 排空；排空时由 afterUpdate countDown

    /** 生产构造。updateExecutor 必须是单线程的——按序全靠它。本类不偷偷建线程池，由调用方显式注入。 */
    public ThrottledCardUpdater(Consumer<ObjectNode> updateCard, CardScheduler scheduler,
            Executor updateExecutor, long intervalMs) {
        this.updateCard = updateCard;
        this.scheduler = scheduler;
        this.updateExecutor = updateExecutor;
        this.intervalMs = intervalMs;
    }

    /** 推入待发卡片；closed 后静默忽略。 */
    public void push(ObjectNode card) {
        lock.lock();
        try {
            if (closed) return;
            pendingCard = card;
            scheduleLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 置 closed、等在途 update 排空，再在调用线程提交 finalCard（不经窗口）；
     * finalCard 的异常向上抛。幂等。 */
    public void finish(ObjectNode finalCard) {
        CountDownLatch drained;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            cancelTimerLocked();
            pendingCard = null;
            drained = armDrainLocked();
        } finally {
            lock.unlock();
        }
        if (drained != null) {
            awaitDrained(drained);
        }
        updateCard.accept(finalCard);
    }

    /** 同 finish 但不提交 finalCard，只等在途 update 排空。幂等。 */
    public void cancel() {
        CountDownLatch drained;
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            cancelTimerLocked();
            pendingCard = null;
            drained = armDrainLocked();
        } finally {
            lock.unlock();
        }
        if (drained != null) {
            awaitDrained(drained);
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
                    interrupted = true;         // 吞中断但保留标记，排空后再恢复中断标记
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void scheduleLocked() {
        if (timer != null) return;
        timer = scheduler.schedule(this::flushPending, intervalMs);
    }

    private void cancelTimerLocked() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** 窗口回调。即便已有 update 在途也照常入队，顺序交给单线程执行器。 */
    private void flushPending() {
        ObjectNode card;
        lock.lock();
        try {
            timer = null;
            card = pendingCard;
            pendingCard = null;
            if (card == null || closed) return;
            inFlightCount++;
        } finally {
            lock.unlock();
        }
        try {
            updateExecutor.execute(() -> {
                try {
                    updateCard.accept(card);
                } catch (RuntimeException ignored) {
                    // update 异常按约定吞掉，但在途计数收尾不能丢
                } finally {
                    afterUpdate();
                }
            });
        } catch (RuntimeException e) {
            // 提交失败（执行器已关）：回滚在途计数，否则 finish/cancel 会永远等下去
            afterUpdate();
            throw e;
        }
    }

    /** 在途计数清零时放行 finish/cancel；有新 pending 且未 closed 就再排一窗。 */
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
                scheduleLocked();
            }
        } finally {
            lock.unlock();
        }
        if (drained != null) {
            drained.countDown();
        }
    }
}

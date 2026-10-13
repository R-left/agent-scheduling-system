package com.leftrr.agentscheduling.im;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 1：ScheduledCardScheduler（CardScheduler 生产实现）测试。
 * 语义对齐 TS 版 setTimeout/clearTimeout：一次性 delay 调度 + 可取消句柄。
 */
class ScheduledCardSchedulerTest {

    private final CardScheduler scheduler = ScheduledCardScheduler.getInstance();

    // case01：schedule 后到期执行，且调度线程为 daemon（进程生命周期、不阻止 JVM 退出）
    @Test
    void scheduledTaskRunsAfterDelayOnDaemonThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean daemon = new AtomicBoolean(false);

        scheduler.schedule(() -> {
            daemon.set(Thread.currentThread().isDaemon());
            latch.countDown();
        }, 50);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "到期任务应在延迟后执行");
        assertTrue(daemon.get(), "调度线程应为 daemon（对齐 TS timer unref 语义）");
    }

    // case02：cancel 后不执行。①：delay 取 2s——schedule→cancel 相邻语句间隙出现
    // 2s 级停顿（GC/线程饥饿）才可能让任务先跑，消除 150ms 时的理论抢占竞态；
    // 观察窗口须覆盖整个 delay（2.5s > 2s），cancel 失效时任务必然落窗被断言捕获
    @Test
    void cancelledTaskDoesNotRun() {
        CountDownLatch latch = new CountDownLatch(1);
        CardScheduler.Cancellable cancellable = scheduler.schedule(latch::countDown, 2_000);

        cancellable.cancel();

        try {
            assertFalse(latch.await(2_500, TimeUnit.MILLISECONDS), "取消后的任务不得执行");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // case03：Cancellable.cancel 幂等（重复 cancel 不抛、任务仍不执行）。
    // 与 case02 同款竞态，同用 2s delay 消除
    @Test
    void cancelIsIdempotent() {
        CountDownLatch latch = new CountDownLatch(1);
        CardScheduler.Cancellable cancellable = scheduler.schedule(latch::countDown, 2_000);

        cancellable.cancel();
        assertDoesNotThrow(cancellable::cancel);

        try {
            assertFalse(latch.await(2_500, TimeUnit.MILLISECONDS), "取消后的任务不得执行");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // case04：静态工厂返回进程级共享单例
    @Test
    void factoryReturnsSharedSingleton() {
        assertSame(ScheduledCardScheduler.getInstance(), ScheduledCardScheduler.getInstance());
        assertSame(scheduler, ScheduledCardScheduler.getInstance());
    }
}

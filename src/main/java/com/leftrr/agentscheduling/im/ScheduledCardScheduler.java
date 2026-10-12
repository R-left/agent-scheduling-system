package com.leftrr.agentscheduling.im;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link CardScheduler} 的生产实现：进程级单例 daemon 单线程
 * ScheduledExecutorService，一次性 delay 调度（对齐 TS setTimeout/clearTimeout）。
 *
 * <p>进程生命周期：不提供 shutdown——TS 版的 timer 线程同为进程级常驻；
 * 线程设为 daemon，不阻止 JVM 正常退出（对齐 TS heartbeat.unref() 的语义）。
 *
 * <p>cancel 包装 {@link ScheduledFuture#cancel(boolean)}（传 false：不中断
 * 已开始执行的任务），Future.cancel 天然幂等。
 */
public final class ScheduledCardScheduler implements CardScheduler {

    private static final ScheduledCardScheduler INSTANCE = new ScheduledCardScheduler();

    private final ScheduledExecutorService executor;

    private ScheduledCardScheduler() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scheduled-card-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 进程级单例静态工厂。 */
    public static CardScheduler getInstance() {
        return INSTANCE;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs) {
        ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }
}

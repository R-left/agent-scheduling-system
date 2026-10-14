package com.leftrr.agentscheduling.im;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * CardScheduler 的生产实现：进程级单例 daemon 单线程调度器。
 * 不提供 shutdown——线程是 daemon，不阻止 JVM 正常退出。
 * cancel 走 future.cancel(false)：不中断已开始执行的任务，天然幂等。
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

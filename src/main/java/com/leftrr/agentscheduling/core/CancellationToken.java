package com.leftrr.agentscheduling.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协作式取消令牌，对应 TS 版 Node AbortController/AbortSignal 的本项目用法。
 * 虚拟线程安全：CAS 原子取消；监听器注册/触发不使用 synchronized（避免 pin 载体线程）。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 原子取消。返回 true 表示本次调用完成了取消（并发下只有一个赢家）。 */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        for (Runnable listener : new ArrayList<>(listeners)) {
            runSafely(listener);
        }
        return true;
    }

    /** 注册取消监听器；若已取消则立即执行一次。 */
    public void onCancel(Runnable listener) {
        if (cancelled.get()) {
            runSafely(listener);
            return;
        }
        listeners.add(listener);
        if (cancelled.get() && listeners.remove(listener)) {
            runSafely(listener); // 注册与取消并发时补一次，保证恰好执行
        }
    }

    private static void runSafely(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            // 对应 Node AbortSignal：单个监听器异常不阻断其他监听器
        }
    }
}

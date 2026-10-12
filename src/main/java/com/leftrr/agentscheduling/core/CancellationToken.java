package com.leftrr.agentscheduling.core;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协作式取消令牌，对应 TS 版 Node AbortController/AbortSignal 的本项目用法。
 * 虚拟线程安全：CAS 原子取消；监听器注册/触发不使用 synchronized（避免 pin 载体线程）。
 * 每个监听器恰好执行一次由 per-listener fired CAS 保证（零触发与双重触发均不可达）。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    private static final class ListenerRegistration {
        final Runnable action;
        final AtomicBoolean fired = new AtomicBoolean();

        ListenerRegistration(Runnable action) {
            this.action = action;
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 原子取消。返回 true 表示本次调用完成了取消（并发下只有一个赢家）。 */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        for (ListenerRegistration registration : new ArrayList<>(listeners)) {
            fireOnce(registration);
        }
        return true;
    }

    /** 注册取消监听器；若已取消则立即执行一次。恰好一次由 fired CAS 保证：
     * 若注册先于取消快照，快照遍历触发；否则本方法末尾读到 cancelled 后触发。 */
    public void onCancel(Runnable listener) {
        ListenerRegistration registration = new ListenerRegistration(listener);
        listeners.add(registration);
        if (cancelled.get()) {
            fireOnce(registration);
        }
    }

    private static void fireOnce(ListenerRegistration registration) {
        if (registration.fired.compareAndSet(false, true)) {
            runSafely(registration.action);
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

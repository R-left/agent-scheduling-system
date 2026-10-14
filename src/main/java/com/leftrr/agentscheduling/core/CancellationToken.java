package com.leftrr.agentscheduling.core;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协作式取消令牌。
 * 全程不用 synchronized（会把虚拟线程钉在载体线程上）；取消用 CAS 原子完成，
 * 每个监听器恰好执行一次由 per-listener fired CAS 保证。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    /**
     * 一次取消监听器注册的句柄。刻意不重写 equals/hashCode：
     * 移除按同一实例（identity）匹配，避免值相等误删别的注册。
     */
    public final class ListenerRegistration {
        final Runnable action;
        final AtomicBoolean fired = new AtomicBoolean();

        ListenerRegistration(Runnable action) {
            this.action = action;
        }

        /** 按同一实例移除注册（取消前移除 = 该监听器不再触发）。 */
        public void remove() {
            listeners.remove(this);
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

    /** 注册取消监听器；若已取消则立即触发一次。恰好一次靠 fired CAS：
     * 注册先于取消快照则随快照遍历触发，否则这里末尾读到 cancelled 后触发。 */
    public ListenerRegistration register(Runnable listener) {
        ListenerRegistration registration = new ListenerRegistration(listener);
        listeners.add(registration);
        if (cancelled.get()) {
            fireOnce(registration);
        }
        return registration;
    }

    /** 注册入口（返回句柄可忽略）。 */
    public void onCancel(Runnable listener) {
        register(listener);
    }

    /** 按同一实例移除注册（取消前移除 = 该监听器不再触发）。 */
    public void removeListener(ListenerRegistration registration) {
        listeners.remove(registration);
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
            // 单个监听器异常不阻断其他监听器
        }
    }
}

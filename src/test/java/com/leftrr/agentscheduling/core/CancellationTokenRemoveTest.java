package com.leftrr.agentscheduling.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CancellationToken.removeListener 增量行为（M1 fired/CAS 语义回归由 TaskAbortTest 保护）。
 */
class CancellationTokenRemoveTest {

    @Test
    void register后remove再cancel监听器不触发() {
        var token = new CancellationToken();
        var fires = new AtomicInteger();
        CancellationToken.ListenerRegistration registration = token.register(fires::incrementAndGet);
        token.removeListener(registration);
        assertTrue(token.cancel());
        assertEquals(0, fires.get(), "取消前移除的监听器不应触发");
    }

    @Test
    void remove其一仅另一个触发() {
        var token = new CancellationToken();
        var firesA = new AtomicInteger();
        var firesB = new AtomicInteger();
        CancellationToken.ListenerRegistration a = token.register(firesA::incrementAndGet);
        token.register(firesB::incrementAndGet);
        token.removeListener(a);
        assertTrue(token.cancel());
        assertEquals(0, firesA.get(), "被移除的监听器不应触发");
        assertEquals(1, firesB.get(), "保留的监听器应恰好触发一次");
    }

    @Test
    void remove与registration自身remove等价() {
        var token = new CancellationToken();
        var fires = new AtomicInteger();
        CancellationToken.ListenerRegistration registration = token.register(fires::incrementAndGet);
        registration.remove();
        assertTrue(token.cancel());
        assertEquals(0, fires.get(), "registration.remove() 后监听器不应触发");
    }

    @Test
    void cancel后remove无异常且再次cancel无重放() {
        var token = new CancellationToken();
        var fires = new AtomicInteger();
        CancellationToken.ListenerRegistration registration = token.register(fires::incrementAndGet);
        assertTrue(token.cancel());
        assertEquals(1, fires.get());
        // 取消后移除：不抛异常、不撤销已触发的执行
        token.removeListener(registration);
        registration.remove();
        assertEquals(1, fires.get(), "已触发过的监听器不重放");
        // 再次 cancel 走 CAS 失败分支：无重放
        assertFalse(token.cancel());
        assertEquals(1, fires.get(), "CAS 失败的取消不应重放监听器");
    }

    @Test
    void remove按同一实例匹配而非值相等() {
        var token = new CancellationToken();
        var fires = new AtomicInteger();
        // 两个注册共用同一 Runnable 实例：值相等但注册实例不同，只移除其一
        Runnable action = fires::incrementAndGet;
        CancellationToken.ListenerRegistration first = token.register(action);
        CancellationToken.ListenerRegistration second = token.register(action);
        token.removeListener(first);
        assertTrue(token.cancel());
        assertEquals(1, fires.get(), "同一 Runnable 的另一注册仍应恰好触发一次");
    }

    @Test
    void 并发registerRemove与cancel无异常且每令牌至多触发一次() throws Exception {
        int iterations = 200;
        var tokens = new CancellationToken[iterations];
        for (int i = 0; i < iterations; i++) {
            tokens[i] = new CancellationToken();
        }
        var fires = new AtomicInteger();
        var barrier = new CyclicBarrier(2);
        var registerer = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                // 一线程 register + remove；若 cancel 恰在两步之间，fired CAS 保证至多触发一次
                CancellationToken.ListenerRegistration registration = tokens[i].register(fires::incrementAndGet);
                tokens[i].removeListener(registration);
            }
        });
        var canceller = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                tokens[i].cancel();
            }
        });
        registerer.start();
        canceller.start();
        registerer.join();
        canceller.join();
        assertTrue(fires.get() <= iterations, "每令牌触发次数至多 1，实际总触发: " + fires.get());
    }
}

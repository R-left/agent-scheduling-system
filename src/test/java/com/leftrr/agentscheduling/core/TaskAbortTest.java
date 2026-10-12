package com.leftrr.agentscheduling.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAbortTest {
    @Test
    void returnsNotFoundForUnknownSession() {
        assertEquals(AbortTaskOutcome.NOT_FOUND,
                TaskAbort.requestTaskAbort(Map.of(), "s1", "ou_1"));
    }

    @Test
    void forbidsBlankOperator() {
        var token = new CancellationToken();
        var runs = new java.util.HashMap<String, ActiveRun>();
        runs.put("s1", new ActiveRun(token, "ou_1"));
        assertEquals(AbortTaskOutcome.FORBIDDEN,
                TaskAbort.requestTaskAbort(runs, "s1", ""));
    }

    @Test
    void stopsActiveRunAndSetsCancelMode() {
        var token = new CancellationToken();
        var runs = new java.util.HashMap<String, ActiveRun>();
        runs.put("s1", new ActiveRun(token, "ou_1"));
        assertEquals(AbortTaskOutcome.STOPPED,
                TaskAbort.requestTaskAbort(runs, "s1", "ou_1"));
        assertTrue(token.isCancelled());
        assertEquals(CancelMode.STOP, runs.get("s1").cancelMode());
    }

    @Test
    void secondAbortReportsAlreadyStopping() {
        var runs = new java.util.HashMap<String, ActiveRun>();
        runs.put("s1", new ActiveRun(new CancellationToken(), "ou_1"));
        TaskAbort.requestTaskAbort(runs, "s1", "ou_1");
        assertEquals(AbortTaskOutcome.ALREADY_STOPPING,
                TaskAbort.requestTaskAbort(runs, "s1", "ou_1"));
    }

    @Test
    void concurrentAbortsYieldSingleStopped() throws Exception {
        var runs = new java.util.HashMap<String, ActiveRun>();
        runs.put("s1", new ActiveRun(new CancellationToken(), "ou_1"));
        int threads = 16;
        var outcomes = new CopyOnWriteArrayList<AbortTaskOutcome>();
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    outcomes.add(TaskAbort.requestTaskAbort(runs, "s1", "ou_1"));
                    return null;
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(threads, outcomes.size());
        assertEquals(1, outcomes.stream().filter(o -> o == AbortTaskOutcome.STOPPED).count());
        assertEquals(threads - 1,
                outcomes.stream().filter(o -> o == AbortTaskOutcome.ALREADY_STOPPING).count());
    }

    @Test
    void concurrentRegisterCancelFiresExactlyOnce() throws Exception {
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
                tokens[i].onCancel(fires::incrementAndGet);
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
        assertEquals(iterations, fires.get());
    }

    @Test
    void cancelFiresListenersOnce() {
        var token = new CancellationToken();
        var fires = new AtomicInteger();
        token.onCancel(fires::incrementAndGet);
        token.onCancel(fires::incrementAndGet);
        assertTrue(token.cancel());
        token.onCancel(fires::incrementAndGet); // 已取消后注册：立即执行一次
        assertEquals(3, fires.get());
    }
}

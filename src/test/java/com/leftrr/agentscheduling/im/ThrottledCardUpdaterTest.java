package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/card.ts:978-1033 ThrottledCardUpdater
 * （M4 Task 8 brief Step 1，用例编号 1-13 与 brief 一一对应；
 * fake scheduler 收集任务、List&lt;Runnable&gt; 手动泵，executor 用
 * 「记录待执行 Runnable」的手动执行器以便断言串行化）。
 */
class ThrottledCardUpdaterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode card(String id) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        return node;
    }

    /** fake scheduler：记录 schedule 调用与 delayMs，cancel 记录被取消的任务，由测试手动泵。 */
    private static final class FakeScheduler implements CardScheduler {
        record Scheduled(Runnable task, long delayMs) {}

        final List<Scheduled> scheduled = new ArrayList<>();
        final List<Runnable> cancelled = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable task, long delayMs) {
            scheduled.add(new Scheduled(task, delayMs));
            return () -> cancelled.add(task);
        }

        void fire(int index) {
            scheduled.get(index).task().run();
        }
    }

    /** 手动执行器：只记录待执行 Runnable，不真正执行，由测试按序泵出。 */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runNext() {
            queue.remove(0).run();
        }

        void drain() {
            while (!queue.isEmpty()) {
                runNext();
            }
        }
    }

    private static void awaitThreadWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline || thread.getState() == Thread.State.TERMINATED) {
                throw new AssertionError("thread never blocked, state=" + thread.getState());
            }
            Thread.sleep(5);
        }
    }

    // ---- brief 用例 1：两参构造默认 intervalMs=1000 ----

    @Test
    void case01_twoArgConstructorSchedulesWithDefault1000msDelay() {
        FakeScheduler scheduler = new FakeScheduler();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(c -> {}, scheduler);

        updater.push(card("a"));

        assertEquals(1, scheduler.scheduled.size());
        assertEquals(1000, scheduler.scheduled.get(0).delayMs());
    }

    // ---- brief 用例 2：窗口未触发 → 零调用 ----

    @Test
    void case02_pushBeforeWindowFiresDoesNotCallUpdateCard() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler);

        updater.push(card("a"));

        assertTrue(calls.isEmpty());
    }

    // ---- brief 用例 3：触发窗口 → 恰 1 次、参数为最后 push 的卡 ----

    @Test
    void case03_windowFlushSubmitsOnlyLatestPushedCard() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        updater.push(card("b"));
        updater.push(card("c"));
        scheduler.fire(0);
        executor.drain();

        assertEquals(1, calls.size());
        assertEquals("c", calls.get(0).get("id").asText());
    }

    // ---- brief 用例 4：窗口内 push 3 次 → 触发后仍只 1 次调用（节流） ----

    @Test
    void case04_threePushesInWindowThrottledToSingleCall() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        updater.push(card("b"));
        updater.push(card("c"));
        scheduler.fire(0);
        executor.drain();

        assertEquals(1, calls.size());
    }

    // ---- brief 用例 5：flush 完成后又有新卡 → 再排一窗（card.ts:1030 .finally 分支） ----

    @Test
    void case05_newPendingAfterFlushSchedulesNextWindowWithSameDelay() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        scheduler.fire(0);          // flush：取走 a、timer=null、提交 update #1
        updater.push(card("b"));    // pending=b，此时 timer 已清空 → 排第二窗
        executor.runNext();         // update a 完成 → afterUpdate：pending=b 但 timer 已在 → 不得重复排窗

        assertEquals(2, scheduler.scheduled.size());
        assertEquals(1000, scheduler.scheduled.get(0).delayMs());
        assertEquals(1000, scheduler.scheduled.get(1).delayMs());

        scheduler.fire(1);          // flush b
        executor.runNext();         // update b 完成

        assertEquals(List.of("a", "b"), calls.stream().map(c -> c.get("id").asText()).toList());
    }

    // ---- brief 用例 6：无新 pending → flush 后不再 schedule ----

    @Test
    void case06_noNewPendingAfterFlushDoesNotReschedule() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        scheduler.fire(0);
        executor.runNext();

        assertEquals(1, scheduler.scheduled.size());
        assertEquals(1, calls.size());
    }

    // ---- brief 用例 7：finish → 取消窗口、丢弃 pending、立即提交 finalCard、幂等 ----

    @Test
    void case07_finishCancelsTimerDiscardsPendingAndDeliversFinalCardIdempotently() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        updater.finish(card("final"));

        assertEquals(1, scheduler.cancelled.size(), "挂起窗口应被 cancel");
        assertTrue(scheduler.scheduled.get(0).task() == scheduler.cancelled.get(0),
                "被取消的应是原定时任务");
        assertEquals(1, calls.size(), "finalCard 立即提交、不经窗口");
        assertEquals("final", calls.get(0).get("id").asText());
        assertTrue(executor.queue.isEmpty(), "pending 卡 a 应被丢弃，不进入执行器");

        updater.finish(card("final2"));
        assertEquals(1, calls.size(), "二次 finish 幂等，不再提交");
    }

    // ---- brief 用例 8：finish 后 push → 静默忽略 ----

    @Test
    void case08_pushAfterFinishIsSilentlyIgnored() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler);

        updater.finish(card("final"));
        updater.push(card("x"));

        assertEquals(1, calls.size());
        assertEquals("final", calls.get(0).get("id").asText());
        assertTrue(scheduler.scheduled.isEmpty(), "finish 后 push 不得再调度");
    }

    // ---- brief 用例 9：cancel → 丢弃 pending、不发 finalCard、取消窗口、幂等 ----

    @Test
    void case09_cancelDiscardsPendingSendsNoFinalCardAndIsIdempotent() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        updater.cancel();

        assertEquals(1, scheduler.cancelled.size(), "挂起窗口应被 cancel");
        assertTrue(calls.isEmpty(), "cancel 不发 finalCard");
        assertTrue(executor.queue.isEmpty(), "pending 卡 a 应被丢弃");

        updater.cancel();
        assertTrue(calls.isEmpty(), "二次 cancel 幂等");
    }

    // ---- brief 用例 10：cancel 后 push → 静默忽略 ----

    @Test
    void case10_pushAfterCancelIsSilentlyIgnored() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler);

        updater.cancel();
        updater.push(card("x"));

        assertTrue(calls.isEmpty());
        assertTrue(scheduler.scheduled.isEmpty(), "cancel 后 push 不得再调度");
    }

    // ---- brief 用例 11：updateCard 抛异常 → 不冒泡、后续窗口仍正常（card.ts .catch） ----

    @Test
    void case11_updateCardExceptionSwallowedAndLaterWindowsStillWork() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        // 首次调用抛异常（记录后抛），之后正常
        Consumer<ObjectNode> flaky = c -> {
            calls.add(c);
            if (calls.size() == 1) {
                throw new RuntimeException("boom");
            }
        };
        ThrottledCardUpdater updater = new ThrottledCardUpdater(flaky, scheduler, executor, 1000);

        updater.push(card("a"));
        scheduler.fire(0);
        executor.runNext();         // 若异常冒泡，此处直接抛出、afterUpdate（finally）也不会跑

        updater.push(card("b"));
        scheduler.fire(1);
        executor.runNext();

        assertEquals(List.of("a", "b"), calls.stream().map(c -> c.get("id").asText()).toList());
    }

    // ---- brief 用例 12：串行化——第一个 update 滞留时第二个排队不并发（updateChain） ----

    @Test
    void case12_secondWindowUpdateQueuesBehindInFlightOneInOrder() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        scheduler.fire(0);          // flush a → update #1 进入手动执行器滞留
        updater.push(card("b"));
        scheduler.fire(1);          // flush b → update #2 排队

        assertEquals(2, executor.queue.size(), "两个 update 均滞留在执行器中排队，无并发执行");
        assertTrue(calls.isEmpty());

        executor.runNext();
        assertEquals(List.of("a"), calls.stream().map(c -> c.get("id").asText()).toList());
        executor.runNext();
        assertEquals(List.of("a", "b"), calls.stream().map(c -> c.get("id").asText()).toList(),
                "按序执行，对齐 TS updateChain");
    }

    // ---- brief 用例 13：finish 阻塞等在途 update 完成后再提交 finalCard ----

    @Test
    void case13_finishBlocksUntilInFlightUpdateCompletesThenDeliversFinalCard() throws Exception {
        FakeScheduler scheduler = new FakeScheduler();
        List<ObjectNode> calls = new ArrayList<>();
        ManualExecutor executor = new ManualExecutor();
        ThrottledCardUpdater updater = new ThrottledCardUpdater(calls::add, scheduler, executor, 1000);

        updater.push(card("a"));
        scheduler.fire(0);          // update #1 滞留在手动执行器

        Thread finisher = new Thread(() -> updater.finish(card("final")));
        finisher.start();
        awaitThreadWaiting(finisher);   // finish 阻塞在 latch 上
        assertTrue(calls.isEmpty(), "在途 update 未完成前 finalCard 不得提交");
        assertTrue(finisher.isAlive());

        executor.runNext();         // 在途 update a 执行完 → afterUpdate 清零 latch → finish 返回
        finisher.join(5_000);
        assertFalse(finisher.isAlive(), "finish 应在在途 update 完成后返回");

        assertEquals(List.of("a", "final"), calls.stream().map(c -> c.get("id").asText()).toList(),
                "finalCard 排在该 update 之后，顺序正确");
    }
}

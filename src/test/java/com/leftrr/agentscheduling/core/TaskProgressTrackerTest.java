package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5：TaskProgressTracker 测试（对齐 TS 版 src/core/task-progress.ts 语义）。
 * now 用 AtomicLong 注入，事件直接 new M2 CliEvent record。
 */
class TaskProgressTrackerTest {

    private static CliEvent.ToolStartEvent start(String id, String toolName, String label, String detail) {
        return new CliEvent.ToolStartEvent(id, toolName, label, detail);
    }

    private static CliEvent.ToolEndEvent end(String id, boolean failed) {
        return new CliEvent.ToolEndEvent(id, failed);
    }

    // 1. 初始 snapshot
    @Test
    void initialSnapshotIsUnderstandingTask() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        var snap = tracker.snapshot();
        assertEquals("正在理解任务", snap.current());
        assertNull(snap.currentToolName());
        assertNull(snap.currentDetail());
        assertNull(snap.contextUsedTokens());
        assertNull(snap.contextStartTokens());
        assertNull(snap.contextWindowTokens());
        assertNull(snap.startedNewSession());
        assertEquals(0, snap.elapsedMs());
        assertEquals(0, snap.toolCount());
        assertEquals(0, snap.completedCount());
        assertTrue(snap.activities().isEmpty());
    }

    // 2. context 事件：首个同时写 start/used；第二个只更新 used（??= 语义）
    @Test
    void contextEventsKeepFirstStartTokens() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        tracker.accept(new CliEvent.ContextEvent(100));
        assertEquals(100L, tracker.snapshot().contextStartTokens());
        assertEquals(100L, tracker.snapshot().contextUsedTokens());
        tracker.accept(new CliEvent.ContextEvent(200));
        assertEquals(100L, tracker.snapshot().contextStartTokens());
        assertEquals(200L, tracker.snapshot().contextUsedTokens());
    }

    // 3. tool_start：计数与 current 字段；detail 为 null → currentDetail 为 null
    @Test
    void toolStartUpdatesCurrent() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        var snap = tracker.accept(start("t1", "Bash", "运行命令", "ls -la"));
        assertEquals(1, snap.toolCount());
        assertEquals("运行命令", snap.current());
        assertEquals("Bash", snap.currentToolName());
        assertEquals("ls -la", snap.currentDetail());

        var tracker2 = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        var snap2 = tracker2.accept(start("t1", "Read", "读取文件", null));
        assertEquals("读取文件", snap2.current());
        assertEquals("Read", snap2.currentToolName());
        assertNull(snap2.currentDetail());
    }

    // 4. tool_end：活动 unshift 到首位、时长、failed 透传、completedCount、activeTools 移除
    @Test
    void toolEndRecordsActivityAndAnalysisCurrent() {
        var clock = new AtomicLong(1000);
        var tracker = new TaskProgressTracker(clock::get, null, false);
        tracker.accept(start("t1", "Bash", "运行命令", "ls -la"));
        clock.set(1500);
        var snap = tracker.accept(end("t1", true));
        assertEquals(1, snap.completedCount());
        assertEquals(1, snap.activities().size());
        var activity = snap.activities().get(0);
        assertEquals("Bash", activity.toolName());
        assertEquals("运行命令", activity.label());
        assertEquals("ls -la", activity.detail());
        assertEquals(500, activity.durationMs());
        assertTrue(activity.failed());
        assertEquals("正在分析执行结果", snap.current());
        assertNull(snap.currentToolName());

        // failed=false 透传
        var clock2 = new AtomicLong(2000);
        var tracker2 = new TaskProgressTracker(clock2::get, null, false);
        tracker2.accept(start("t2", "Read", "读取文件", null));
        clock2.set(2500);
        var snap2 = tracker2.accept(end("t2", false));
        assertTrue(snap2.activities().get(0).failed() == false);
        assertEquals(500, snap2.activities().get(0).durationMs());
    }

    // 5. tool_end 未知 toolUseId → 忽略
    @Test
    void unknownToolEndIsIgnored() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        tracker.accept(start("t1", "Bash", "运行命令", null));
        var snap = tracker.accept(end("nope", true));
        assertEquals(0, snap.completedCount());
        assertTrue(snap.activities().isEmpty());
        assertEquals("运行命令", snap.current());
    }

    // 6. 并发多工具：current 取最后插入且未结束者（LinkedHashMap 插入序 at(-1)）
    @Test
    void concurrentToolsCurrentIsLastStarted() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        tracker.accept(start("t1", "Bash", "命令一", null));
        var snap = tracker.accept(start("t2", "Read", "文件二", null));
        assertEquals("文件二", snap.current());
        assertEquals("Read", snap.currentToolName());
        var snapAfterEnd = tracker.accept(end("t2", false));
        assertEquals("命令一", snapAfterEnd.current());
        assertEquals("Bash", snapAfterEnd.currentToolName());
    }

    // 7. 同 id 重复 start：重 put 保留最新值
    @Test
    void sameIdRestartKeepsLatestValue() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        tracker.accept(start("t1", "Bash", "第一次", null));
        tracker.accept(end("t1", false));
        var snap = tracker.accept(start("t1", "Read", "第二次", null));
        assertEquals("第二次", snap.current());
        assertEquals(2, snap.toolCount());
        assertEquals(1, snap.completedCount());
    }

    // 8. activities 容量 12：unshift + splice(12)，头部是最新活动
    @Test
    void activitiesCappedAtTwelve() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        for (int i = 1; i <= 15; i++) {
            tracker.accept(start("t" + i, "Tool" + i, "活动" + i, null));
            tracker.accept(end("t" + i, false));
        }
        List<TaskActivity> activities = tracker.snapshot().activities();
        assertEquals(12, activities.size());
        assertEquals("活动15", activities.get(0).label());
        assertEquals("活动4", activities.get(11).label());
    }

    // 9. 时长钳制：now 倒退 → durationMs=0、elapsedMs=0
    @Test
    void clockRegressionClampsToZero() {
        var clock = new AtomicLong(1000);
        var tracker = new TaskProgressTracker(clock::get, null, false);
        tracker.accept(start("t1", "Bash", "运行命令", null));
        clock.set(500);
        var snap = tracker.accept(end("t1", false));
        assertEquals(0, snap.activities().get(0).durationMs());
        assertEquals(0, snap.elapsedMs());
    }

    // 10. 构造参数透传：contextWindowTokens、startedNewSession
    @Test
    void constructorArgsArePassedThrough() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, 200_000L, true);
        var snap = tracker.snapshot();
        assertEquals(200_000L, snap.contextWindowTokens());
        assertEquals(Boolean.TRUE, snap.startedNewSession());

        var defaultTracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        assertNull(defaultTracker.snapshot().startedNewSession());
    }

    // 11. snapshot 的 activities 是拷贝
    @Test
    void snapshotActivitiesAreACopy() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        tracker.accept(start("t1", "Bash", "第一次", null));
        tracker.accept(end("t1", false));
        List<TaskActivity> before = tracker.snapshot().activities();
        int beforeSize = before.size();

        tracker.accept(start("t2", "Read", "第二次", null));
        tracker.accept(end("t2", false));

        assertEquals(beforeSize, before.size());
        assertEquals("第一次", before.get(0).label());
        assertEquals(2, tracker.snapshot().activities().size());
    }

    // 13. currentDetail 与活动 detail 空串对齐 TS falsy：省略字段（null）
    @Test
    void emptyDetailOmittedFromCurrentDetailAndActivity() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        tracker.accept(start("t1", "Bash", "运行命令", ""));
        var snap = tracker.accept(end("t1", false));
        assertNull(snap.currentDetail(), "currentDetail 空串应省略");
        assertNull(snap.activities().get(0).detail(), "活动 detail 空串应省略");
        // 非空 detail 不受影响：active 在跑时 currentDetail 透传，结束后 activities 透传
        var tracker2 = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        var started = tracker2.accept(start("t1", "Read", "读取文件", "a.md"));
        assertEquals("a.md", started.currentDetail());
        var ended = tracker2.accept(end("t1", false));
        assertEquals("a.md", ended.activities().get(0).detail());
    }

    // 14. active.label null 回落兜底文案（?? 只回落 null/undefined），空串不回落
    @Test
    void nullActiveLabelFallsBackButEmptyDoesNot() {
        var tracker = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        var snap = tracker.accept(start("t1", "Bash", null, "ls -la"));
        assertEquals("正在分析执行结果", snap.current(), "label null 应回落兜底文案");
        assertEquals(1, snap.toolCount());

        var tracker2 = new TaskProgressTracker(new AtomicLong(1000)::get, null, false);
        var snap2 = tracker2.accept(start("t1", "Bash", "", null));
        assertEquals("", snap2.current(), "label 空串是合法值不回落");
    }

    // 12. accept 返回值即当次 snapshot
    @Test
    void acceptReturnsCurrentSnapshot() {
        var clock = new AtomicLong(1000);
        var tracker = new TaskProgressTracker(clock::get, 42L, true);
        clock.set(1250);
        TaskProgressSnapshot fromAccept = tracker.accept(new CliEvent.ContextEvent(777));
        assertEquals(tracker.snapshot(), fromAccept);
    }
}

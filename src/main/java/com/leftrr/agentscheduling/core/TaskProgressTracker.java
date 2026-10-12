package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 聚合 CLI 事件流为进度快照。对应 TS 版 src/core/task-progress.ts。
 * activeTools 用 LinkedHashMap：对齐 TS Map 的插入序语义（at(-1) = 最后插入且未结束者）。
 */
public final class TaskProgressTracker {
    private static final int ACTIVITY_LIMIT = 12; // 对齐 activities.splice(12)

    private final LongSupplier now;
    private final Long contextWindowTokens;
    private final boolean startedNewSession;
    private final long startedAt;
    private final Map<String, ActiveTool> activeTools = new LinkedHashMap<>();
    private final List<TaskActivity> activities = new ArrayList<>();
    private int toolCount;
    private int completedCount;
    private Long contextUsedTokens;
    private Long contextStartTokens;

    private record ActiveTool(String toolName, String label, String detail, long startedAt) {}

    public TaskProgressTracker() {
        this(System::currentTimeMillis, null, false);
    }

    public TaskProgressTracker(LongSupplier now, Long contextWindowTokens, boolean startedNewSession) {
        this.now = now;
        this.contextWindowTokens = contextWindowTokens;
        this.startedNewSession = startedNewSession;
        this.startedAt = now.getAsLong();
    }

    public TaskProgressSnapshot accept(CliEvent event) {
        if (event instanceof CliEvent.ContextEvent e) {
            if (contextStartTokens == null) { // 对齐 ??=
                contextStartTokens = e.usedTokens();
            }
            contextUsedTokens = e.usedTokens();
        } else if (event instanceof CliEvent.ToolStartEvent e) {
            toolCount += 1;
            activeTools.put(e.toolUseId(),
                    new ActiveTool(e.toolName(), e.label(), e.detail(), now.getAsLong()));
        } else if (event instanceof CliEvent.ToolEndEvent e) {
            ActiveTool tool = activeTools.remove(e.toolUseId());
            if (tool != null) {
                completedCount += 1;
                activities.add(0, new TaskActivity(tool.toolName(), tool.label(), tool.detail(),
                        Math.max(0, now.getAsLong() - tool.startedAt()), e.failed()));
                if (activities.size() > ACTIVITY_LIMIT) {
                    activities.subList(ACTIVITY_LIMIT, activities.size()).clear(); // 对齐 splice(12)
                }
            }
        }
        return snapshot();
    }

    public TaskProgressSnapshot snapshot() {
        ActiveTool active = null;
        for (ActiveTool tool : activeTools.values()) {
            active = tool; // 对齐 [...values()].at(-1)
        }
        String current = active != null
                ? active.label()
                : toolCount > 0 ? "正在分析执行结果" : "正在理解任务";
        return new TaskProgressSnapshot(
                current,
                active != null ? active.toolName() : null,
                active != null && active.detail() != null ? active.detail() : null,
                Math.max(0, now.getAsLong() - startedAt),
                toolCount,
                completedCount,
                List.copyOf(activities),
                contextUsedTokens,
                contextStartTokens,
                contextWindowTokens,
                startedNewSession ? Boolean.TRUE : null);
    }
}

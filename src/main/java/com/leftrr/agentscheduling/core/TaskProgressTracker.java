package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.dto.cli.CliEvent;
import com.leftrr.agentscheduling.dto.core.TaskActivity;
import com.leftrr.agentscheduling.dto.core.TaskProgressSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 把 CLI 事件流聚合成进度快照。
 */
public final class TaskProgressTracker {
    private static final int ACTIVITY_LIMIT = 12;

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

    // accept 在任务虚拟线程上被调（CLI 事件回调），snapshot 在心跳线程被调，得互斥。
    // 用 synchronized 就够：临界区极短、无阻塞 IO。
    public synchronized TaskProgressSnapshot accept(CliEvent event) {
        if (event instanceof CliEvent.ContextEvent e) {
            if (contextStartTokens == null) {
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
                String detail = tool.detail() != null && !tool.detail().isEmpty()
                        ? tool.detail() : null;
                activities.add(0, new TaskActivity(tool.toolName(), tool.label(), detail,
                        Math.max(0, now.getAsLong() - tool.startedAt()), e.failed()));
                if (activities.size() > ACTIVITY_LIMIT) {
                    activities.subList(ACTIVITY_LIMIT, activities.size()).clear();
                }
            }
        }
        return snapshot();
    }

    public synchronized TaskProgressSnapshot snapshot() {
        ActiveTool active = null;
        for (ActiveTool tool : activeTools.values()) {
            active = tool; // LinkedHashMap 保插入序，遍历到最后就是当前工具
        }
        String current = active != null && active.label() != null
                ? active.label()
                : toolCount > 0 ? "正在分析执行结果" : "正在理解任务";
        return new TaskProgressSnapshot(
                current,
                active != null ? active.toolName() : null,
                // 空串 detail 视为无详情 → null
                active != null && active.detail() != null && !active.detail().isEmpty()
                        ? active.detail() : null,
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

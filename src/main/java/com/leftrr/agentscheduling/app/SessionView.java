package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.SessionStatus;

import java.io.IOException;
import java.util.Map;

/**
 * /status 文本格式化 + 执行完成后标记会话空闲。对齐 TS 版 src/app/session-view.ts（33 行）。
 */
public final class SessionView {

    /** 对齐 TS STATUS_LABELS：四状态中文标签逐字。 */
    private static final Map<SessionStatus, String> STATUS_LABELS = Map.of(
            SessionStatus.CREATING, "创建中",
            SessionStatus.ACTIVE, "执行中",
            SessionStatus.IDLE, "空闲",
            SessionStatus.CLOSED, "已关闭");

    private SessionView() {}

    /**
     * TS formatSessionStatus（session-view.ts:11-23）：8 行输出结构逐字。
     * TS→Java：TS 的 `session.cliSessionId ?? "(尚未建立)"` 走 null 判断
     * （null 即 TS 的 undefined，空串语义本就原样透传）。
     */
    public static String formatSessionStatus(Session session, String botId) {
        CliAdapter adapter = CliAdapterRegistry.get(session.cliId());
        return String.join("\n",
                "机器人：" + botId,
                "会话：" + session.id(),
                "状态：" + STATUS_LABELS.get(session.status()),
                "执行引擎：" + adapter.displayName(),
                "CLI 会话：" + (session.cliSessionId() != null
                        ? session.cliSessionId() : "(尚未建立)"),
                "工作目录：" + session.workspaceDir(),
                "话题：" + session.threadId(),
                "更新时间：" + session.updatedAt());
    }

    /**
     * TS markSessionIdle（session-view.ts:25-32）：仅 active 会话切换 idle。
     * TS→Java：TS 的 `sessions.get(sessionId)?.status !== "active"` 拆为
     * 「get 返回 null 或状态非 ACTIVE 都直接 return」；transition 的受检 IOException
     * 显式上抛（M7 兜底 catch 依赖此签名，调研 §1.4）；日志用 System.out（M7 切 SLF4J）。
     */
    public static void markSessionIdle(SessionManager sessions, String sessionId)
            throws IOException {
        Session session = sessions.get(sessionId);
        if (session == null || session.status() != SessionStatus.ACTIVE) {
            return;
        }
        sessions.transition(sessionId, SessionStatus.IDLE);
        System.out.println("[会话] id=" + sessionId + " status=idle");
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.core.Session;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.dto.core.SessionStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * /status 文本格式化 + 执行完成后标记会话空闲。
 */
public final class SessionView {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionView.class);

    /** 四状态中文标签（产品文案）。 */
    private static final Map<SessionStatus, String> STATUS_LABELS = Map.of(
            SessionStatus.CREATING, "创建中",
            SessionStatus.ACTIVE, "执行中",
            SessionStatus.IDLE, "空闲",
            SessionStatus.CLOSED, "已关闭");

    private SessionView() {}

    /** /status 输出（产品文案）；CLI 会话 ID 为 null 显示 "(尚未建立)"。 */
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

    /** 仅 active 会话切 idle，其余直接返回；IOException 上抛——主流程兜底依赖此签名。 */
    public static void markSessionIdle(SessionManager sessions, String sessionId)
            throws IOException {
        Session session = sessions.get(sessionId);
        if (session == null || session.status() != SessionStatus.ACTIVE) {
            return;
        }
        sessions.transition(sessionId, SessionStatus.IDLE);
        LOGGER.info("[会话] id={} status=idle", sessionId);
    }
}

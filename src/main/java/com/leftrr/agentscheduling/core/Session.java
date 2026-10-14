package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leftrr.agentscheduling.dto.cli.CliId;
import com.leftrr.agentscheduling.dto.core.SessionStatus;

/**
 * 话题会话。
 * cliSessionId 为 null 表示缺省（序列化时省略）。
 * record 分量顺序 = 持久化 JSON 键序：cliSessionId 由后续更新追加、恒位于末尾。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Session(
        String id,
        String botId,
        String threadId,
        String chatId,
        CliId cliId,
        String workspaceDir,
        SessionStatus status,
        String createdAt,
        String updatedAt,
        String cliSessionId) {

    public Session withStatus(SessionStatus nextStatus, String updatedAt) {
        return new Session(id, botId, threadId, chatId, cliId, workspaceDir,
                nextStatus, createdAt, updatedAt, cliSessionId);
    }

    public Session withCliSessionId(String cliSessionId, String updatedAt) {
        return new Session(id, botId, threadId, chatId, cliId, workspaceDir,
                status, createdAt, updatedAt, cliSessionId);
    }

    /** 换工作目录同时清空 CLI 会话选择。 */
    public Session withWorkspaceDir(String workspaceDir, String updatedAt) {
        return new Session(id, botId, threadId, chatId, cliId, workspaceDir,
                status, createdAt, updatedAt, null);
    }
}

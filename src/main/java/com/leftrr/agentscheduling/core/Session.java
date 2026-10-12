package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leftrr.agentscheduling.cli.CliId;

/**
 * 话题会话。对应 TS 版 src/core/session-manager.ts 的 Session 接口。
 * cliSessionId 为 null 表示 TS 的 undefined（序列化时省略）。
 * record 分量顺序刻意对齐 TS 对象字面量的 JSON 键序：cliSessionId 在 TS 中由后续更新
 * spread 追加、恒位于末尾（Review Focus 3）。
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

    /** 对齐 TS setWorkspaceDir 的解构剔除：换工作目录同时清空 CLI 会话选择。 */
    public Session withWorkspaceDir(String workspaceDir, String updatedAt) {
        return new Session(id, botId, threadId, chatId, cliId, workspaceDir,
                status, createdAt, updatedAt, null);
    }
}

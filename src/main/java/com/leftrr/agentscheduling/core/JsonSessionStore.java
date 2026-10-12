package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话 JSON 持久化。对应 TS 版 src/core/session-store.ts 的 JsonSessionStore。
 * 旧格式迁移（缺 botId/workspaceDir）、中断恢复（creating/active → idle）、非法行跳过，
 * 任一发生即回写文件（needsCleanup）。写串行化：TS Promise 队列 → ReentrantLock（设计裁决 7）。
 */
public final class JsonSessionStore implements SessionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private final String legacyBotId;
    private final Map<String, String> defaultWorkspaces;
    private final ReentrantLock writeLock = new ReentrantLock();

    public JsonSessionStore(String filePath) {
        this(filePath, "default", Map.of());
    }

    public JsonSessionStore(String filePath, String legacyBotId,
            Map<String, String> defaultWorkspaces) {
        this.filePath = Path.of(filePath);
        this.legacyBotId = legacyBotId;
        this.defaultWorkspaces = Map.copyOf(defaultWorkspaces);
    }

    @Override
    public List<Session> load() throws IOException {
        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of(); // 对齐 readdir/readFile ENOENT → []
        }
        JsonNode rows = MAPPER.readTree(content);
        if (rows == null || rows.isMissingNode()) {
            // 对齐 TS：空文件 JSON.parse 抛 SyntaxError 直通（与非法 JSON 同为解析失败路径），
            // 不走「会话文件格式错误」
            throw new IOException("会话状态文件解析失败: " + filePath);
        }
        if (!rows.isArray()) {
            throw new IOException("会话文件格式错误: " + filePath);
        }
        List<Session> sessions = new ArrayList<>();
        boolean needsCleanup = false;
        for (JsonNode row : rows) {
            Migration migration = migrateLegacySession(row);
            Session parsed = parseSession(migration.candidate());
            if (parsed == null) {
                needsCleanup = true; // 对齐 safeParse 失败 → 跳行
                continue;
            }
            if (migration.migrated()) {
                needsCleanup = true;
            }
            Session recovered = recoverInterruptedSession(parsed);
            if (recovered.status() != parsed.status()) {
                needsCleanup = true;
            }
            sessions.add(recovered);
        }
        if (needsCleanup) {
            save(sessions);
        }
        return sessions;
    }

    @Override
    public void save(List<Session> sessions) throws IOException {
        String snapshot = JsonFormat.toJson(sessions) + "\n";
        writeLock.lock();
        try {
            JsonFormat.writeAtomic(filePath, snapshot);
        } finally {
            writeLock.unlock();
        }
    }

    private record Migration(JsonNode candidate, boolean migrated) {}

    /** 对齐 migrateLegacySession()：缺 botId/workspaceDir 的旧行就地补齐。 */
    private Migration migrateLegacySession(JsonNode row) {
        if (row == null || !row.isObject()) {
            return new Migration(row, false);
        }
        boolean needsBotId = !row.has("botId");
        boolean needsWorkspace = !row.has("workspaceDir");
        if (!needsBotId && !needsWorkspace) {
            return new Migration(row, false);
        }
        ObjectNode candidate = ((ObjectNode) row).deepCopy(); // deepCopy() 深拷贝，只增字段不改动原行
        if (needsBotId) {
            candidate.put("botId", legacyBotId);
        }
        String botId = candidate.path("botId").isTextual()
                ? candidate.path("botId").asText()
                : legacyBotId;
        if (needsWorkspace) {
            String workspace = defaultWorkspaces.get(botId);
            candidate.put("workspaceDir",
                    workspace != null ? workspace : Path.of("").toAbsolutePath().toString());
        }
        return new Migration(candidate, true);
    }

    /** 对齐 recoverInterruptedSession()：中断在中途的会话恢复为 idle。 */
    static Session recoverInterruptedSession(Session session) {
        if (session.status() != SessionStatus.CREATING && session.status() != SessionStatus.ACTIVE) {
            return session;
        }
        return session.withStatus(SessionStatus.IDLE, session.updatedAt());
    }

    /** 对齐 SessionSchema.safeParse：非法返回 null（不抛）。逐字段校验规则见各 helper。 */
    static Session parseSession(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        String id = nonEmptyText(row, "id");
        String botId = nonEmptyText(row, "botId");
        String threadId = nonEmptyText(row, "threadId");
        String chatId = nonEmptyText(row, "chatId");
        String cliIdRaw = nonEmptyText(row, "cliId");
        CliId cliId = cliIdRaw == null ? null : CliId.fromId(cliIdRaw).orElse(null);
        String workspaceDir = nonEmptyText(row, "workspaceDir");
        String statusRaw = nonEmptyText(row, "status");
        SessionStatus status = statusRaw == null ? null : SessionStatus.fromId(statusRaw);
        String createdAt = zodDateTimeText(row, "createdAt");
        String updatedAt = zodDateTimeText(row, "updatedAt");
        // z.string().min(1).optional()：缺席 OK；存在（含显式 null）必须是非空字符串
        String cliSessionId = null;
        JsonNode cliSessionNode = row.get("cliSessionId");
        if (cliSessionNode != null) {
            if (!cliSessionNode.isTextual() || cliSessionNode.asText().isEmpty()) {
                return null;
            }
            cliSessionId = cliSessionNode.asText();
        }
        if (id == null || botId == null || threadId == null || chatId == null || cliId == null
                || workspaceDir == null || status == null || createdAt == null || updatedAt == null) {
            return null;
        }
        return new Session(id, botId, threadId, chatId, cliId, workspaceDir, status,
                createdAt, updatedAt, cliSessionId);
    }

    private static String nonEmptyText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && value.isTextual() && !value.asText().isEmpty()
                ? value.asText()
                : null;
    }

    private static String zodDateTimeText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && value.isTextual() && IsoTime.isZodDateTime(value.asText())
                ? value.asText()
                : null;
    }
}

package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 对齐 TS CliEvent 判别联合（sealed interface + record）。
 * ToolCallEvent.input 为原始 JSON（Jackson JsonNode），由上层（M6）解释。
 * 可空字段（sessionId/stats/detail）用 null 表达 TS 的 undefined。
 */
public sealed interface CliEvent permits
        CliEvent.SessionEvent, CliEvent.ToolStartEvent, CliEvent.ToolEndEvent,
        CliEvent.ContextEvent, CliEvent.StatsEvent, CliEvent.ToolCallEvent,
        CliEvent.ResultEvent, CliEvent.ErrorEvent {

    record SessionEvent(String sessionId) implements CliEvent {}

    record ToolStartEvent(String toolUseId, String toolName, String label, String detail)
            implements CliEvent {}

    record ToolEndEvent(String toolUseId, boolean failed) implements CliEvent {}

    record ContextEvent(long usedTokens) implements CliEvent {}

    record StatsEvent(CliRunStats stats) implements CliEvent {}

    record ToolCallEvent(String toolUseId, String toolName, JsonNode input) implements CliEvent {}

    record ResultEvent(String answer, String sessionId, CliRunStats stats) implements CliEvent {}

    record ErrorEvent(String message, String sessionId) implements CliEvent {}
}

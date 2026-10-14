package com.leftrr.agentscheduling.dto.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 一次 CLI 运行的结果；可选字段用 null 表示缺省。 */
public record CliRunResult(
        String answer,
        String sessionId,
        CliRunStats stats,
        List<ToolCall> toolCalls) {

    public record ToolCall(String toolUseId, String toolName, JsonNode input) {}
}

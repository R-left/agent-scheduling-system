package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 对齐 TS CliRunResult（可选字段 → null；toolCalls 默认 null 表达 TS 的 undefined）。 */
public record CliRunResult(
        String answer,
        String sessionId,
        CliRunStats stats,
        List<ToolCall> toolCalls) {

    public record ToolCall(String toolUseId, String toolName, JsonNode input) {}
}

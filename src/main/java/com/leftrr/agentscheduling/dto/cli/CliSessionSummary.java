package com.leftrr.agentscheduling.dto.cli;

/** CLI 原生历史会话摘要；updatedAt 为 ISO-8601 毫秒精度 UTC 字符串。 */
public record CliSessionSummary(String id, String title, String updatedAt) {}

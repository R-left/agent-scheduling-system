package com.leftrr.agentscheduling.cli;

/** 对齐 TS CliSessionSummary；updatedAt 为 ISO-8601 毫秒精度 UTC 字符串（见设计裁决 8）。 */
public record CliSessionSummary(String id, String title, String updatedAt) {}

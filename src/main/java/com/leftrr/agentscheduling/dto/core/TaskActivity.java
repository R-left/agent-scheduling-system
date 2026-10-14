package com.leftrr.agentscheduling.dto.core;

/** 进度快照中的一条活动记录；detail 为 null 表示无详情。 */
public record TaskActivity(String toolName, String label, String detail, long durationMs, boolean failed) {}

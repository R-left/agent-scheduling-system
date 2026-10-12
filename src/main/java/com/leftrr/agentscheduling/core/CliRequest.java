package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;

/**
 * /claude、/codex 直达请求。对应 TS 版 src/core/command-parser.ts 的 CliRequest。
 * prompt 为空串表示无附加指令（对齐 TS 的 {@code ?? ''}）。
 */
public record CliRequest(CliId cliId, String prompt) {
}

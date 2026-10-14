package com.leftrr.agentscheduling.dto.core;

import com.leftrr.agentscheduling.dto.cli.CliId;
/**
 * /claude、/codex 直达请求。
 * prompt 为空串表示无附加指令。
 */
public record CliRequest(CliId cliId, String prompt) {
}

package com.leftrr.agentscheduling.dto.cli;

import java.util.List;


/**
 * compact 执行计划的判别联合；protocol 判别字段由 record 类型承载。
 */
public sealed interface CliCompactPlan {

    /** claude-stream-json：prompt（/compact 指令）需写入子进程 stdin。 */
    record ClaudeStreamJson(String command, List<String> args, String prompt)
            implements CliCompactPlan {}

    /** codex-app-server：经 app-server JSON-RPC 执行 thread/compact/start。 */
    record CodexAppServer(String command, List<String> args, String sessionId)
            implements CliCompactPlan {}
}

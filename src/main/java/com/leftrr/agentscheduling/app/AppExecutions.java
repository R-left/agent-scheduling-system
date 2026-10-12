package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.CliRunner;
import com.leftrr.agentscheduling.core.CancellationToken;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * CLI 执行薄封装。对齐 TS 版 src/app/cli-execution.ts（21 行）：把适配器与运行参数
 * 原样转发给统一运行器，是应用层各收尾分支的统一执行入口。
 * TS→Java：Java CliRunner.Options 多出 timeoutMs 字段，此处固定传 null 走
 * CliTimeouts.fromEnv 兜底（与 TS runner 内部读 env 的行为一致）。
 */
public final class AppExecutions {

    private AppExecutions() {}

    /** TS executeCli（cli-execution.ts:4-20）；sessionId 为 null 表示新会话。 */
    public static CliRunResult executeCli(CliAdapter adapter, String prompt, String workspaceDir,
            String sessionId, CancellationToken cancellation, Consumer<CliEvent> onEvent)
            throws IOException, InterruptedException {
        return CliRunner.run(new CliRunner.Options(
                adapter, prompt, workspaceDir, sessionId, cancellation, null, onEvent));
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.dto.cli.CliEvent;
import com.leftrr.agentscheduling.dto.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.CliRunner;
import com.leftrr.agentscheduling.core.CancellationToken;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * CLI 执行薄封装：应用层各收尾分支的统一执行入口，参数原样转发 CliRunner。
 * timeoutMs 固定传 null，走默认超时（2 小时）。
 */
public final class AppExecutions {

    private AppExecutions() {}

    /** 统一执行入口；sessionId 为 null 表示新会话。 */
    public static CliRunResult executeCli(CliAdapter adapter, String prompt, String workspaceDir,
            String sessionId, CancellationToken cancellation, Consumer<CliEvent> onEvent)
            throws IOException, InterruptedException {
        return CliRunner.run(new CliRunner.Options(
                adapter, prompt, workspaceDir, sessionId, cancellation, null, onEvent));
    }
}

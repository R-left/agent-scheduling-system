package com.leftrr.agentscheduling.cli;

import com.leftrr.agentscheduling.core.CancellationToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 通用 CLI 运行器，对齐 TS runner.ts runCli()。
 *
 * 形态差异（语义等价，见计划设计裁决 1）：TS 基于 Promise 回调拼装；本实现阻塞调用线程
 * （架构约定由虚拟线程调用）直到子进程退出。stderr 收集与 stdin 写入各用一条虚拟线程，
 * 超时由守护调度线程触发 killCli。取消用 M1 CancellationToken（per-run 创建）；
 * 取消监听器只负责杀进程树，不读 ActiveRun.cancelMode（M2 前置事项）。
 */
public final class CliRunner {
    public static final long DEFAULT_TIMEOUT_MS = 2L * 60 * 60 * 1000;

    private CliRunner() {}

    public record Options(
            CliAdapter adapter,
            String prompt,
            String cwd,
            String sessionId,
            CancellationToken cancellation,
            Long timeoutMs,
            Consumer<CliEvent> onEvent) {}

    public static CliRunResult run(Options o) throws IOException, InterruptedException {
        long timeoutMs = o.timeoutMs() != null
                ? o.timeoutMs()
                : withDefault(CliTimeouts.fromEnv(o.adapter().id()));
        CliPromptInput promptInput = CliPromptInput.current();
        boolean useStdin = promptInput == CliPromptInput.STDIN;
        List<String> args = o.sessionId() != null
                ? o.adapter().buildResumeArgs(o.prompt(), o.sessionId(), promptInput)
                : o.adapter().buildArgs(o.prompt(), promptInput);

        if (o.cancellation() != null && o.cancellation().isCancelled()) {
            throw new CliExecutionException(o.adapter().displayName() + " 执行已取消");
        }

        Process child = CliProcess.spawn(o.adapter().command(), args, o.cwd());

        // stdin：写完即收口（TS child.stdin.end()）。虚拟线程写入，避免大 prompt 塞满管道死锁。
        Thread stdinWriter = Thread.ofVirtual().start(() -> {
            try (OutputStream stdin = child.getOutputStream()) {
                if (useStdin) {
                    stdin.write(o.prompt().getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 子进程提前退出时写 stdin 抛 IOException 是正常路径
            }
        });

        // TS 的 spawn signal 只杀直接子进程，额外 abort 监听用 killCli 连进程树清。
        CancellationToken.ListenerRegistration cancelReg = o.cancellation() == null
                ? null
                : o.cancellation().register(() -> CliProcess.kill(child));

        StringBuilder stderr = new StringBuilder();
        Thread stderrPump = pump(child.getErrorStream(), stderr);

        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "cli-runner-timeout");
                    t.setDaemon(true);
                    return t;
                });
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            timedOut.set(true);
            CliProcess.kill(child);
        }, timeoutMs, TimeUnit.MILLISECONDS);

        String observedSessionId = o.sessionId();
        String observedAnswer = null;
        CliRunStats observedStats = null;
        Map<String, CliRunResult.ToolCall> observedToolCalls = new LinkedHashMap<>();
        CliRunResult finalResult = null;
        String resultError = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (CliEvent event : o.adapter().parseEvents(line)) {
                    if (o.onEvent() != null) {
                        o.onEvent().accept(event);
                    }
                    if (event instanceof CliEvent.SessionEvent s) {
                        observedSessionId = s.sessionId();
                    } else if (event instanceof CliEvent.ErrorEvent e) {
                        resultError = e.message();
                        continue;
                    } else if (event instanceof CliEvent.ToolCallEvent c) {
                        observedToolCalls.put(c.toolUseId(),
                                new CliRunResult.ToolCall(c.toolUseId(), c.toolName(), c.input()));
                        continue;
                    } else if (event instanceof CliEvent.ToolEndEvent end && end.failed()) {
                        observedToolCalls.remove(end.toolUseId());
                        continue;
                    } else if (event instanceof CliEvent.StatsEvent st) {
                        observedStats = st.stats();
                        if (finalResult != null && finalResult.stats() == null) {
                            finalResult = withStats(finalResult, st.stats());
                        }
                        continue;
                    } else if (event instanceof CliEvent.ResultEvent r) {
                        if (r.answer() != null && !r.answer().isEmpty()) {
                            observedAnswer = r.answer();
                        }
                        if (r.stats() != null) {
                            observedStats = r.stats();
                        }
                        if (observedAnswer == null || observedAnswer.isEmpty()) {
                            continue;
                        }
                        finalResult = new CliRunResult(observedAnswer,
                                r.sessionId() != null ? r.sessionId() : observedSessionId,
                                observedStats, null);
                    }
                }
            }
        }
        int code = child.waitFor();
        stdinWriter.join();
        stderrPump.join();
        timer.cancel(false);
        scheduler.shutdownNow();
        if (cancelReg != null) {
            cancelReg.remove();
        }

        // 终态判定优先级对齐 TS：timeout → cancelled → error 事件 → 退出码 → 无结果
        if (timedOut.get()) {
            throw new CliExecutionException(o.adapter().displayName() + " 执行超时");
        }
        if (o.cancellation() != null && o.cancellation().isCancelled()) {
            throw new CliExecutionException(o.adapter().displayName() + " 执行已取消");
        }
        if (resultError != null) {
            throw new CliExecutionException(resultError);
        }
        if (code != 0) {
            String stderrText = JsText.jsTrim(stderr.toString());
            throw new CliExecutionException(!stderrText.isEmpty()
                    ? stderrText
                    : o.adapter().displayName() + " 退出，状态码 " + code);
        }
        if (finalResult == null) {
            throw new CliExecutionException(o.adapter().displayName() + " 没有返回最终结果");
        }
        if (observedStats != null && finalResult.stats() == null) {
            finalResult = withStats(finalResult, observedStats);
        }
        if (!observedToolCalls.isEmpty()) {
            finalResult = new CliRunResult(finalResult.answer(), finalResult.sessionId(),
                    finalResult.stats(), List.copyOf(observedToolCalls.values()));
        }
        return finalResult;
    }

    private static long withDefault(Long timeoutMs) {
        return timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    private static CliRunResult withStats(CliRunResult result, CliRunStats stats) {
        return new CliRunResult(result.answer(), result.sessionId(), stats, result.toolCalls());
    }

    private static Thread pump(InputStream input, StringBuilder target) {
        return Thread.ofVirtual().start(() -> {
            try (InputStream in = input) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (target) {  // 仅拼接，短暂持锁不构成 pin 风险；如评审质疑可换 ConcurrentHashMap 分片
                        target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时流关闭是正常路径
            }
        });
    }
}

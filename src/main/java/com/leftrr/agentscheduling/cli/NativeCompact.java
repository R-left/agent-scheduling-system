package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.core.CancellationToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调用 CLI 原生上下文压缩（/compact），对齐 TS native-compact.ts。
 * claude-stream-json：/compact 指令写 stdin，等 compact_boundary；
 * codex-app-server：JSON-RPC thread/resume + thread/compact/start，等 contextCompaction。
 */
public final class NativeCompact {
    public static final long DEFAULT_TIMEOUT_MS = 2L * 60 * 60 * 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NativeCompact() {}

    /**
     * compact 参数。cancellation 为每任务新建的 CancellationToken（per-run），
     * 不共享复用、不读 ActiveRun.cancelMode（同 CliRunner 措辞）。
     */
    public record Options(
            CliAdapter adapter,
            String sessionId,
            String cwd,
            String instructions,
            CancellationToken cancellation,
            Long timeoutMs) {}

    public record Result(String sessionId, boolean compacted, String message) {}

    public static Result compactCliSession(Options options) throws IOException, InterruptedException {
        CliCompactPlan plan = options.adapter().buildCompactPlan(
                options.sessionId(), options.instructions());
        if (plan instanceof CliCompactPlan.ClaudeStreamJson p) {
            return runClaudeCompact(p, options);
        }
        return runCodexCompact((CliCompactPlan.CodexAppServer) plan, options);
    }

    private static Result runClaudeCompact(CliCompactPlan.ClaudeStreamJson plan, Options options)
            throws IOException, InterruptedException {
        String displayName = options.adapter().displayName();
        if (options.cancellation() != null && options.cancellation().isCancelled()) {
            throw new CliExecutionException(displayName + " 上下文整理已取消");
        }
        Process child = CliProcess.spawn(plan.command(), plan.args(), options.cwd());
        CancellationToken.ListenerRegistration cancelReg = options.cancellation() == null
                ? null
                : options.cancellation().register(() -> CliProcess.kill(child));
        // /compact 指令写入子进程后收口，否则 Claude 会空等 stdin
        Thread stdinWriter = Thread.ofVirtual().start(() -> {
            try (OutputStream stdin = child.getOutputStream()) {
                stdin.write((plan.prompt() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 子进程提前退出属正常路径
            }
        });
        StringBuilder stderr = new StringBuilder();
        Thread stderrPump = NativeSessions.pump(child.getErrorStream(), stderr);

        long timeoutMs = options.timeoutMs() != null
                ? options.timeoutMs()
                : withDefault(CliTimeouts.fromEnv(options.adapter().id()));
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-compact-timeout");
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            timedOut.set(true);
            CliProcess.kill(child);
        }, timeoutMs, TimeUnit.MILLISECONDS);

        boolean completed = false;
        String resultMessage = null;
        String failReason = null;
        int code;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode parsed = parseOrNull(line);
                    if (parsed == null) {
                        continue;
                    }
                    // 数组行展开为多个消息（brief 用例 1 的 fixture 即数组行，对齐 TestAdapter 契约）
                    Iterable<JsonNode> messages = parsed.isArray() ? parsed : List.of(parsed);
                    for (JsonNode message : messages) {
                        if ("system".equals(text(message, "type"))
                                && "compact_boundary".equals(text(message, "subtype"))) {
                            completed = true;
                        }
                        // 对齐 TS `is_error === true`：字符串 "true" 等非布尔值不算失败
                        JsonNode isError = message.path("is_error");
                        if ("result".equals(text(message, "type"))
                                && isError.isBoolean() && isError.asBoolean()) {
                            JsonNode result = message.path("result");
                            failReason = result.isTextual()
                                    ? result.asText()
                                    : "Claude Code 原生上下文整理失败";
                            CliProcess.kill(child); // 对齐 TS fail：立即收兵
                            break;
                        }
                        if ("result".equals(text(message, "type"))
                                && message.path("result").isTextual()) {
                            String trimmed = JsText.jsTrim(message.path("result").asText());
                            resultMessage = trimmed.isEmpty() ? null : trimmed;
                        }
                    }
                    if (failReason != null) {
                        break;
                    }
                }
            }
            code = child.waitFor();
            stdinWriter.join();
            stderrPump.join();
        } catch (InterruptedException e) {
            // 中断路径：先杀进程树再恢复中断标记，防孤儿；中断语义向调用方透传（同 CliRunner）
            CliProcess.kill(child);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 所有路径统一收尾（同 CliRunner）：调度器关闭防守护线程泄漏、取消注册移除防泄漏、
            // 读循环抛 IOException 等异常路径下子进程仍存活时连树杀掉
            timer.cancel(false);
            scheduler.shutdownNow();
            if (cancelReg != null) {
                cancelReg.remove();
            }
            if (child.isAlive()) {
                CliProcess.kill(child);
            }
        }

        // 终态判定优先级对齐 TS fail() 先到先得：timeout → cancelled → is_error → 退出码 → 无 boundary
        // 窗口差异（Task 8 挂账保留）：TS 严格按事件到达序在回调内定终态，Java 在循环结束后按固定优先级判定，
        // timeout/cancel 与进程退出同瞬间竞态时终态归属可能与 TS 不同；窗口极窄且各终态文案一致，不值得为对齐
        // 引入到达序追踪，故不改。
        if (timedOut.get()) {
            throw new CliExecutionException(displayName + " 上下文整理超时");
        }
        if (options.cancellation() != null && options.cancellation().isCancelled()) {
            throw new CliExecutionException(displayName + " 上下文整理已取消");
        }
        if (failReason != null) {
            throw new CliExecutionException(failReason);
        }
        if (code != 0) {
            String stderrText = JsText.jsTrim(stderr.toString());
            throw new CliExecutionException(!stderrText.isEmpty()
                    ? stderrText
                    : "Claude Code 退出，状态码 " + code);
        }
        if (!completed) {
            if ("Not enough messages to compact.".equals(resultMessage)) {
                return new Result(options.sessionId(), false,
                        "当前上下文还不需要整理。继续使用一段时间后再试。");
            }
            throw new CliExecutionException("Claude Code 没有返回上下文整理完成事件");
        }
        return new Result(options.sessionId(), true, null);
    }

    /**
     * 同 {@link #runClaudeCompact} 的进程/超时/取消骨架，差别仅在 JSON-RPC 协议序列与完成条件；
     * JSON-RPC 收发/泵/调度器写法与 NativeSessions.listCodexSessions 同构（Task 7 审查修复同步）：
     * timer.cancel / scheduler.shutdownNow / 取消注册移除必须放 finally；InterruptedException
     * catch 内先 CliProcess.kill(child) 再恢复中断标记。
     */
    private static Result runCodexCompact(CliCompactPlan.CodexAppServer plan, Options options)
            throws IOException, InterruptedException {
        String displayName = options.adapter().displayName();
        if (options.cancellation() != null && options.cancellation().isCancelled()) {
            throw new CliExecutionException(displayName + " 上下文整理已取消");
        }
        Process child = CliProcess.spawn(plan.command(), plan.args(), options.cwd());
        CancellationToken.ListenerRegistration cancelReg = options.cancellation() == null
                ? null
                : options.cancellation().register(() -> CliProcess.kill(child));
        StringBuilder stderr = new StringBuilder();
        Thread stderrPump = NativeSessions.pump(child.getErrorStream(), stderr);

        long timeoutMs = options.timeoutMs() != null
                ? options.timeoutMs()
                : withDefault(CliTimeouts.fromEnv(options.adapter().id()));
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "codex-compact-timeout");
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            timedOut.set(true);
            CliProcess.kill(child);
        }, timeoutMs, TimeUnit.MILLISECONDS);

        boolean compacted = false;
        String failReason = null;
        int code;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                OutputStream stdin = child.getOutputStream();
                // R9（TS 语义，勿漏）：打开 stdin 后先发 initialize(id=1) 再等应答
                try {
                    writeInitializeRequest(stdin);
                } catch (IOException e) {
                    // 管道断裂：子进程已提前退出（对齐 stdin EPIPE），走退出码失败路径
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode message = parseOrNull(line);
                    if (message == null || !message.isObject()) {
                        continue;
                    }
                    JsonNode error = message.path("error");
                    if (error.isObject()) {
                        failReason = error.path("message").isTextual()
                                ? error.path("message").asText()
                                : "原生上下文整理失败";
                        CliProcess.kill(child); // 对齐 TS fail：立即收兵
                        break;
                    }
                    if (message.path("id").isInt() && message.path("id").asInt() == 1) {
                        // initialize 应答 → 发 initialized 通知 + thread/resume
                        try {
                            NativeSessions.writeJsonLine(stdin, Map.of(
                                    "method", "initialized",
                                    "params", Map.of()));
                            ObjectNode resume = MAPPER.createObjectNode();
                            resume.put("id", 2);
                            resume.put("method", "thread/resume");
                            resume.putObject("params").put("threadId", plan.sessionId());
                            NativeSessions.writeJsonLine(stdin, resume);
                        } catch (IOException e) {
                            // 管道断裂：子进程已提前退出，走退出码失败路径
                            break;
                        }
                        continue;
                    }
                    if (message.path("id").isInt() && message.path("id").asInt() == 2) {
                        // thread/resume 应答 → 发 thread/compact/start
                        try {
                            ObjectNode start = MAPPER.createObjectNode();
                            start.put("id", 3);
                            start.put("method", "thread/compact/start");
                            start.putObject("params").put("threadId", plan.sessionId());
                            NativeSessions.writeJsonLine(stdin, start);
                        } catch (IOException e) {
                            // 管道断裂：子进程已提前退出，走退出码失败路径
                            break;
                        }
                        continue;
                    }
                    if (!"item/completed".equals(text(message, "method"))) {
                        continue;
                    }
                    JsonNode item = message.path("params").path("item");
                    if (item.isObject() && "contextCompaction".equals(text(item, "type"))) {
                        compacted = true;
                        CliProcess.kill(child); // 对齐 TS succeed()：killCli 后 resolve
                        break;
                    }
                }
            }
            code = child.waitFor();
            stderrPump.join();
        } catch (InterruptedException e) {
            // 中断路径：先杀进程树再恢复中断标记，防孤儿；中断语义向调用方透传（同 CliRunner）
            CliProcess.kill(child);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 所有路径统一收尾（同 CliRunner / NativeSessions）
            timer.cancel(false);
            scheduler.shutdownNow();
            if (cancelReg != null) {
                cancelReg.remove();
            }
            if (child.isAlive()) {
                CliProcess.kill(child);
            }
            // 流关闭与 CliRunner 的差异：stdin 写入散布在 JSON-RPC 应答处理中，无 try-with-resources
            // 覆盖写线程路径，故此处 finally 显式关闭（CliRunner 由写线程自身 try-with-resources 收口）
            try {
                child.getOutputStream().close();
            } catch (IOException ignored) {
                // 进程被杀后关闭管道是正常路径
            }
        }

        // 终态判定优先级对齐 TS fail()/succeed() 先到先得：compacted → timeout → cancelled → error → 退出码
        // 窗口差异（Task 8 挂账保留，同 runClaudeCompact 注释）：TS 到达序定终态 vs Java 固定优先级，
        // 竞态同瞬间时终态归属可能不同；窗口极窄且文案一致，故不改。
        if (compacted) {
            return new Result(options.sessionId(), true, null);
        }
        if (timedOut.get()) {
            throw new CliExecutionException(displayName + " 上下文整理超时");
        }
        if (options.cancellation() != null && options.cancellation().isCancelled()) {
            throw new CliExecutionException(displayName + " 上下文整理已取消");
        }
        if (failReason != null) {
            throw new CliExecutionException(failReason);
        }
        String stderrText = JsText.jsTrim(stderr.toString());
        throw new CliExecutionException(!stderrText.isEmpty()
                ? stderrText
                : "Codex app-server 提前退出，状态码 " + code);
    }

    /** 对齐 TS 尾部 send({id:1, method:'initialize', params:{clientInfo:{...}}})。 */
    private static void writeInitializeRequest(OutputStream stdin) throws IOException {
        ObjectNode initialize = MAPPER.createObjectNode();
        initialize.put("id", 1);
        initialize.put("method", "initialize");
        ObjectNode clientInfo = initialize.putObject("params").putObject("clientInfo");
        clientInfo.put("name", "agent_os");
        clientInfo.put("title", "Agent OS");
        clientInfo.put("version", "0.1.0");
        NativeSessions.writeJsonLine(stdin, initialize);
    }

    private static long withDefault(Long timeoutMs) {
        return timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static JsonNode parseOrNull(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }
}

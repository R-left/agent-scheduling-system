package com.leftrr.agentscheduling.cli;
import com.leftrr.agentscheduling.util.StringUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.core.CancellationToken;
import com.leftrr.agentscheduling.dto.cli.CliCompactPlan;
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
 * 调用 CLI 原生上下文压缩（/compact）。
 * claude-stream-json：/compact 指令写 stdin，等 compact_boundary；
 * codex-app-server：JSON-RPC thread/resume + thread/compact/start，等 contextCompaction。
 */
public final class NativeCompact {
    public static final long DEFAULT_TIMEOUT_MS = 2L * 60 * 60 * 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NativeCompact() {}

    /** compact 参数。cancellation 为每任务新建，不复用、也不读 ActiveRun.cancelMode。 */
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

        long timeoutMs = options.timeoutMs() != null ? options.timeoutMs() : DEFAULT_TIMEOUT_MS;
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
                    // 数组行展开为多个消息逐条处理
                    Iterable<JsonNode> messages = parsed.isArray() ? parsed : List.of(parsed);
                    for (JsonNode message : messages) {
                        if ("system".equals(text(message, "type"))
                                && "compact_boundary".equals(text(message, "subtype"))) {
                            completed = true;
                        }
                        // 只认真布尔值，字符串 "true" 不算
                        JsonNode isError = message.path("is_error");
                        if ("result".equals(text(message, "type"))
                                && isError.isBoolean() && isError.asBoolean()) {
                            JsonNode result = message.path("result");
                            failReason = result.isTextual()
                                    ? result.asText()
                                    : "Claude Code 原生上下文整理失败";
                            CliProcess.kill(child); // 判定失败即杀树终止
                            break;
                        }
                        if ("result".equals(text(message, "type"))
                                && message.path("result").isTextual()) {
                            String trimmed = StringUtil.trim(message.path("result").asText());
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
            // 先杀树防孤儿，再恢复中断标记（同 CliRunner）
            CliProcess.kill(child);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 所有路径统一收尾（同 CliRunner）：守护调度线程关掉、取消注册移除、子进程还活着就连树杀
            timer.cancel(false);
            scheduler.shutdownNow();
            if (cancelReg != null) {
                cancelReg.remove();
            }
            if (child.isAlive()) {
                CliProcess.kill(child);
            }
        }

        // 终态判定优先级：timeout → cancelled → is_error → 退出码 → 无 boundary。
        // timeout/cancel 与进程退出同瞬间时终态归属受事件到达序影响，窗口极窄且
        // 各终态文案一致，不值得做到达序追踪。
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
            String stderrText = StringUtil.trim(stderr.toString());
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

    /** 进程/超时/取消骨架同 runClaudeCompact，只差 JSON-RPC 协议序列与完成条件。 */
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

        long timeoutMs = options.timeoutMs() != null ? options.timeoutMs() : DEFAULT_TIMEOUT_MS;
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
                // 协议序：打开 stdin 后先发 initialize(id=1) 再等应答
                try {
                    writeInitializeRequest(stdin);
                } catch (IOException e) {
                    // 管道断裂：子进程已提前退出（stdin EPIPE 同形态），走退出码失败路径
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
                        CliProcess.kill(child); // 判定失败即杀树终止
                        break;
                    }
                    if (message.path("id").isInt() && message.path("id").asInt() == 1) {
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
                        CliProcess.kill(child); // 完成即杀树终止
                        break;
                    }
                }
            }
            code = child.waitFor();
            stderrPump.join();
        } catch (InterruptedException e) {
            // 先杀树防孤儿，再恢复中断标记（同 CliRunner）
            CliProcess.kill(child);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 收尾同 runClaudeCompact / NativeSessions；stdin 写入散布在应答处理里，
            // 没有 try-with-resources，这里 finally 显式关闭
            timer.cancel(false);
            scheduler.shutdownNow();
            if (cancelReg != null) {
                cancelReg.remove();
            }
            if (child.isAlive()) {
                CliProcess.kill(child);
            }
            try {
                child.getOutputStream().close();
            } catch (IOException ignored) {
                // 进程被杀后关闭管道是正常路径
            }
        }

        // 终态判定优先级：compacted → timeout → cancelled → error → 退出码。
        // 竞态窗口同 runClaudeCompact 注释：归属可能随到达序不同，窗口极窄且文案一致。
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
        String stderrText = StringUtil.trim(stderr.toString());
        throw new CliExecutionException(!stderrText.isEmpty()
                ? stderrText
                : "Codex app-server 提前退出，状态码 " + code);
    }

    /** 发送 initialize(id=1) 请求（clientInfo 描述本进程）。 */
    private static void writeInitializeRequest(OutputStream stdin) throws IOException {
        ObjectNode initialize = MAPPER.createObjectNode();
        initialize.put("id", 1);
        initialize.put("method", "initialize");
        ObjectNode clientInfo = initialize.putObject("params").putObject("clientInfo");
        clientInfo.put("name", "agent_scheduling");
        clientInfo.put("title", "Agent OS");
        clientInfo.put("version", "0.1.0");
        NativeSessions.writeJsonLine(stdin, initialize);
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

package com.leftrr.agentscheduling.cli;
import com.leftrr.agentscheduling.util.StringUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.dto.cli.CliId;
import com.leftrr.agentscheduling.dto.cli.CliSessionSummary;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 列出 CLI 原生历史会话。
 * Claude 读 ~/.claude/projects/&lt;key&gt;/*.jsonl；Codex 走 app-server JSON-RPC。
 */
public final class NativeSessions {
    public static final int SESSION_LIMIT = 8;
    public static final long REQUEST_TIMEOUT_MS = 15_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 同 Claude/Codex 适配器：UNICODE_CHARACTER_CLASS 覆盖 Unicode 空白
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");
    // 恒定 3 位毫秒的 UTC 格式（Instant.toString() 会省略 .000）
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private NativeSessions() {}

    public record Options(CliAdapter adapter, String cwd) {}

    public static List<CliSessionSummary> listNativeCliSessions(Options options)
            throws IOException, InterruptedException {
        return options.adapter().id() == CliId.CLAUDE
                ? listClaudeSessions(options, defaultConfigDir())
                : listCodexSessions(options, REQUEST_TIMEOUT_MS);
    }

    static String defaultConfigDir() {
        String env = System.getenv("CLAUDE_CONFIG_DIR");
        return env != null && !env.isEmpty()
                ? env
                : Path.of(System.getProperty("user.home"), ".claude").toString();
    }

    /** cwd 中非 [A-Za-z0-9] 全部替换为 '-'。 */
    static String claudeProjectDirectory(String configDir, String cwd) {
        String key = NON_ALNUM.matcher(cwd).replaceAll("-");
        return Path.of(configDir, "projects", key).toString();
    }

    static List<CliSessionSummary> listClaudeSessions(Options options, String configDir)
            throws IOException {
        Path projectDir = Path.of(claudeProjectDirectory(configDir, options.cwd()));
        List<CliSessionSummary> sessions = new ArrayList<>();
        List<Path> files;
        try (var stream = Files.list(projectDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (NoSuchFileException e) {
            return List.of();
        }
        for (Path file : files) {
            CliSessionSummary summary = readClaudeSession(file, options.cwd());
            if (summary != null) {
                sessions.add(summary);
            }
        }
        // ISO 毫秒字符串的 compareTo 即时间先后序（纯 ASCII 格式）
        sessions.sort(Comparator.comparing(CliSessionSummary::updatedAt).reversed());
        return List.copyOf(sessions.subList(0, Math.min(SESSION_LIMIT, sessions.size())));
    }

    static CliSessionSummary readClaudeSession(Path filePath, String expectedCwd) throws IOException {
        String sessionId = null;
        String observedCwd = null;
        String firstPrompt = null;
        String lastPrompt = null;
        String title = null;
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode row = parseOrNull(line);
                if (row == null || !row.isObject()) {
                    continue;
                }
                if (row.path("sessionId").isTextual()) {
                    sessionId = row.path("sessionId").asText();
                }
                if (row.path("cwd").isTextual()) {
                    observedCwd = row.path("cwd").asText();
                }
                if ("ai-title".equals(text(row, "type"))) {
                    String t = shortText(row.path("aiTitle"));
                    if (t != null) {
                        title = t;
                    }
                }
                if ("last-prompt".equals(text(row, "type"))) {
                    String t = shortText(row.path("lastPrompt"));
                    if (t != null) {
                        lastPrompt = t;
                    }
                }
                if ("user".equals(text(row, "type")) && firstPrompt == null) {
                    firstPrompt = userPrompt(row.path("message"));
                }
            }
        }
        if (sessionId == null || !expectedCwd.equals(observedCwd)) {
            return null;
        }
        FileTime mtime = Files.getLastModifiedTime(filePath);
        return new CliSessionSummary(sessionId,
                title != null ? title
                        : lastPrompt != null ? lastPrompt
                        : firstPrompt != null ? firstPrompt
                        : "未命名会话",
                ISO_MILLIS.format(Instant.ofEpochMilli(mtime.toMillis())));
    }

    /** content 为字符串直取；数组取 type=text 的 text 以空格拼接。 */
    static String userPrompt(JsonNode message) {
        if (!message.isObject()) {
            return null;
        }
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return shortText(content);
        }
        if (!content.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block.isObject() && "text".equals(text(block, "type"))
                    && block.path("text").isTextual()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(block.path("text").asText());
            }
        }
        return shortText(MAPPER.valueToTree(sb.toString()));
    }

    /** 折叠空白并截 80 字符（超长加 …）。入参可为 null（返回 null）。 */
    static String shortText(JsonNode value) {
        if (!value.isTextual()) {
            return null;
        }
        String text = StringUtil.trim(WHITESPACE.matcher(value.asText()).replaceAll(" "));
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 80 ? text.substring(0, 79) + "…" : text;
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

    // ---- Codex 分支（app-server JSON-RPC，阻塞式）----

    /** 只认 JSON 整数：Jackson 的 asInt 会把字符串 "1" 强转成 1，宽松判定会让非法应答推进协议。 */
    static boolean hasId(JsonNode message, int expected) {
        JsonNode id = message.path("id");
        return id.isInt() && id.asInt() == expected;
    }

    static List<CliSessionSummary> listCodexSessions(Options options, long timeoutMs)
            throws IOException, InterruptedException {
        // codex app-server 模式固定走 app-server --stdio 启动参数
        List<String> args = List.of("app-server", "--stdio");
        Process child = CliProcess.spawn(options.adapter().command(), args, options.cwd());
        StringBuilder stderr = new StringBuilder();
        Thread stderrPump = pump(child.getErrorStream(), stderr);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "codex-sessions-timeout");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            timedOut.set(true);
            CliProcess.kill(child);
        }, timeoutMs, TimeUnit.MILLISECONDS);
        List<CliSessionSummary> result = null;
        String failReason = null;
        int code;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            OutputStream stdin = child.getOutputStream();
            try {
                ObjectNode initializeRequest = MAPPER.createObjectNode();
                initializeRequest.put("id", 1);
                initializeRequest.put("method", "initialize");
                ObjectNode initParams = initializeRequest.putObject("params");
                ObjectNode clientInfo = initParams.putObject("clientInfo");
                clientInfo.put("name", "agent_scheduling");
                clientInfo.put("title", "Agent OS");
                clientInfo.put("version", "0.1.0");
                writeJsonLine(stdin, initializeRequest);
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
                            : "Codex 会话列表读取失败";
                    CliProcess.kill(child);
                    break;
                }
                if (hasId(message, 1)) {
                    try {
                        writeJsonLine(stdin, Map.of("method", "initialized", "params", Map.of()));
                        ObjectNode listRequest = MAPPER.createObjectNode();
                        listRequest.put("id", 2);
                        listRequest.put("method", "thread/list");
                        ObjectNode params = listRequest.putObject("params");
                        params.put("cwd", options.cwd());
                        params.put("limit", SESSION_LIMIT);
                        params.put("sortKey", "updated_at");
                        params.put("sortDirection", "desc");
                        params.set("sourceKinds",
                                MAPPER.valueToTree(List.of("cli", "vscode", "exec", "appServer")));
                        writeJsonLine(stdin, listRequest);
                    } catch (IOException e) {
                        // 管道断裂：子进程已提前退出（stdin EPIPE 同形态），走退出码失败路径
                        break;
                    }
                    continue;
                }
                if (!hasId(message, 2) || !message.path("result").isObject()) {
                    continue;
                }
                JsonNode data = message.path("result").path("data");
                List<CliSessionSummary> summaries = new ArrayList<>();
                if (data.isArray()) {
                    for (JsonNode thread : data) {
                        if (!thread.isObject() || !thread.path("id").isTextual()) {
                            continue;
                        }
                        String updatedAt = thread.path("updatedAt").isNumber()
                                ? ISO_MILLIS.format(Instant.ofEpochMilli(thread.path("updatedAt").asLong() * 1000))
                                : ISO_MILLIS.format(Instant.now());
                        String title = shortText(thread.path("name"));
                        if (title == null) {
                            title = shortText(thread.path("preview"));
                        }
                        summaries.add(new CliSessionSummary(thread.path("id").asText(),
                                title != null ? title : "未命名会话",
                                updatedAt));
                    }
                }
                result = summaries;
                CliProcess.kill(child); // 读完即杀树终止
                break;
            }
            if (result == null && failReason == null) {
                child.waitFor(); // 未定局时等子进程退出（被 kill 或自行结束）
            }
            code = child.waitFor();
            stderrPump.join();
        } catch (InterruptedException e) {
            // 先杀树防孤儿，再恢复中断标记（同 CliRunner）
            CliProcess.kill(child);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // 所有路径统一收尾（同 CliRunner）：守护调度线程关掉、子进程还活着就连树杀。
            // 与 CliRunner 的差异：stdin 写入散布在应答处理里，没有 try-with-resources，
            // 所以这里 finally 里显式关 stdin/stdout。
            timer.cancel(false);
            scheduler.shutdownNow();
            if (child.isAlive()) {
                CliProcess.kill(child);
            }
            try {
                child.getOutputStream().close();
            } catch (IOException ignored) {
                // 进程被杀后关闭管道是正常路径
            }
            try {
                child.getInputStream().close();
            } catch (IOException ignored) {
                // 同上
            }
        }
        if (result != null) {
            return result;
        }
        if (timedOut.get()) {
            throw new CliExecutionException("Codex 会话列表读取超时");
        }
        if (failReason != null) {
            throw new CliExecutionException(failReason);
        }
        String stderrText = StringUtil.trim(stderr.toString());
        throw new CliExecutionException(!stderrText.isEmpty()
                ? stderrText
                : "Codex app-server 提前退出，状态码 " + code);
    }

    static void writeJsonLine(OutputStream stdin, Object node) throws IOException {
        stdin.write((MAPPER.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    static Thread pump(InputStream input, StringBuilder target) {
        return Thread.ofVirtual().start(() -> {
            try (InputStream in = input) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (target) {
                        target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时流关闭是正常路径
            }
        });
    }
}

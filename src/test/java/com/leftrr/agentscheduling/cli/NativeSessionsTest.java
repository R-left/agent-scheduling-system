package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.testsupport.FakeCliCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativeSessions 行为清单（brief Task 7 共 15 组用例）。
 * Claude 分支走包级重载注入 configDir（对齐 Task 2 CliTimeouts 手法，env 不可注入故入口保持薄分发）；
 * Codex 分支用 FakeCli app-server 脚本 + 包级重载注入超时与启动参数。
 * 文案断言逐字对齐 TS native-sessions.ts。
 */
class NativeSessionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // cwd 用正斜杠形态：JSONL 文本里无需转义反斜杠，claudeProjectDirectory 的 key 同样是 D--work
    private static final String CWD = "D:/work";
    private static final String PROJECT_KEY = "D--work";
    // 对齐 Node toISOString：恒 3 位毫秒（设计裁决 8）
    private static final Pattern ISO_MILLIS =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    @TempDir
    Path tmpDir;

    // ---------- 测试替身 ----------

    /** 最小适配器：Claude 分支只用 cwd；Codex 分支 command() 为当前 JVM 的 java 可执行。 */
    private static final class StubAdapter implements CliAdapter {
        private final CliId id;

        StubAdapter(CliId id) {
            this.id = id;
        }

        @Override
        public CliId id() {
            return id;
        }

        @Override
        public String command() {
            return FakeCliCommand.command().get(0);
        }

        @Override
        public String displayName() {
            return "测试CLI";
        }

        @Override
        public List<String> buildArgs(String prompt, CliPromptInput promptInput) {
            return List.of();
        }

        @Override
        public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
            return List.of();
        }

        @Override
        public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
            return null;
        }

        @Override
        public List<CliEvent> parseEvents(String line) {
            return List.of();
        }
    }

    private NativeSessions.Options claudeOptions() {
        return new NativeSessions.Options(new StubAdapter(CliId.CLAUDE), CWD);
    }

    private NativeSessions.Options codexOptions() {
        return new NativeSessions.Options(new StubAdapter(CliId.CODEX), tmpDir.toString());
    }

    /** FakeCli 完整启动参数（去掉首元素可执行本身），NativeSessions 注入用。 */
    private List<String> fakeCliArgs(String scriptPath) {
        List<String> full = FakeCliCommand.command("app-server:" + scriptPath);
        return full.subList(1, full.size());
    }

    private Path writeScript(JsonNode script) throws IOException {
        Path file = tmpDir.resolve("script-" + System.nanoTime() + ".json");
        Files.writeString(file, MAPPER.writeValueAsString(script), StandardCharsets.UTF_8);
        return file;
    }

    private Path writeSessionFile(String name, String... lines) throws IOException {
        Path projectDir = tmpDir.resolve("cfg").resolve("projects").resolve(PROJECT_KEY);
        Files.createDirectories(projectDir);
        Path file = projectDir.resolve(name);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static void setMtime(Path file, long epochMillis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(epochMillis));
    }

    // ---------- Claude 分支 ----------

    @Test
    void projectDirectoryReplacesNonAlnumWithDashes() {
        // cwd "D:\work 中 文/a"：':' '\' ' '(空格) '中' ' '(空格) '文' '/' 各换一个 '-'
        assertEquals("C:\\cfg\\projects\\D--work-----a",
                NativeSessions.claudeProjectDirectory("C:\\cfg", "D:\\work 中 文/a"));
    }

    @Test
    void readsBasicSessionWithTitle() throws IOException {
        Path file = writeSessionFile("abc.jsonl",
                "{\"sessionId\":\"s1\",\"cwd\":\"" + CWD + "\",\"type\":\"user\",\"message\":{\"content\":\"第一问\"}}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"标题\"}");
        setMtime(file, 1700000000123L);

        List<CliSessionSummary> sessions =
                NativeSessions.listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString());

        assertEquals(1, sessions.size());
        assertEquals(new CliSessionSummary("s1", "标题", "2023-11-14T22:13:20.123Z"), sessions.get(0));
    }

    @Test
    void titleFallbackChain() throws IOException {
        // ai-title 缺失 → lastPrompt → 首条 user message.content（数组形态拼接）→ 全无 → 未命名会话
        Path f1 = writeSessionFile("f1.jsonl",
                "{\"sessionId\":\"f1\",\"cwd\":\"" + CWD + "\",\"type\":\"last-prompt\",\"lastPrompt\":\"最新问\"}");
        setMtime(f1, 1000);
        Path f2 = writeSessionFile("f2.jsonl",
                "{\"sessionId\":\"f2\",\"cwd\":\"" + CWD + "\",\"type\":\"user\",\"message\":{\"content\":\"首问\"}}");
        setMtime(f2, 2000);
        Path f3 = writeSessionFile("f3.jsonl",
                "{\"sessionId\":\"f3\",\"cwd\":\"" + CWD + "\",\"type\":\"user\",\"message\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}}");
        setMtime(f3, 3000);
        Path f4 = writeSessionFile("f4.jsonl", "{\"sessionId\":\"f4\",\"cwd\":\"" + CWD + "\"}");
        setMtime(f4, 4000);
        // lastPrompt 优先于首条 user；userPrompt 为 null 时后续 user 行仍可补位
        Path f5 = writeSessionFile("f5.jsonl",
                "{\"sessionId\":\"f5\",\"cwd\":\"" + CWD + "\",\"type\":\"last-prompt\",\"lastPrompt\":\"  \\n  \"}",
                "{\"type\":\"user\",\"message\":{\"content\":\"后续问\"}}");
        setMtime(f5, 5000);

        List<CliSessionSummary> sessions =
                NativeSessions.listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString());
        assertEquals(5, sessions.size());
        assertEquals(new CliSessionSummary("f5", "后续问", "1970-01-01T00:00:05.000Z"), byId(sessions, "f5"));
        assertEquals(new CliSessionSummary("f4", "未命名会话", "1970-01-01T00:00:04.000Z"), byId(sessions, "f4"));
        assertEquals(new CliSessionSummary("f3", "a b", "1970-01-01T00:00:03.000Z"), byId(sessions, "f3"));
        assertEquals(new CliSessionSummary("f2", "首问", "1970-01-01T00:00:02.000Z"), byId(sessions, "f2"));
        assertEquals(new CliSessionSummary("f1", "最新问", "1970-01-01T00:00:01.000Z"), byId(sessions, "f1"));
    }

    @Test
    void shortTextCollapsesWhitespaceAndTruncates() throws IOException {
        Path f1 = writeSessionFile("t1.jsonl",
                "{\"sessionId\":\"t1\",\"cwd\":\"" + CWD + "\",\"type\":\"last-prompt\","
                        + "\"lastPrompt\":\"  a \\n b\\t\\tc  \"}");
        setMtime(f1, 1000);
        String longText = "x".repeat(100);
        Path f2 = writeSessionFile("t2.jsonl",
                "{\"sessionId\":\"t2\",\"cwd\":\"" + CWD + "\",\"type\":\"last-prompt\","
                        + "\"lastPrompt\":\"" + longText + "\"}");
        setMtime(f2, 2000);

        List<CliSessionSummary> sessions =
                NativeSessions.listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString());
        assertEquals("a b c", byId(sessions, "t1").title());
        assertEquals("x".repeat(79) + "…", byId(sessions, "t2").title());
    }

    @Test
    void filtersSessionsWithMismatchedCwdOrMissingSessionId() throws IOException {
        writeSessionFile("m1.jsonl", "{\"sessionId\":\"m1\",\"cwd\":\"D:\\\\other\"}");
        writeSessionFile("m2.jsonl", "{\"cwd\":\"" + CWD + "\",\"type\":\"user\",\"message\":{\"content\":\"q\"}}");

        List<CliSessionSummary> sessions =
                NativeSessions.listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString());
        assertTrue(sessions.isEmpty());
    }

    @Test
    void ignoresNonJsonlFilesAndMissingProjectDirectory() throws IOException {
        // 非 .jsonl 文件即使内容有效也被忽略
        Path projectDir = tmpDir.resolve("cfg").resolve("projects").resolve(PROJECT_KEY);
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("notes.txt"),
                "{\"sessionId\":\"n1\",\"cwd\":\"" + CWD + "\"}", StandardCharsets.UTF_8);

        assertTrue(NativeSessions
                .listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString())
                .isEmpty());

        // projects/<key> 目录不存在 → 空列表（对齐 readdir ENOENT，不抛）
        assertTrue(NativeSessions
                .listClaudeSessions(claudeOptions(), tmpDir.resolve("no-such-cfg").toString())
                .isEmpty());
    }

    @Test
    void sortsByUpdatedAtDescendingAndCapsAtEight() throws IOException {
        for (int i = 0; i < 10; i++) {
            Path file = writeSessionFile("s" + i + ".jsonl",
                    "{\"sessionId\":\"s" + i + "\",\"cwd\":\"" + CWD + "\"}");
            setMtime(file, 1_700_000_000_000L + i * 1000L);
        }

        List<CliSessionSummary> sessions =
                NativeSessions.listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString());
        assertEquals(8, sessions.size());
        assertEquals("s9", sessions.get(0).id());
        assertEquals("s2", sessions.get(7).id());
    }

    @Test
    void updatedAtIsIsoWithConstantMillis() throws IOException {
        Path file = writeSessionFile("iso.jsonl", "{\"sessionId\":\"iso\",\"cwd\":\"" + CWD + "\"}");
        setMtime(file, 1_700_000_000_000L);

        List<CliSessionSummary> sessions =
                NativeSessions.listClaudeSessions(claudeOptions(), tmpDir.resolve("cfg").toString());
        assertEquals(1, sessions.size());
        assertTrue(ISO_MILLIS.matcher(sessions.get(0).updatedAt()).matches(),
                "updatedAt 应为 3 位毫秒恒在的 ISO 串: " + sessions.get(0).updatedAt());
    }

    // ---------- Codex 分支（FakeCli app-server 脚本）----------

    @Test
    void codexListsThreadsFromAppServer() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        ObjectNode list = script.putObject("list");
        var dataArray = list.putArray("data");
        ObjectNode th1 = dataArray.addObject();
        th1.put("id", "th1");
        th1.put("name", "会话甲");
        th1.put("updatedAt", 1700000000);
        dataArray.addObject().put("id", "th2");
        // name 缺失回退 preview（brief 用例 9 的 preview 分支）
        ObjectNode th3 = dataArray.addObject();
        th3.put("id", "th3");
        th3.put("preview", "预览文本");

        List<CliSessionSummary> sessions = NativeSessions.listCodexSessions(
                codexOptions(), 10_000, fakeCliArgs(writeScript(script).toString()));

        assertEquals(3, sessions.size());
        assertEquals(new CliSessionSummary("th1", "会话甲", "2023-11-14T22:13:20.000Z"), sessions.get(0));
        assertEquals("th2", sessions.get(1).id());
        assertEquals("未命名会话", sessions.get(1).title());
        assertTrue(ISO_MILLIS.matcher(sessions.get(1).updatedAt()).matches(),
                "updatedAt 缺失时回退当前时间的 ISO 串");
        assertEquals(new CliSessionSummary("th3", "预览文本", sessions.get(2).updatedAt()), sessions.get(2));
        assertTrue(ISO_MILLIS.matcher(sessions.get(2).updatedAt()).matches());
    }

    @Test
    void codexProtocolSequenceAndListParams() throws Exception {
        Path recordFile = tmpDir.resolve("record.jsonl");
        ObjectNode script = MAPPER.createObjectNode();
        script.put("record", recordFile.toString());
        ObjectNode list = script.putObject("list");
        list.putArray("data").addObject().put("id", "th1");

        NativeSessions.listCodexSessions(codexOptions(), 10_000, fakeCliArgs(writeScript(script).toString()));

        List<JsonNode> received = readRecord(recordFile);
        assertEquals(3, received.size(), "应恰好收到 initialize、initialized、thread/list 三行");
        assertEquals("initialize", received.get(0).path("method").asText());
        assertEquals("initialized", received.get(1).path("method").asText());
        assertTrue(!received.get(1).has("id"), "initialized 是通知，不带 id");

        JsonNode listRequest = received.get(2);
        assertEquals(2, listRequest.path("id").asInt());
        assertEquals("thread/list", listRequest.path("method").asText());
        JsonNode params = listRequest.path("params");
        assertEquals(tmpDir.toString(), params.path("cwd").asText());
        assertEquals(8, params.path("limit").asInt());
        assertEquals("updated_at", params.path("sortKey").asText());
        assertEquals("desc", params.path("sortDirection").asText());
        assertEquals(MAPPER.createArrayNode()
                        .add("cli").add("vscode").add("exec").add("appServer"),
                params.path("sourceKinds"));
    }

    @Test
    void codexInitializeCarriesClientInfo() throws Exception {
        Path recordFile = tmpDir.resolve("record-client-info.jsonl");
        ObjectNode script = MAPPER.createObjectNode();
        script.put("record", recordFile.toString());
        ObjectNode list = script.putObject("list");
        list.putArray("data").addObject().put("id", "th1");

        NativeSessions.listCodexSessions(codexOptions(), 10_000, fakeCliArgs(writeScript(script).toString()));

        JsonNode initialize = readRecord(recordFile).get(0);
        JsonNode clientInfo = initialize.path("params").path("clientInfo");
        assertEquals("agent_os", clientInfo.path("name").asText());
        assertEquals("Agent OS", clientInfo.path("title").asText());
        assertEquals("0.1.0", clientInfo.path("version").asText());
    }

    @Test
    void codexListErrorSurfacesProtocolMessage() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        ObjectNode err = script.putObject("listError");
        err.put("message", "boom");

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                NativeSessions.listCodexSessions(codexOptions(), 10_000,
                        fakeCliArgs(writeScript(script).toString())));
        assertEquals("boom", e.getMessage());
    }

    @Test
    void codexErrorWithoutMessageFallsBackToDefaultText() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        script.putObject("listError");

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                NativeSessions.listCodexSessions(codexOptions(), 10_000,
                        fakeCliArgs(writeScript(script).toString())));
        assertEquals("Codex 会话列表读取失败", e.getMessage());
    }

    @Test
    void codexTimeoutWhenAppServerStalls() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        script.put("stall", true);

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                NativeSessions.listCodexSessions(codexOptions(), 400,
                        fakeCliArgs(writeScript(script).toString())));
        assertEquals("Codex 会话列表读取超时", e.getMessage());
    }

    @Test
    void codexStringIdRepliesAreRejectedUntilTimeout() throws Exception {
        // 应答 id 全为字符串 "1"/"2"：宽松 asInt(0) 会把 "1" 强转匹配、协议推进并成功返回列表
        // （修复前 RED）；严格 isInt 判定不匹配，一直等不到 id=2 应答直至超时。
        // stall 保证 FakeCli 驻留不退出，超时定时器有机会触发而非走「提前退出」路径
        ObjectNode script = MAPPER.createObjectNode();
        script.put("stall", true);
        script.put("stringIds", true);
        ObjectNode list = script.putObject("list");
        ObjectNode th1 = list.putArray("data").addObject();
        th1.put("id", "th1");
        th1.put("name", "会话甲");

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                NativeSessions.listCodexSessions(codexOptions(), 400,
                        fakeCliArgs(writeScript(script).toString())));
        assertEquals("Codex 会话列表读取超时", e.getMessage());
    }

    @Test
    void codexEarlyExitReportsExitCode() throws Exception {
        ObjectNode script = MAPPER.createObjectNode();
        script.put("exitAfterInit", 2);

        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                NativeSessions.listCodexSessions(codexOptions(), 10_000,
                        fakeCliArgs(writeScript(script).toString())));
        assertEquals("Codex app-server 提前退出，状态码 2", e.getMessage());
    }

    // ---------- 入口分发 ----------

    @Test
    void entryDispatchesClaudeBranch() throws Exception {
        // env CLAUDE_CONFIG_DIR 不可注入；用必然不存在的项目目录 cwd 断言走 claude 分支（返回空列表不抛）
        String unseenCwd = tmpDir.resolve("never-opened").toString();
        assertTrue(NativeSessions.listNativeCliSessions(
                new NativeSessions.Options(new StubAdapter(CliId.CLAUDE), unseenCwd)).isEmpty());
    }

    @Test
    void entryDispatchesCodexBranch() {
        // 裸 FakeCli（无脚本模式）遇到硬编码 app-server 参数：java 把 "app-server" 当主类加载，
        // 必然 ClassNotFoundException 并写 stderr、以非 0 退出。断言 stderr 特征（ASCII、
        // 兜底文案里不存在），使断言真正落在子进程输出而非 stderr 为空时的兜底文案上
        CliExecutionException e = assertThrows(CliExecutionException.class, () ->
                NativeSessions.listNativeCliSessions(
                        new NativeSessions.Options(new StubAdapter(CliId.CODEX), tmpDir.toString())));
        assertTrue(e.getMessage().contains("ClassNotFoundException"),
                "codex 分支异常信息应来自子进程 stderr 的 ClassNotFoundException，实际: " + e.getMessage());
    }

    // ---------- 工具 ----------

    private static List<JsonNode> readRecord(Path recordFile) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        for (String line : Files.readAllLines(recordFile, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                messages.add(MAPPER.readTree(line));
            }
        }
        return messages;
    }

    private static CliSessionSummary byId(List<CliSessionSummary> sessions, String id) {
        return sessions.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
}

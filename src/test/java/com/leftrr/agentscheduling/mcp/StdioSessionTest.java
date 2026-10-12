package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leftrr.agentscheduling.mcp.McpTestFixtures.clarifyArgs;
import static com.leftrr.agentscheduling.mcp.McpTestFixtures.option;
import static com.leftrr.agentscheduling.mcp.McpTestFixtures.question;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M5 Task 2：stdio JSON-RPC 会话端到端（内存流，不起真实子进程）。
 *
 * <p>流形态（实测定型）：SDK 的 StdioServerTransportProvider 在 server build 时即启动
 * 「stdio-inbound / stdio-outbound」两个非 daemon 单线程执行器做行级读写（Task 0 的
 * ByteArrayInputStream 立即 EOF，会话读完即关），端到端多请求会话需要保持输入流打开——
 * 因此用管道对：测试线程往 PipedOutputStream 写请求（保持流不关），server 的 inbound
 * 线程从 PipedInputStream 读；响应写到 {@link CapturingOutputStream}，测试线程按
 * JSON-RPC id 轮询认领。inbound 用 Flux.flatMap 分发（并发），响应到达顺序不保证，
 * 断言一律按 id 关联，不按到达序。</p>
 *
 * <p>会话前提：SDK 的 McpServerSession 有 UNINITIALIZED → INITIALIZING → INITIALIZED
 * 状态机，tools/list、tools/call 须先 initialize + notifications/initialized——夹具
 * {@link StdioSession#initialize(long)} 封装这一步。</p>
 *
 * <p>结构校验层实测形态：McpServer$SyncSpecification 的 validateToolInputs 缺省 true
 * （字节码实测 iconst_1），tools/call 分发时经 ToolInputValidator.validate 先做 JSON
 * Schema 结构校验，失败返回 CallToolResult(isError=true, TextContent(错误文案))——即
 * JSON-RPC result（非 error 对象），本文件钉住该形态（Task 1 直连测试绕过了这一层，
 * 这里是它的唯一覆盖点）。</p>
 */
class StdioSessionTest {

    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    /** 单条响应的最长等待。CI 慢机上真实到达通常毫秒级，10s 足够宽。 */
    private static final long AWAIT_TIMEOUT_MS = 10_000;

    private static final String CLARIFY_RECEIPT_1 = "已把 1 个问题交给 Agent OS，请等待用户回答。";

    // ------------------------------------------------------------------
    // 用例 1：initialize 握手
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void initializeReturnsProtocolVersionServerInfoAndToolCapability() throws Exception {
        try (StdioSession session = new StdioSession()) {
            JsonNode response = session.initialize(1);
            assertEquals("2.0", response.path("jsonrpc").asText());
            JsonNode result = response.path("result");
            assertTrue(result.has("protocolVersion"), "响应须含 protocolVersion，实测: " + result);
            assertEquals("2025-06-18", result.path("protocolVersion").asText(),
                    "SDK 2.0.1 支持该版本，按请求回显");
            assertEquals("agent-os", result.path("serverInfo").path("name").asText(),
                    "TS McpServer 构造字面（设计裁决 3）");
            assertEquals("1.0.0", result.path("serverInfo").path("version").asText());
            assertTrue(result.path("capabilities").has("tools"),
                    "tools 能力随工具注册自动声明（Review Focus 5），实测: " + result.path("capabilities"));
        }
    }

    // ------------------------------------------------------------------
    // 用例 2：tools/list
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void toolsListReturnsExactlyThreeRegisteredTools() throws Exception {
        try (StdioSession session = new StdioSession()) {
            session.initialize(1);
            session.send(request(2, "tools/list"));
            JsonNode result = session.awaitResponse(2).path("result");
            JsonNode tools = result.path("tools");
            assertEquals(3, tools.size(), "恰注册 TS 的三个工具");
            List<String> names = new ArrayList<>();
            tools.forEach(tool -> names.add(tool.path("name").asText()));
            assertEquals(Set.of("request_clarification", "request_spec_approval", "dispatch_task"),
                    Set.copyOf(names));
        }
    }

    // ------------------------------------------------------------------
    // 用例 3：tools/call 合法 → 回执文案
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void toolsCallClarifyValidReturnsReceiptText() throws Exception {
        try (StdioSession session = new StdioSession()) {
            session.initialize(1);
            session.send(toolsCall(2, "request_clarification", clarifyArgs(question("q1"))));
            JsonNode result = session.awaitResponse(2).path("result");
            assertFalse(result.path("isError").asBoolean(false),
                    "成功回执 isError 非 true（缺省/显式 false 均接受），实测: " + result);
            JsonNode content = result.path("content");
            assertEquals(1, content.size(), "回执恰一条 content");
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals(CLARIFY_RECEIPT_1, content.get(0).path("text").asText());
        }
    }

    // ------------------------------------------------------------------
    // 用例 4：tools/call 语义非法（结构合法但 core parseRequest 拒绝）→ isError:true
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void toolsCallSemanticallyInvalidReturnsIsErrorTrue() throws Exception {
        try (StdioSession session = new StdioSession()) {
            session.initialize(1);
            // options 内两 opt_a 重复 id：JSON Schema 层合法，core 复校验拒绝（设计裁决 1）
            Map<String, Object> question = question("q1");
            question.put("options", List.of(
                    option("opt_a", "方案 A"), option("opt_a", "方案 A 副本")));
            session.send(toolsCall(2, "request_clarification", clarifyArgs(question)));
            JsonNode result = session.awaitResponse(2).path("result");
            assertTrue(result.path("isError").isBoolean() && result.path("isError").asBoolean(),
                    "语义非法必须 isError=true，实测: " + result);
            String text = result.path("content").get(0).path("text").asText();
            assertFalse(text.equals(CLARIFY_RECEIPT_1), "失败不得回执成功文案");
            assertFalse(text.isEmpty(), "isError 回执须带说明文案，实测: " + result);
        }
    }

    // ------------------------------------------------------------------
    // 用例 5：结构非法入参 → SDK validateToolInputs 层拒绝
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void toolsCallStructurallyInvalidRejectedBySdkValidation() throws Exception {
        try (StdioSession session = new StdioSession()) {
            session.initialize(1);
            // questions 空数组：违反 inputSchema 的 minItems:1（required 在位但值非法），
            // 由 SDK 的 ToolInputValidator 在分发 handler 之前拒绝
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("questions", List.of());
            session.send(toolsCall(2, "request_clarification", args));
            JsonNode result = session.awaitResponse(2).path("result");
            assertTrue(result.path("isError").isBoolean() && result.path("isError").asBoolean(),
                    "SDK 结构校验拒绝形态为 CallToolResult(isError=true)，实测: " + result);
            String text = result.path("content").get(0).path("text").asText();
            assertFalse(text.equals(CLARIFY_RECEIPT_1), "结构拒绝不得回执成功文案");
            assertFalse(text.equals(AppToolsServer.CLARIFICATION_REJECTION), "拒绝发生在 SDK 校验层，先于 handler 的 core 复校验");
        }
    }

    // ------------------------------------------------------------------
    // 用例 6：全会话在同一对内存流上顺序完成
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void fullSessionCompletesSequentiallyOnSameStreamPair() throws Exception {
        try (StdioSession session = new StdioSession()) {
            // initialize 先行（会话前提约束生效），后续请求逐一发送、逐一认领响应
            JsonNode initResult = session.initialize(1).path("result");
            assertEquals("agent-os", initResult.path("serverInfo").path("name").asText());

            session.send(request(2, "tools/list"));
            JsonNode listed = session.awaitResponse(2).path("result").path("tools");
            assertEquals(3, listed.size(), "同一会话 tools/list 正常");

            Map<String, Object> validQuestion = question("q1");
            session.send(toolsCall(3, "request_clarification", clarifyArgs(validQuestion)));
            JsonNode valid = session.awaitResponse(3).path("result");
            assertFalse(valid.path("isError").asBoolean(false));
            assertEquals(CLARIFY_RECEIPT_1, valid.path("content").get(0).path("text").asText());

            Map<String, Object> badQuestion = question("q2");
            badQuestion.put("options", List.of(
                    option("opt_a", "方案 A"), option("opt_a", "方案 A 副本")));
            session.send(toolsCall(4, "request_clarification", clarifyArgs(badQuestion)));
            assertTrue(session.awaitResponse(4).path("result").path("isError").asBoolean(),
                    "同一会话内语义非法仍被拒");

            Map<String, Object> structural = new LinkedHashMap<>();
            structural.put("questions", List.of());
            session.send(toolsCall(5, "request_clarification", structural));
            assertTrue(session.awaitResponse(5).path("result").path("isError").asBoolean(),
                    "同一会话内结构非法仍被 SDK 校验拒绝");
        }
    }

    // ------------------------------------------------------------------
    // 夹具：管道对 + 捕获输出流 + 按 id 认领响应
    // ------------------------------------------------------------------

    /** 单条会话：管道对承载请求，CapturingOutputStream 收响应；close 收敛两端的线程与流。 */
    private static final class StdioSession implements AutoCloseable {

        private final PipedOutputStream clientToServer = new PipedOutputStream();
        private final PipedInputStream serverIn;
        private final CapturingOutputStream serverOut = new CapturingOutputStream();
        private final McpSyncServer server;

        StdioSession() throws IOException {
            serverIn = new PipedInputStream(clientToServer, 64 * 1024);
            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    McpJsonDefaults.getMapper(), serverIn, serverOut);
            // connect 内部 McpServer.sync(...).build() 调 transport.setSessionFactory，
            // 随即启动 inbound/outbound 处理线程——构建即开始监听输入流
            server = AppToolsServer.build().connect(transport);
        }

        void send(JsonNode message) throws IOException {
            byte[] bytes = (JACKSON2.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (clientToServer) {
                clientToServer.write(bytes);
                clientToServer.flush();
            }
        }

        /** initialize 请求 + 响应认领 + initialized 通知（会话进入 INITIALIZED 的完整握手）。 */
        JsonNode initialize(long id) throws IOException, InterruptedException {
            ObjectNode init = request(id, "initialize");
            ObjectNode params = init.putObject("params");
            params.put("protocolVersion", "2025-06-18");
            params.putObject("capabilities");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "stdio-session-test");
            clientInfo.put("version", "0.0.0");
            send(init);
            JsonNode response = awaitResponse(id);
            send(notification("notifications/initialized"));
            return response;
        }

        /** 按 JSON-RPC id 轮询认领响应（inbound flatMap 并发分发，不保证到达序）。 */
        JsonNode awaitResponse(long id) throws InterruptedException {
            long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (String line : serverOut.snapshot()) {
                    JsonNode node = parse(line);
                    if (node == null) {
                        continue;
                    }
                    boolean idMatches = node.has("id") && node.path("id").asLong(Long.MIN_VALUE) == id;
                    boolean isResponse = node.has("result") || node.has("error");
                    if (idMatches && isResponse) {
                        return node;
                    }
                }
                Thread.sleep(20);
            }
            fail("等待 id=" + id + " 的响应超时；已收到的报文：" + serverOut.snapshot());
            return null;
        }

        private static JsonNode parse(String line) {
            try {
                return JACKSON2.readTree(line);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void close() {
            server.close();
            try {
                clientToServer.close();
            } catch (IOException ignored) {
                // 收尾关闭，流已无用
            }
        }
    }

    /** 捕获 server 写出的全部字节并按 '\n' 切行（JSON-RPC 报文逐行写，转义后不含裸换行）。 */
    private static final class CapturingOutputStream extends OutputStream {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final List<String> lines = new ArrayList<>();

        @Override
        public synchronized void write(int b) {
            pending.write(b);
            if (b == '\n') {
                lines.add(new String(pending.toByteArray(), StandardCharsets.UTF_8).trim());
                pending.reset();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        synchronized List<String> snapshot() {
            return List.copyOf(lines);
        }
    }

    // ------------------------------------------------------------------
    // JSON-RPC 报文构造
    // ------------------------------------------------------------------

    private static ObjectNode request(long id, String method) {
        ObjectNode node = JACKSON2.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        return node;
    }

    private static ObjectNode notification(String method) {
        ObjectNode node = JACKSON2.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        return node;
    }

    private static ObjectNode toolsCall(long id, String tool, Map<String, Object> arguments) {
        ObjectNode node = request(id, "tools/call");
        ObjectNode params = node.putObject("params");
        params.put("name", tool);
        params.set("arguments", JACKSON2.valueToTree(arguments));
        return node;
    }
}

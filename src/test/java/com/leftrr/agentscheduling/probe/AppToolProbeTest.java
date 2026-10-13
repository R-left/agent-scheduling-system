package com.leftrr.agentscheduling.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.ClaudeAdapter;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.CodexAdapter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AppToolProbe 参数校验与结果判定测试（研究 §2.2）。
 * 绝不真实拉起 claude/codex——真实 CLI 路径只在 main（javadoc 写明运行命令）。
 */
class AppToolProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Output(String out, String err) {}

    private static Output runParse(String cliId) {
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        CliId parsed = AppToolProbe.parseCliId(cliId, err);
        return new Output(parsed == null ? null : parsed.id(), errBytes.toString(StandardCharsets.UTF_8));
    }

    // ---------- argv 校验 ----------

    @Test
    void invalidCliIdPrintsUsageVerbatim() {
        for (String bad : new String[] {"gpt", "Claude", "", null}) {
            Output output = runParse(bad);
            assertNull(output.out(), "cliId=" + bad);
            assertEquals("用法：pnpm probe:tool <claude|codex>\n", output.err(), "cliId=" + bad);
        }
    }

    @Test
    void validCliIdsParseWithoutErrOutput() {
        assertEquals(new Output("claude", ""), runParse("claude"));
        assertEquals(new Output("codex", ""), runParse("codex"));
    }

    // ---------- 固定 prompt ----------

    @Test
    void promptIsFourLinesVerbatim() {
        assertEquals("我们准备给任务列表增加优先级功能。\n"
                + "请调用 request_clarification，询问一个会实质影响实现范围的问题。\n"
                + "提供 2 到 4 个清晰选项，并标记推荐项。\n"
                + "调用工具后，用一句话结束。", AppToolProbe.PROMPT);
    }

    // ---------- onEvent 打印 ----------

    @Test
    void toolCallEventPrintsHeaderAndPrettyJson() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("key", "值");
        input.put("n", 2);
        input.put("nested", true);
        ArrayNode list = input.putArray("list");
        list.add(1);
        list.add("x");
        input.putObject("empty");

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        AppToolProbe.printToolCall(
                new CliEvent.ToolCallEvent("t1", "request_clarification", input),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8));

        // 对齐 JSON.stringify(input, null, 2)：2 空格缩进、`"key": value`、空对象紧凑 `{}`
        String expected = "\n[应用工具] request_clarification\n"
                + "{\n"
                + "  \"key\": \"值\",\n"
                + "  \"n\": 2,\n"
                + "  \"nested\": true,\n"
                + "  \"list\": [\n"
                + "    1,\n"
                + "    \"x\"\n"
                + "  ],\n"
                + "  \"empty\": {}\n"
                + "}\n";
        assertEquals(expected, outBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nonToolCallEventsPrintNothing() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        AppToolProbe.printToolCall(new CliEvent.SessionEvent("s-1"), out);
        AppToolProbe.printToolCall(new CliEvent.ResultEvent("答案", "s-1", null), out);
        AppToolProbe.printToolCall(new CliEvent.ToolStartEvent("t1", "Bash", "运行命令", null), out);
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8));
    }

    // ---------- jsonStringify：JSON.stringify(x, null, 2) 等价 ----------

    @Test
    void jsonStringifyHandlesScalarsAndEmptyContainers() throws Exception {
        assertEquals("null", AppToolProbe.jsonStringify(MAPPER.nullNode()));
        assertEquals("42", AppToolProbe.jsonStringify(MAPPER.readTree("42")));
        assertEquals("\"a\\\"b\"", AppToolProbe.jsonStringify(MAPPER.readTree("\"a\\\"b\"")));
        assertEquals("true", AppToolProbe.jsonStringify(MAPPER.readTree("true")));
        assertEquals("{}", AppToolProbe.jsonStringify(MAPPER.createObjectNode()));
        assertEquals("[]", AppToolProbe.jsonStringify(MAPPER.createArrayNode()));
    }

    @Test
    void jsonStringifyEscapesAndKeepsUnicodeLiteral() throws Exception {
        // JSON 字符串转义与 JSON.stringify 一致：引号/反斜杠/控制符转义，非 ASCII 保持原样
        ObjectNode node = MAPPER.createObjectNode();
        node.put("s", "行\n换\"引\"\\斜");
        assertEquals("{\n  \"s\": \"行\\n换\\\"引\\\"\\\\斜\"\n}",
                AppToolProbe.jsonStringify(node));
    }

    // ---------- 结束判定 ----------

    private static CliRunResult resultWithToolCalls(int count) {
        List<CliRunResult.ToolCall> calls = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            calls.add(new CliRunResult.ToolCall("t" + i, "request_clarification", MAPPER.createObjectNode()));
        }
        return new CliRunResult("一句话结束", "s-1", null, calls);
    }

    @Test
    void completionLineVerbatim() {
        CliAdapter claude = new ClaudeAdapter();
        assertEquals("\n[完成] Claude Code 返回 2 次应用工具调用",
                AppToolProbe.completionLine(claude, resultWithToolCalls(2)));
        CliAdapter codex = new CodexAdapter();
        assertEquals("\n[完成] Codex 返回 1 次应用工具调用",
                AppToolProbe.completionLine(codex, resultWithToolCalls(1)));
    }

    @Test
    void missingOrEmptyToolCallsThrows() {
        CliAdapter claude = new ClaudeAdapter();
        CliRunResult noCalls = new CliRunResult("一句话结束", "s-1", null, null);
        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> AppToolProbe.completionLine(claude, noCalls));
        assertEquals("Claude Code 没有调用 request_clarification", e1.getMessage());

        CliRunResult emptyCalls = new CliRunResult("一句话结束", "s-1", null, List.of());
        IllegalStateException e2 = assertThrows(IllegalStateException.class,
                () -> AppToolProbe.completionLine(claude, emptyCalls));
        assertEquals("Claude Code 没有调用 request_clarification", e2.getMessage());

        IllegalStateException e3 = assertThrows(IllegalStateException.class,
                () -> AppToolProbe.completionLine(new CodexAdapter(), noCalls));
        assertEquals("Codex 没有调用 request_clarification", e3.getMessage());
    }

    // ---------- workspace 解析 ----------

    @Test
    void workspaceResolvesRelativeToUserDirAndNormalizes() {
        String expected = Path.of("a", "..", "b").toAbsolutePath().normalize().toString();
        assertEquals(expected, AppToolProbe.resolveWorkspace("a/../b"));
    }

    @Test
    void missingWorkspaceArgFallsBackToUserDir() {
        assertEquals(Path.of("").toAbsolutePath().normalize().toString(),
                AppToolProbe.resolveWorkspace(null));
    }
}

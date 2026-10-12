package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodexAdapter fixture 测试：每条 fixture 为一行 JSON 字符串，断言事件序列与字段。
 * 语义对齐 TS codex-adapter.ts（含 shortText 72 截断、NBSP 折叠、arguments ?? input 等）。
 * 平台分支通过包级构造器注入 BooleanSupplier，不依赖运行平台。
 */
class CodexAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CodexAdapter adapter = new CodexAdapter(() -> true);

    // ---------- 常量 ----------

    @Test
    void exposesConstants() {
        assertEquals(CliId.CODEX, adapter.id());
        assertEquals("codex", adapter.command());
        assertEquals("Codex", adapter.displayName());
    }

    // ---------- parseEvents fixture ----------

    @Test
    void fixture1NonJsonLinesReturnEmptyWithoutThrowing() {
        assertEquals(List.of(), adapter.parseEvents("not-json"));
        assertEquals(List.of(), adapter.parseEvents(""));
        // 合法 JSON 但非对象：字符串与数字 → 无 type 字段 → 空列表
        assertEquals(List.of(), adapter.parseEvents("\"纯字符串\""));
        assertEquals(List.of(), adapter.parseEvents("123"));
    }

    @Test
    void fixture2aThreadStartedEmitsSessionEvent() {
        String line = "{\"type\":\"thread.started\",\"thread_id\":\"th1\"}";
        assertEquals(List.of(new CliEvent.SessionEvent("th1")), adapter.parseEvents(line));
    }

    @Test
    void fixture2bThreadStartedWithNonTextThreadIdEmitsNothing() {
        String line = "{\"type\":\"thread.started\",\"thread_id\":123}";
        assertEquals(List.of(), adapter.parseEvents(line));
    }

    @Test
    void fixture3ErrorEventUsesMessageField() {
        String line = "{\"type\":\"error\",\"message\":\"坏了\"}";
        assertEquals(List.of(new CliEvent.ErrorEvent("坏了", null)), adapter.parseEvents(line));
    }

    @Test
    void fixture4TurnFailedUsesNestedErrorMessage() {
        String line = "{\"type\":\"turn.failed\",\"error\":{\"message\":\"失败原因\"}}";
        assertEquals(List.of(new CliEvent.ErrorEvent("失败原因", null)), adapter.parseEvents(line));
    }

    @Test
    void fixture5ErrorWithoutAnyMessageFallsBackToLiteral() {
        String line = "{\"type\":\"error\"}";
        assertEquals(List.of(new CliEvent.ErrorEvent("Codex 执行失败", null)), adapter.parseEvents(line));
    }

    @Test
    void fixture6aTurnCompletedCarriesUsageStats() {
        String line = "{\"type\":\"turn.completed\",\"usage\":{"
                + "\"input_tokens\":10,\"output_tokens\":5,\"cached_input_tokens\":3}}";
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(1, events.size());
        CliEvent.StatsEvent statsEvent = assertInstanceOf(CliEvent.StatsEvent.class, events.get(0));
        CliRunStats stats = statsEvent.stats();
        // totalTokens = input + output（不含 cached）
        assertEquals(15L, stats.totalTokens());
        assertEquals(10L, stats.inputTokens());
        assertEquals(5L, stats.outputTokens());
        assertEquals(3L, stats.cacheReadTokens());
        assertNull(stats.durationMs());
        assertNull(stats.turns());
    }

    @Test
    void fixture6bTurnCompletedWithoutUsageEmitsNothing() {
        assertEquals(List.of(), adapter.parseEvents("{\"type\":\"turn.completed\"}"));
    }

    @Test
    void fixture6cTurnCompletedWithAllEmptyUsageEmitsNothing() {
        // Review Focus 3：usage 全空字段 → parseStats 整体 null（TS some(v => v !== undefined)）
        assertEquals(List.of(), adapter.parseEvents("{\"type\":\"turn.completed\",\"usage\":{}}"));
    }

    @Test
    void fixture7ItemCompletedAgentMessageEmitsResultEvent() {
        String line = "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"答案\"}}";
        assertEquals(
                List.of(new CliEvent.ResultEvent("答案", null, null)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture8CommandExecutionStartFoldsWhitespaceRuns() {
        // 连续空白折叠为单空格； （NBSP）也被 /\s+/g 折叠（UNICODE_CHARACTER_CLASS）
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"command_execution\",\"command\":\"npm test  test\"}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("i1", "Bash", "运行命令", "npm test test")),
                adapter.parseEvents(line));

        String nbspLine = "{\"type\":\"item.started\",\"item\":{\"id\":\"i2\","
                + "\"type\":\"command_execution\",\"command\":\"a\\u00A0b\"}}";
        List<CliEvent> events = adapter.parseEvents(nbspLine);
        assertEquals(1, events.size());
        assertEquals("a b", assertInstanceOf(CliEvent.ToolStartEvent.class, events.get(0)).detail());
    }

    @Test
    void fixture9aFileChangeWithPathFieldUsesItAsDetail() {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"file_change\",\"path\":\"a/b/c.txt\"}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("i1", "Edit", "修改文件", "a/b/c.txt")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture9bFileChangeWithChangesArrayUsesFirstChangePath() {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i2\","
                + "\"type\":\"file_change\",\"changes\":[{\"path\":\"x/y.txt\"}]}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("i2", "Edit", "修改文件", "x/y.txt")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture10WebSearchStartCarriesQuery() {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"web_search\",\"query\":\"飞书 api\"}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("i1", "WebSearch", "搜索资料", "飞书 api")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture11aAgentOsMcpToolCallEmitsStartAndCall() throws Exception {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\","
                + "\"server\":\"agent_os\",\"tool\":\"request_spec_approval\",\"arguments\":{\"a\":1}}}";
        JsonNode input = MAPPER.readTree("{\"a\":1}");
        assertEquals(
                List.of(
                        new CliEvent.ToolStartEvent("i1", "MCP", "调用外部工具", "agent_os.request_spec_approval"),
                        new CliEvent.ToolCallEvent("i1", "request_spec_approval", input)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture11bClarificationToolCallMapped() throws Exception {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\","
                + "\"server\":\"agent_os\",\"tool\":\"request_clarification\",\"arguments\":{\"q\":1}}}";
        JsonNode input = MAPPER.readTree("{\"q\":1}");
        assertEquals(
                List.of(
                        new CliEvent.ToolStartEvent("i1", "MCP", "调用外部工具", "agent_os.request_clarification"),
                        new CliEvent.ToolCallEvent("i1", AppTools.CLARIFICATION_TOOL_NAME, input)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture11cDispatchTaskToolCallMapped() throws Exception {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\","
                + "\"server\":\"agent_os\",\"tool\":\"dispatch_task\",\"arguments\":{\"t\":1}}}";
        JsonNode input = MAPPER.readTree("{\"t\":1}");
        assertEquals(
                List.of(
                        new CliEvent.ToolStartEvent("i1", "MCP", "调用外部工具", "agent_os.dispatch_task"),
                        new CliEvent.ToolCallEvent("i1", AppTools.DISPATCH_TASK_TOOL_NAME, input)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture12McpToolCallWithoutArgumentsUsesInputNode() throws Exception {
        // 对齐 TS `arguments ?? input`：无 arguments 时 ToolCallEvent.input 取 input 节点
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\","
                + "\"server\":\"agent_os\",\"tool\":\"dispatch_task\",\"input\":{\"k\":\"v\"}}}";
        JsonNode input = MAPPER.readTree("{\"k\":\"v\"}");
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(2, events.size());
        assertEquals(
                new CliEvent.ToolCallEvent("i1", AppTools.DISPATCH_TASK_TOOL_NAME, input),
                events.get(1));
    }

    @Test
    void fixture13aNonAgentOsServerEmitsToolStartOnly() {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\","
                + "\"server\":\"other\",\"tool\":\"dispatch_task\",\"arguments\":{\"a\":1}}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("i1", "MCP", "调用外部工具", "other.dispatch_task")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture13bToolOutsideThreeNamesEmitsToolStartOnly() {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"mcp_tool_call\","
                + "\"server\":\"agent_os\",\"tool\":\"other_tool\",\"arguments\":{\"a\":1}}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("i1", "MCP", "调用外部工具", "agent_os.other_tool")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture14aItemCompletedFailedStatusMarksToolEndFailed() {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"command_execution\",\"status\":\"failed\"}}";
        assertEquals(List.of(new CliEvent.ToolEndEvent("i1", true)), adapter.parseEvents(line));
    }

    @Test
    void fixture14bNonZeroExitCodeMarksToolEndFailed() {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"command_execution\",\"exit_code\":1}}";
        assertEquals(List.of(new CliEvent.ToolEndEvent("i1", true)), adapter.parseEvents(line));
    }

    @Test
    void fixture14cZeroExitCodeWithoutStatusMarksToolEndSucceeded() {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"command_execution\",\"exit_code\":0}}";
        assertEquals(List.of(new CliEvent.ToolEndEvent("i1", false)), adapter.parseEvents(line));
    }

    @Test
    void fixture15aItemWithoutIdEmitsNothing() {
        String line = "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"ls\"}}";
        assertEquals(List.of(), adapter.parseEvents(line));
    }

    @Test
    void fixture15bItemWithUnrecognizedTypeEmitsNothing() {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\"}}";
        assertEquals(List.of(), adapter.parseEvents(line));
    }

    // ---------- buildArgs / buildResumeArgs / buildCompactPlan ----------

    @Test
    void buildArgsWindowsUsesBypassFlag() {
        CodexAdapter windows = new CodexAdapter(() -> true);
        List<String> args = windows.buildArgs("问", CliPromptInput.ARGUMENT);
        List<String> toolArgs = AppTools.codexAppToolArgs();
        assertEquals(4, toolArgs.size());
        // 前置 codexAppToolArgs 4 元素 + exec/--json/--skip-git-repo-check/平台标志/prompt = 9 元素
        assertEquals(9, args.size());
        assertEquals(toolArgs, args.subList(0, 4));
        assertEquals("exec", args.get(4));
        assertEquals("--json", args.get(5));
        assertEquals("--skip-git-repo-check", args.get(6));
        assertEquals("--dangerously-bypass-approvals-and-sandbox", args.get(7));
        assertEquals("问", args.get(8));
    }

    @Test
    void buildArgsNonWindowsUsesYoloFlag() {
        CodexAdapter nonWindows = new CodexAdapter(() -> false);
        List<String> args = nonWindows.buildArgs("问", CliPromptInput.ARGUMENT);
        assertEquals(9, args.size());
        assertEquals("--yolo", args.get(7));
        assertEquals("问", args.get(8));
    }

    @Test
    void buildResumeArgsInsertsResumeAndSessionId() {
        CodexAdapter windows = new CodexAdapter(() -> true);
        List<String> args = windows.buildResumeArgs("问", "th1", CliPromptInput.ARGUMENT);
        List<String> toolArgs = AppTools.codexAppToolArgs();
        assertEquals(11, args.size());
        assertEquals(toolArgs, args.subList(0, 4));
        assertEquals("exec", args.get(4));
        assertEquals("resume", args.get(5));
        assertEquals("--json", args.get(6));
        assertEquals("--skip-git-repo-check", args.get(7));
        assertEquals("th1", args.get(8));
        assertEquals("--dangerously-bypass-approvals-and-sandbox", args.get(9));
        assertEquals("问", args.get(10));
    }

    @Test
    void buildArgsStdinModeUsesDashPlaceholder() {
        BooleanSupplier alwaysWindows = () -> true;
        List<String> args = new CodexAdapter(alwaysWindows).buildArgs("问", CliPromptInput.STDIN);
        assertEquals("-", args.get(args.size() - 1));
        assertTrue(!args.contains("问"));

        List<String> resumeArgs =
                new CodexAdapter(alwaysWindows).buildResumeArgs("问", "th1", CliPromptInput.STDIN);
        assertEquals("-", resumeArgs.get(resumeArgs.size() - 1));
        assertTrue(!resumeArgs.contains("问"));
    }

    @Test
    void buildCompactPlanIgnoresInstructionsAndTargetsAppServer() {
        CliCompactPlan.CodexAppServer plan = assertInstanceOf(
                CliCompactPlan.CodexAppServer.class, adapter.buildCompactPlan("th1", null));
        assertEquals("codex", plan.command());
        assertEquals(List.of("app-server", "--stdio"), plan.args());
        assertEquals("th1", plan.sessionId());

        // instructions 被忽略——对齐 TS：Codex 的 compact 走 app-server JSON-RPC
        CliCompactPlan.CodexAppServer withInstructions = assertInstanceOf(
                CliCompactPlan.CodexAppServer.class, adapter.buildCompactPlan("th1", "自定义指令"));
        assertEquals(List.of("app-server", "--stdio"), withInstructions.args());
    }
}

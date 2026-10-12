package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClaudeAdapter fixture 测试：每条 fixture 为一行 JSON 字符串，断言事件序列与字段。
 * 语义对齐 TS claude-adapter.ts（含 shortPath 取末 3 段、shortText 72 截断、NBSP 折叠等）。
 */
class ClaudeAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClaudeAdapter adapter = new ClaudeAdapter();

    // ---------- 常量 ----------

    @Test
    void exposesConstants() {
        assertEquals(CliId.CLAUDE, adapter.id());
        assertEquals("claude", adapter.command());
        assertEquals("Claude Code", adapter.displayName());
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
    void fixture2SystemInitEmitsSessionEvent() {
        String line = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\"}";
        assertEquals(List.of(new CliEvent.SessionEvent("s1")), adapter.parseEvents(line));
    }

    @Test
    void fixture3SystemInitWithoutSessionIdEmitsNothing() {
        String line = "{\"type\":\"system\",\"subtype\":\"init\"}";
        assertEquals(List.of(), adapter.parseEvents(line));
    }

    @Test
    void fixture4AssistantUsageOnlyEmitsContextEvent() {
        String line = "{\"type\":\"assistant\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}}";
        assertEquals(List.of(new CliEvent.ContextEvent(15)), adapter.parseEvents(line));
    }

    @Test
    void fixture5AssistantReadToolContextFirstThenToolStart() {
        String line = "{\"type\":\"assistant\",\"message\":{\"usage\":{\"input_tokens\":1},"
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\","
                + "\"input\":{\"file_path\":\"D:\\\\work\\\\a\\\\b\\\\c.txt\"}}]}}";
        // shortPath 取末 3 段：D:/work/a/b/c.txt → a/b/c.txt
        assertEquals(
                List.of(
                        new CliEvent.ContextEvent(1),
                        new CliEvent.ToolStartEvent("t1", "Read", "读取文件", "a/b/c.txt")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture5bShortPathKeepsAllPartsWhenFewerThanLimit() {
        // Review Focus 5：元素不足时从头取（Math.max 对齐负索引语义）
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t5\",\"name\":\"Read\","
                + "\"input\":{\"file_path\":\"b/c.txt\"}}]}}";
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(1, events.size());
        assertEquals("b/c.txt", assertInstanceOf(CliEvent.ToolStartEvent.class, events.get(0)).detail());
    }

    @Test
    void fixture6UnknownToolFallsBackToGenericLabelAndNullDetail() {
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Foo\",\"input\":{}}]}}";
        assertEquals(
                List.of(new CliEvent.ToolStartEvent("t2", "Foo", "调用 Foo", null)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture7GlobPatternTruncatedTo71CharsPlusEllipsis() {
        String pattern = "a".repeat(80);
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t3\",\"name\":\"Glob\","
                + "\"input\":{\"pattern\":\"" + pattern + "\"}}]}}";
        String expectedDetail = "a".repeat(71) + "…";
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(1, events.size());
        CliEvent.ToolStartEvent start = assertInstanceOf(CliEvent.ToolStartEvent.class, events.get(0));
        assertEquals("查找文件", start.label());
        assertEquals(expectedDetail, start.detail());
    }

    @Test
    void fixture8WhitespaceRunFoldedToSingleSpace() {
        // JSON 内 \u00A0（NBSP）：/\s+/g 折叠（UNICODE_CHARACTER_CLASS 对齐 JS 语义）
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t4\",\"name\":\"Bash\","
                + "\"input\":{\"description\":\"a\\u00A0b\"}}]}}";
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(1, events.size());
        CliEvent.ToolStartEvent start = assertInstanceOf(CliEvent.ToolStartEvent.class, events.get(0));
        assertEquals("运行命令", start.label());
        assertEquals("a b", start.detail());
    }

    @Test
    void fixture9aClarificationToolCallMapped() throws Exception {
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"mcp__agent_os__request_clarification\","
                + "\"input\":{\"questions\":[\"q1\"]}}]}}";
        JsonNode input = MAPPER.readTree("{\"questions\":[\"q1\"]}");
        assertEquals(
                List.of(
                        new CliEvent.ToolStartEvent("t1", "mcp__agent_os__request_clarification",
                                "调用 mcp__agent_os__request_clarification", null),
                        new CliEvent.ToolCallEvent("t1", AppTools.CLARIFICATION_TOOL_NAME, input)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture9bProductSpecToolCallMapped() throws Exception {
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"mcp__agent_os__request_spec_approval\","
                + "\"input\":{\"spec\":\"s\"}}]}}";
        JsonNode input = MAPPER.readTree("{\"spec\":\"s\"}");
        assertEquals(
                List.of(
                        new CliEvent.ToolStartEvent("t2", "mcp__agent_os__request_spec_approval",
                                "调用 mcp__agent_os__request_spec_approval", null),
                        new CliEvent.ToolCallEvent("t2", AppTools.PRODUCT_SPEC_TOOL_NAME, input)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture9cDispatchTaskToolCallMapped() throws Exception {
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"t3\",\"name\":\"mcp__agent_os__dispatch_task\","
                + "\"input\":{\"task\":\"d\"}}]}}";
        JsonNode input = MAPPER.readTree("{\"task\":\"d\"}");
        assertEquals(
                List.of(
                        new CliEvent.ToolStartEvent("t3", "mcp__agent_os__dispatch_task",
                                "调用 mcp__agent_os__dispatch_task", null),
                        new CliEvent.ToolCallEvent("t3", AppTools.DISPATCH_TASK_TOOL_NAME, input)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture10UserToolResultsEmitToolEndEvents() {
        String line = "{\"type\":\"user\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"is_error\":true}]}}";
        assertEquals(
                List.of(
                        new CliEvent.ToolEndEvent("t1", false),
                        new CliEvent.ToolEndEvent("t2", true)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture11aResultErrorUsesResultTextAsMessage() {
        String line = "{\"type\":\"result\",\"is_error\":true,\"result\":\"出错了\",\"session_id\":\"s1\"}";
        assertEquals(
                List.of(new CliEvent.ErrorEvent("出错了", "s1")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture11bResultErrorWithoutResultTextFallsBackToLiteralMessage() {
        String line = "{\"type\":\"result\",\"is_error\":true,\"session_id\":\"s1\"}";
        assertEquals(
                List.of(new CliEvent.ErrorEvent("Claude Code 执行失败", "s1")),
                adapter.parseEvents(line));
    }

    @Test
    void fixture11cResultStringIsErrorTakesSuccessBranch() {
        // TS `if (event.is_error)` 为 truthy 判定：字符串 "true" 等真值在 TS 也会触发 error 事件；
        // Java 侧刻意采用严格布尔判定（已知分歧，ClaudeAdapter 注释备查），字符串 "true" 不触发
        // ErrorEvent——注意期望不是空列表，result 文本合法时成功分支照常产出 ResultEvent
        String line = "{\"type\":\"result\",\"is_error\":\"true\",\"result\":\"出错了\",\"session_id\":\"s1\"}";
        assertEquals(
                List.of(new CliEvent.ResultEvent("出错了", "s1", null)),
                adapter.parseEvents(line));
    }

    @Test
    void fixture12ResultSuccessCarriesFullStats() {
        String line = "{\"type\":\"result\",\"result\":\"答案\",\"session_id\":\"s1\","
                + "\"duration_ms\":100,\"num_turns\":2,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                + "\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":1},"
                + "\"modelUsage\":{\"claude-x\":{\"contextWindow\":200000},"
                + "\"claude-y\":{\"contextWindow\":1000000}}}";
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(1, events.size());
        CliEvent.ResultEvent result = assertInstanceOf(CliEvent.ResultEvent.class, events.get(0));
        assertEquals("答案", result.answer());
        assertEquals("s1", result.sessionId());
        CliRunStats stats = result.stats();
        assertEquals(100, stats.durationMs());
        assertEquals(2, stats.turns());
        // totalTokens = 10+5+3+1（只累加存在的数值字段）
        assertEquals(19L, stats.totalTokens());
        assertEquals(10L, stats.inputTokens());
        assertEquals(5L, stats.outputTokens());
        assertEquals(3L, stats.cacheReadTokens());
        assertEquals(1L, stats.cacheCreationTokens());
        // contextUsedTokens 不由 parseStats 填充（TS parseStats 从不设置该字段）
        assertNull(stats.contextUsedTokens());
        // modelUsage 多模型取 >0 的最大 contextWindow
        assertEquals(1000000L, stats.contextWindowTokens());
    }

    @Test
    void fixture13ResultSuccessWithAllEmptyStatsYieldsNullStats() {
        String line = "{\"type\":\"result\",\"result\":\"答案\",\"session_id\":\"s1\","
                + "\"usage\":{},\"modelUsage\":{}}";
        List<CliEvent> events = adapter.parseEvents(line);
        assertEquals(1, events.size());
        CliEvent.ResultEvent result = assertInstanceOf(CliEvent.ResultEvent.class, events.get(0));
        assertEquals("答案", result.answer());
        assertNull(result.stats());
    }

    @Test
    void fixture14UnknownTopLevelTypeEmitsNothing() {
        assertEquals(List.of(), adapter.parseEvents("{\"type\":\"stream_event\"}"));
    }

    // ---------- buildArgs / buildResumeArgs / buildCompactPlan ----------

    @Test
    void buildArgsArgumentModeEndsWithOutputAndMcpConfig() throws Exception {
        List<String> args = adapter.buildArgs("问", CliPromptInput.ARGUMENT);
        assertEquals(8, args.size());
        assertEquals("--dangerously-skip-permissions", args.get(0));
        assertEquals("-p", args.get(1));
        assertEquals("问", args.get(2));
        assertEquals("--output-format", args.get(3));
        assertEquals("stream-json", args.get(4));
        assertEquals("--verbose", args.get(5));
        assertEquals("--mcp-config", args.get(6));
        // 第 8 个参数为内联 MCP 配置 JSON（结构断言，AppToolsTest 已覆盖细节）
        JsonNode config = MAPPER.readTree(args.get(7));
        assertTrue(config.has("mcpServers"), () -> "mcp-config JSON 缺少 mcpServers：" + args.get(7));
    }

    @Test
    void buildArgsStdinModeOmitsPromptParameter() {
        List<String> args = adapter.buildArgs("问", CliPromptInput.STDIN);
        assertEquals(7, args.size());
        // -p 之后直接 --output-format，prompt 不进参数（写入 stdin）
        assertEquals("-p", args.get(1));
        assertEquals("--output-format", args.get(2));
        assertFalse(args.contains("问"));
    }

    @Test
    void buildResumeArgsPrependsResumeSessionId() {
        List<String> args = adapter.buildResumeArgs("问", "sess", CliPromptInput.ARGUMENT);
        assertEquals("--resume", args.get(0));
        assertEquals("sess", args.get(1));
        // 其余与 buildArgs 完全一致
        assertEquals(adapter.buildArgs("问", CliPromptInput.ARGUMENT), args.subList(2, args.size()));
    }

    @Test
    void buildCompactPlanTrimsInstructionsIntoCommand() {
        CliCompactPlan.ClaudeStreamJson plan =
                assertInstanceOf(CliCompactPlan.ClaudeStreamJson.class,
                        adapter.buildCompactPlan("sess", "  精简  "));
        // command 字段 = 可执行名（Task 8 CliProcess.spawn(plan.command(), ...) 消费）
        assertEquals("claude", plan.command());
        assertEquals(adapter.buildResumeArgs("/compact 精简", "sess", CliPromptInput.current()), plan.args());
        assertEquals("/compact 精简", plan.prompt());
    }

    @Test
    void buildCompactPlanNullOrBlankInstructionsDefaultsToBareCompact() {
        CliCompactPlan.ClaudeStreamJson nullPlan =
                assertInstanceOf(CliCompactPlan.ClaudeStreamJson.class,
                        adapter.buildCompactPlan("sess", null));
        assertEquals("claude", nullPlan.command());
        assertEquals("/compact", nullPlan.prompt());
        assertEquals(adapter.buildResumeArgs("/compact", "sess", CliPromptInput.current()), nullPlan.args());

        // 空白（含全角空格 \u3000）经 jsTrim 后同归默认
        CliCompactPlan.ClaudeStreamJson blankPlan =
                assertInstanceOf(CliCompactPlan.ClaudeStreamJson.class,
                        adapter.buildCompactPlan("sess", " \u3000"));
        assertEquals("/compact", blankPlan.prompt());
    }
}

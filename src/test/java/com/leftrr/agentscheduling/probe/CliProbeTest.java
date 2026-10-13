package com.leftrr.agentscheduling.probe;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CliProbe 四段事件文案测试：注入 Reader/PrintStream/时钟，逐字对齐 TS probe-cli.ts 的输出
 * （研究 §2.1）。不真实拉起 CLI——探针消费的是现成的 JSON 事件行。
 */
class CliProbeTest {

    /** 注入固定起始时间与步长的时钟，返回捕获的输出。 */
    private static String run(String input, long startMs, long stepMs) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        AtomicLong now = new AtomicLong(startMs);
        CliProbe.processLines(new BufferedReader(new StringReader(input)), out, () -> now.getAndAdd(stepMs));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    // ---------- Claude Code 三段 ----------

    @Test
    void claudeSegmentsVerbatim() throws Exception {
        String input = String.join("\n",
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\",\"model\":\"claude-sonnet\"}",
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"你好\"},"
                        + "{\"type\":\"tool_use\",\"name\":\"Bash\"}]}}",
                "{\"type\":\"result\",\"num_turns\":3,\"duration_ms\":1234,\"total_cost_usd\":0.05,"
                        + "\"result\":\"完成\"}");
        String expected = String.join("\n",
                "[0.0s] 会话开始 session_id=s-1 model=claude-sonnet",
                "[0.0s] 模型说: 你好",
                "[0.0s] 调用工具: Bash",
                "[0.0s] 完成 turns=3 耗时=1234ms 成本=$0.05",
                "[0.0s] 最终回答: 完成",
                "");
        assertEquals(expected, run(input, 0, 0));
    }

    @Test
    void claudeSystemNonInitIgnored() throws Exception {
        assertEquals("", run("{\"type\":\"system\",\"subtype\":\"other\"}", 0, 0));
    }

    @Test
    void claudeEmptyTextBlockSkippedAndNonObjectBlocksIgnored() throws Exception {
        // TS `block.type === 'text' && block.text`：空串 falsy 不打印；非对象块 type 为 undefined 跳过
        String input = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "\"str-元素\",{\"type\":\"text\",\"text\":\"\"},{\"type\":\"tool_use\"}]}}";
        assertEquals("[0.0s] 调用工具: undefined\n", run(input, 0, 0));
    }

    @Test
    void claudeMissingMessageContentPrintsNothing() throws Exception {
        assertEquals("", run("{\"type\":\"assistant\"}", 0, 0));
    }

    // ---------- Codex 三段 ----------

    @Test
    void codexSegmentsVerbatim() throws Exception {
        String input = String.join("\n",
                "{\"type\":\"thread.started\",\"thread_id\":\"th-1\"}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"方案如下\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"command\":\"ls -la\"}}",
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
        String expected = String.join("\n",
                "[0.0s] 会话开始 thread_id=th-1",
                "[0.0s] 模型说: 方案如下",
                "[0.0s] 执行命令: ls -la",
                "[0.0s] 完成 tokens={\"input_tokens\":10,\"output_tokens\":5}",
                "");
        assertEquals(expected, run(input, 0, 0));
    }

    @Test
    void codexItemCompletedOtherItemTypesIgnored() throws Exception {
        String input = "{\"type\":\"item.completed\",\"item\":{\"type\":\"file_change\",\"path\":\"a.txt\"}}";
        assertEquals("", run(input, 0, 0));
    }

    // ---------- 公共分支 ----------

    @Test
    void nonJsonAndBlankLinesSkippedSilently() throws Exception {
        String input = String.join("\n",
                "not-json",
                "",
                "[WARN] 某 CLI 日志噪音",
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\",\"model\":\"m\"}");
        assertEquals("[0.0s] 会话开始 session_id=s model=m\n", run(input, 0, 0));
    }

    @Test
    void unknownTypeAndNonObjectJsonIgnored() throws Exception {
        String input = String.join("\n",
                "{\"type\":\"unknown\"}",
                "\"纯字符串\"",
                "123",
                "null");
        assertEquals("", run(input, 0, 0));
    }

    @Test
    void missingFieldsPrintUndefinedLikeTsTemplate() throws Exception {
        // TS 模板串 ${ev.x} 对缺失字段打印 "undefined"
        String input = String.join("\n",
                "{\"type\":\"system\",\"subtype\":\"init\"}",
                "{\"type\":\"result\"}",
                "{\"type\":\"turn.completed\"}");
        String expected = String.join("\n",
                "[0.0s] 会话开始 session_id=undefined model=undefined",
                "[0.0s] 完成 turns=undefined 耗时=undefinedms 成本=$undefined",
                "[0.0s] 最终回答: undefined",
                "[0.0s] 完成 tokens=undefined",
                "");
        assertEquals(expected, run(input, 0, 0));
    }

    @Test
    void relativeTimestampFormatMatchesTofixedOne() throws Exception {
        // t0 之后第 1 行 +1.5s、第 2 行 +3.0s：[${((Date.now()-t0)/1000).toFixed(1)}s]
        String input = String.join("\n",
                "{\"type\":\"thread.started\",\"thread_id\":\"a\"}",
                "{\"type\":\"thread.started\",\"thread_id\":\"b\"}");
        String expected = String.join("\n",
                "[1.5s] 会话开始 thread_id=a",
                "[3.0s] 会话开始 thread_id=b",
                "");
        assertEquals(expected, run(input, 0, 1500));
    }

    @Test
    void turnCompletedUsageNestedJsonStaysCompact() throws Exception {
        // JSON.stringify(ev.usage) 为紧凑序列化（无缩进），键序保持插入序
        String input = "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,"
                + "\"cached_input_tokens\":4,\"output_tokens\":5}}";
        assertEquals("[0.0s] 完成 tokens={\"input_tokens\":10,\"cached_input_tokens\":4,\"output_tokens\":5}\n",
                run(input, 0, 0));
    }
}

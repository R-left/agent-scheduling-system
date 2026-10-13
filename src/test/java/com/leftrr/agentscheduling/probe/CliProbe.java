package com.leftrr.agentscheduling.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * AI CLI 事件流解析器：从 stdin 读 headless 模式的 JSON 行，打印事件时间线。
 * 逐字移植 TS src/probe-cli.ts（文案、相对时间戳、分支全集一致；事件 type 集合为
 * ClaudeAdapter/CodexAdapter parseEvents 原始 type 的观测子集，与 TS 探针对 adapter 的关系相同）。
 *
 * <p>用法（对齐 TS 头注释，stdin 接 CLI 的 JSON 事件流）：
 * <pre>
 *   claude -p "..." --output-format stream-json --verbose | &lt;本探针&gt;
 *   codex exec --json "..." | &lt;本探针&gt;
 * </pre>
 *
 * <p>运行（对齐 LarkProbe 先例，test classpath）：
 * <pre>
 *   mvn test-compile exec:java -Dexec.mainClass=com.leftrr.agentscheduling.probe.CliProbe -Dexec.classpathScope=test
 * </pre>
 *
 * <p>探针输出即产品（对标 TS console.log），沿用 System.out 而非 SLF4J（M7 裁决 2 的
 * 「TS console 对等」口径；SLF4J pattern 会破坏逐字文案）。
 */
public final class CliProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        processLines(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out, System::currentTimeMillis);
    }

    /** 核心循环：可注入 Reader/PrintStream/时钟（时钟仅测试注入；main 用系统墙钟）。 */
    static void processLines(BufferedReader reader, PrintStream out, LongSupplier clock) throws IOException {
        long t0 = clock.getAsLong();
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode ev;
            try {
                ev = MAPPER.readTree(line);
            } catch (Exception e) {
                continue; // 非 JSON 行（日志噪音）直接跳过
            }
            printEvent(ev, stamp(clock.getAsLong() - t0), out);
        }
    }

    /** 对齐 TS `switch (ev.type)` 的四段分支（Claude 三段 + Codex 三段）。 */
    private static void printEvent(JsonNode ev, String stamp, PrintStream out) throws IOException {
        String type = text(ev, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            // ── Claude Code ──
            case "system" -> {
                if ("init".equals(text(ev, "subtype"))) {
                    line(out, stamp + " 会话开始 session_id=" + value(ev, "session_id")
                            + " model=" + value(ev, "model"));
                }
            }
            case "assistant" -> {
                // 对齐 TS `ev.message?.content ?? []`：仅数组可迭代；非对象块与 TS 一样两分支皆不命中
                JsonNode content = ev.path("message").path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if (!block.isObject()) {
                            continue;
                        }
                        if ("text".equals(text(block, "type")) && isTruthy(block.path("text"))) {
                            line(out, stamp + " 模型说: " + value(block, "text"));
                        }
                        if ("tool_use".equals(text(block, "type"))) {
                            line(out, stamp + " 调用工具: " + value(block, "name"));
                        }
                    }
                }
            }
            case "result" -> {
                line(out, stamp + " 完成 turns=" + value(ev, "num_turns")
                        + " 耗时=" + value(ev, "duration_ms") + "ms 成本=$" + value(ev, "total_cost_usd"));
                line(out, stamp + " 最终回答: " + value(ev, "result"));
            }

            // ── Codex ──
            case "thread.started" ->
                line(out, stamp + " 会话开始 thread_id=" + value(ev, "thread_id"));
            case "item.completed" -> {
                JsonNode item = ev.path("item");
                if ("agent_message".equals(text(item, "type"))) {
                    line(out, stamp + " 模型说: " + value(item, "text"));
                }
                if ("command_execution".equals(text(item, "type"))) {
                    line(out, stamp + " 执行命令: " + value(item, "command"));
                }
            }
            case "turn.completed" ->
                line(out, stamp + " 完成 tokens=" + compactJsonOrUndefined(ev.path("usage")));
            default -> {
                // 其余 type 不打印（与 TS 无 default 分支一致）
            }
        }
    }

    /** console.log 等价：恒以 "\n" 收尾（不用 println，避免 Windows 换行成 \r\n 偏离 TS 输出）。 */
    private static void line(PrintStream out, String s) {
        out.print(s + "\n");
    }

    /** 对齐 TS `[${((Date.now()-t0)/1000).toFixed(1)}s]`（toFixed 四舍五入 1 位小数）。 */
    private static String stamp(long elapsedMs) {
        return "[" + String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0) + "s]";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /**
     * 对齐 TS 模板串 ${ev.x} 的 ToString 语义：缺失 → "undefined"、JSON null → "null"、
     * 标量 → 字面文本；数组 → 元素 ToString 以 "," 连接（Array.prototype.toString）；
     * 对象 → "[object Object]"（探针各字段实际只会出现标量，容器分支为完备性兜底）。
     */
    private static String value(JsonNode node, String field) {
        return toDisplayString(node.path(field));
    }

    private static String toDisplayString(JsonNode v) {
        if (v.isMissingNode()) {
            return "undefined";
        }
        if (v.isNull()) {
            return "null";
        }
        if (v.isValueNode()) {
            return v.asText();
        }
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode element : v) {
                parts.add(toDisplayString(element));
            }
            return String.join(",", parts);
        }
        return "[object Object]";
    }

    /** 对齐 JS truthiness：false、null/undefined、空串、0、NaN 为假，其余为真。 */
    private static boolean isTruthy(JsonNode v) {
        if (v.isMissingNode() || v.isNull() || (v.isBoolean() && !v.asBoolean())) {
            return false;
        }
        if (v.isTextual()) {
            return !v.asText().isEmpty();
        }
        if (v.isNumber()) {
            double d = v.asDouble();
            return d == d && d != 0.0d; // NaN 判定对齐
        }
        return true; // 对象/数组恒真
    }

    /** 对齐 JSON.stringify(ev.usage)：缺失 → "undefined"，其余紧凑序列化（键序保持插入序）。 */
    private static String compactJsonOrUndefined(JsonNode node) throws IOException {
        if (node.isMissingNode()) {
            return "undefined";
        }
        return MAPPER.writeValueAsString(node);
    }

    private CliProbe() {
    }
}

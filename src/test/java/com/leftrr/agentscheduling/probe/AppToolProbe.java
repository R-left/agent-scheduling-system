package com.leftrr.agentscheduling.probe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.leftrr.agentscheduling.cli.CliAdapter;
import com.leftrr.agentscheduling.cli.CliAdapterRegistry;
import com.leftrr.agentscheduling.cli.CliEvent;
import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.cli.CliRunner;
import com.leftrr.agentscheduling.cli.CliRunResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用工具链路探针：真实拉起 claude/codex CLI（由 adapter 启动参数挂载本进程 MCP server），
 * 验证「工具调用 → tool_call 事件回传」端到端链路（M5 备案①，M7 裁决 13）。
 * 逐字移植 TS src/probe-app-tool.ts。
 *
 * <p>需要本机装有真实 claude/codex CLI（走真实模型调用：探针失败 ≠ 移植错误，验收口径是
 * 「工具调用回传即算链路通」）。绝不从单测调用——真实 CLI 路径只在 {@link #main}。
 *
 * <p>运行（对齐 LarkProbe 先例，test classpath）：
 * <pre>
 *   mvn test-compile exec:java -Dexec.mainClass=com.leftrr.agentscheduling.probe.AppToolProbe -Dexec.classpathScope=test -Dexec.args="claude &lt;workspace&gt;"
 *   mvn test-compile exec:java -Dexec.mainClass=com.leftrr.agentscheduling.probe.AppToolProbe -Dexec.classpathScope=test -Dexec.args="codex &lt;workspace&gt;"
 * </pre>
 * argv[1] = cliId（claude|codex，TS process.argv[2]）；argv[2] = workspace（缺省 CWD，TS process.argv[3] ?? cwd）。
 *
 * <p>警示（终审第七节 F2 收口备案）：上面 mvn exec:java 形态下 agent_os MCP 挂载必然
 * 静默失败——{@code AppTools.claudeAppToolArgs()} 无条件把 {@code java.class.path}
 * 嵌入子 JVM 的 -cp，而 exec:java 同 JVM 运行时该属性仅 plexus-classworlds jar，子
 * JVM 启动 AppToolsServer 即 ClassNotFoundException。本探针正是 agent_os 工具链路的
 * 真实链路验收载体（M5 备案①、Task 6 链路验收），必须用打包 java -cp 形态运行，
 * 否则静默失败会被误判为模型不调工具（R9）。</p>
 *
 * <p>中文 Windows 下管道/重定向捕获本探针输出时默认 GBK（Task 6 实证，对齐错题本 M4
 * 惯例），建议以 {@code MAVEN_OPTS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"}
 * 前缀运行，保证管道侧读到 UTF-8。</p>
 *
 * <p>探针输出即产品（对标 TS console.log/console.error），沿用 System.out/err 而非 SLF4J
 * （M7 裁决 2 的「TS console 对等」口径；SLF4J pattern 会破坏逐字文案）。
 */
public final class AppToolProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对齐 TS runCli 的固定四行中文 prompt。 */
    static final String PROMPT = String.join("\n",
            "我们准备给任务列表增加优先级功能。",
            "请调用 request_clarification，询问一个会实质影响实现范围的问题。",
            "提供 2 到 4 个清晰选项，并标记推荐项。",
            "调用工具后，用一句话结束。");

    public static void main(String[] args) throws Exception {
        String cliIdArg = args.length > 0 ? args[0] : null;     // TS process.argv[2]
        String workspaceArg = args.length > 1 ? args[1] : null; // TS process.argv[3] ?? process.cwd()
        CliId cliId = parseCliId(cliIdArg, System.err);
        if (cliId == null) {
            System.exit(1); // 对齐 TS process.exit(1)
            return;
        }

        CliAdapter adapter = CliAdapterRegistry.get(cliId);
        CliRunResult result = CliRunner.run(new CliRunner.Options(
                adapter, PROMPT, resolveWorkspace(workspaceArg), null, null, null,
                event -> printToolCall(event, System.out)));

        line(System.out, completionLine(adapter, result));
    }

    /**
     * 对齐 TS `cliId !== 'claude' && cliId !== 'codex'` 校验：非法（含缺失）时向 stderr 打印
     * 用法文案并返回 null（main 侧 exit 1）；合法返回 CliId。
     */
    static CliId parseCliId(String value, PrintStream err) {
        Optional<CliId> parsed = CliId.fromId(value);
        if (parsed.isEmpty()) {
            line(err, "用法：pnpm probe:tool <claude|codex>");
            return null;
        }
        return parsed.get();
    }

    /** 对齐 TS `resolve(workspaceArg ?? process.cwd())`：相对路径按 CWD 解析并规范化。 */
    static String resolveWorkspace(String workspaceArg) {
        String raw = workspaceArg != null ? workspaceArg : System.getProperty("user.dir");
        return Path.of(raw).toAbsolutePath().normalize().toString();
    }

    /** 对齐 TS onEvent 过滤：只打印 tool_call 事件（`event.type !== 'tool_call'` 即跳过）。 */
    static void printToolCall(CliEvent event, PrintStream out) {
        if (!(event instanceof CliEvent.ToolCallEvent call)) {
            return;
        }
        line(out, "");
        line(out, "[应用工具] " + call.toolName());
        line(out, jsonStringify(call.input()));
    }

    /** console.log/console.error 等价：恒以 "\n" 收尾（不用 println，避免 Windows 换行成 \r\n 偏离 TS 输出）。 */
    private static void line(PrintStream out, String s) {
        out.print(s + "\n");
    }

    /**
     * 对齐 TS 收尾判定：`!result.toolCalls?.length` → throw
     * `${displayName} 没有调用 request_clarification`；否则返回
     * `\n[完成] ${displayName} 返回 ${n} 次应用工具调用`。
     */
    static String completionLine(CliAdapter adapter, CliRunResult result) {
        List<CliRunResult.ToolCall> toolCalls = result.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalStateException(adapter.displayName() + " 没有调用 request_clarification");
        }
        return "\n[完成] " + adapter.displayName() + " 返回 " + toolCalls.size() + " 次应用工具调用";
    }

    /**
     * 对齐 JSON.stringify(value, null, 2)：对象/数组 2 空格缩进、`"key": value`（冒号后单空格）、
     * 空容器紧凑为 `{}` / `[]`、非 ASCII 保持原样、键序保持插入序。
     * 不用 Jackson 默认 pretty printer（其为 `{ }` 与 `"key" : value`，与 JSON.stringify 不逐字）。
     */
    static String jsonStringify(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, node, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, JsonNode node, int depth) {
        if (node.isMissingNode()) {
            sb.append("undefined"); // JSON.stringify(undefined) 的形态（输入侧经 M2 兜底通常已是 NullNode）
            return;
        }
        if (node.isObject()) {
            if (node.isEmpty()) {
                sb.append("{}");
                return;
            }
            writeObjectFields(sb, node.fields(), depth);
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                sb.append("[]");
                return;
            }
            writeArrayElements(sb, node.elements(), depth);
        } else {
            sb.append(compact(node));
        }
    }

    private static void writeArrayElements(StringBuilder sb, Iterator<JsonNode> children, int depth) {
        String pad = "  ".repeat(depth + 1);
        String closePad = "  ".repeat(depth);
        sb.append('[').append('\n');
        boolean first = true;
        while (children.hasNext()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append(pad);
            writeValue(sb, children.next(), depth + 1);
        }
        sb.append('\n').append(closePad).append(']');
    }

    /** 对象形态：`"key": value`（键按 JSON 字符串转义，对齐 JSON.stringify 对象键）。 */
    private static void writeObjectFields(StringBuilder sb, Iterator<Map.Entry<String, JsonNode>> fields, int depth) {
        String pad = "  ".repeat(depth + 1);
        String closePad = "  ".repeat(depth);
        sb.append('{').append('\n');
        boolean first = true;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append(pad).append(compact(TextNode.valueOf(field.getKey()))).append(": ");
            writeValue(sb, field.getValue(), depth + 1);
        }
        sb.append('\n').append(closePad).append('}');
    }

    /** 标量（字符串/数字/布尔/null）紧凑序列化，转义规则与 JSON.stringify 一致。 */
    private static String compact(JsonNode scalar) {
        try {
            return MAPPER.writeValueAsString(scalar);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("探针序列化工具入参失败", e);
        }
    }

    private AppToolProbe() {
    }
}

package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Codex 适配器，对齐 TS codex-adapter.ts。 */
public final class CodexAdapter implements CliAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 同 ClaudeAdapter：UNICODE_CHARACTER_CLASS 对齐 JS \s
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final BooleanSupplier windows;

    public CodexAdapter() {
        this(CliProcess::isWindows);
    }

    /** 包级构造器注入平台判定，测试两个分支。 */
    CodexAdapter(BooleanSupplier windows) {
        this.windows = windows;
    }

    @Override public CliId id() { return CliId.CODEX; }
    @Override public String command() { return "codex"; }
    @Override public String displayName() { return "Codex"; }

    @Override
    public List<String> buildArgs(String prompt, CliPromptInput promptInput) {
        List<String> args = baseArgs(false, null);
        args.add(promptInput == CliPromptInput.STDIN ? "-" : prompt);
        return args;
    }

    @Override
    public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
        List<String> args = baseArgs(true, sessionId);
        args.add(promptInput == CliPromptInput.STDIN ? "-" : prompt);
        return args;
    }

    private List<String> baseArgs(boolean resume, String sessionId) {
        List<String> args = new ArrayList<>(AppTools.codexAppToolArgs());
        args.add("exec");
        if (resume) {
            args.add("resume");
        }
        args.add("--json");
        args.add("--skip-git-repo-check");
        if (resume) {
            args.add(sessionId);
        }
        // Windows 上沙箱功能不支持，必须完全禁用；approvals 也一并绕过。
        args.add(windows.getAsBoolean()
                ? "--dangerously-bypass-approvals-and-sandbox"
                : "--yolo");
        return args;
    }

    @Override
    public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
        // 对齐 TS：Codex 的 compact 忽略 instructions，走 app-server JSON-RPC
        return new CliCompactPlan.CodexAppServer(command(), List.of("app-server", "--stdio"), sessionId);
    }

    @Override
    public List<CliEvent> parseEvents(String line) {
        JsonNode event;
        try {
            event = MAPPER.readTree(line);
        } catch (Exception e) {
            return List.of();
        }

        String type = text(event, "type");
        if ("thread.started".equals(type)) {
            String threadId = text(event, "thread_id");
            return threadId != null
                    ? List.of(new CliEvent.SessionEvent(threadId))
                    : List.of();
        }
        if ("error".equals(type) || "turn.failed".equals(type)) {
            return List.of(new CliEvent.ErrorEvent(errorMessage(event), null));
        }
        if ("turn.completed".equals(type)) {
            CliRunStats stats = parseStats(event.path("usage"));
            return stats != null ? List.of(new CliEvent.StatsEvent(stats)) : List.of();
        }
        JsonNode item = event.path("item");
        if (!item.isObject()) {
            return List.of();
        }
        if ("item.completed".equals(type) && "agent_message".equals(text(item, "type"))
                && text(item, "text") != null) {
            return List.of(new CliEvent.ResultEvent(text(item, "text"), null, null));
        }
        String id = text(item, "id");
        if (id == null) {
            return List.of();
        }
        ToolInfo tool = toolInfo(item);
        if (tool == null) {
            return List.of();
        }
        if ("item.started".equals(type)) {
            List<CliEvent> events = new ArrayList<>();
            events.add(new CliEvent.ToolStartEvent(id, tool.toolName(), tool.label(), tool.detail()));
            if ("mcp_tool_call".equals(text(item, "type"))
                    && "agent_os".equals(text(item, "server"))
                    && (AppTools.CLARIFICATION_TOOL_NAME.equals(text(item, "tool"))
                        || AppTools.PRODUCT_SPEC_TOOL_NAME.equals(text(item, "tool"))
                        || AppTools.DISPATCH_TASK_TOOL_NAME.equals(text(item, "tool")))) {
                JsonNode input = item.path("arguments");
                if (input.isMissingNode() || input.isNull()) {
                    input = item.path("input"); // 对齐 `arguments ?? input`
                }
                if (input.isMissingNode()) {
                    // arguments 与 input 皆缺：TS undefined 经 JSON 序列化为 null 的形态，M6 消费侧用 isNull() 判空
                    input = NullNode.getInstance();
                }
                events.add(new CliEvent.ToolCallEvent(id, text(item, "tool"), input));
            }
            return events;
        }
        if ("item.completed".equals(type)) {
            Long exitCode = longOrNull(item, "exit_code");
            boolean failed = "failed".equals(text(item, "status"))
                    || (exitCode != null && exitCode != 0);
            return List.of(new CliEvent.ToolEndEvent(id, failed));
        }
        return List.of();
    }

    private record ToolInfo(String toolName, String label, String detail) {}

    private static ToolInfo toolInfo(JsonNode item) {
        // type 缺失时 text() 返回 null，switch 直接作用于 null 会 NPE（TS undefined 走 default 分支），先兜底
        String itemType = text(item, "type");
        if (itemType == null) {
            return null;
        }
        // brief 原文 shortText(item.path(...)) 传 JsonNode 无法通过编译，先经 text() 取 String（非文本 → null，语义不变）
        return switch (itemType) {
            case "command_execution" -> new ToolInfo("Bash", "运行命令", shortText(text(item, "command")));
            case "file_change" -> new ToolInfo("Edit", "修改文件", firstChangedPath(item));
            case "web_search" -> new ToolInfo("WebSearch", "搜索资料", shortText(text(item, "query")));
            case "mcp_tool_call" -> new ToolInfo("MCP", "调用外部工具",
                    shortText(joinDotted(text(item, "server"), text(item, "tool"))));
            default -> null;
        };
    }

    /** 对齐 [server, tool].filter(Boolean).join('.')：空串过滤。 */
    private static String joinDotted(String server, String tool) {
        StringBuilder sb = new StringBuilder();
        if (server != null && !server.isEmpty()) {
            sb.append(server);
        }
        if (tool != null && !tool.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(tool);
        }
        return sb.toString();
    }

    /** shortText 对 String 的重载（firstChangedPath/joinDotted 无 JsonNode 载体时用）。 */
    private static String shortText(String value) {
        if (value == null) {
            return null;
        }
        String text = JsText.jsTrim(WHITESPACE.matcher(value).replaceAll(" "));
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 72 ? text.substring(0, 71) + "…" : text;
    }

    /** 对齐 firstChangedPath()：item.path 直给优先，否则取第一个对象 change 的 path。 */
    private static String firstChangedPath(JsonNode item) {
        if (item.path("path").isTextual()) {
            return shortText(item.path("path").asText());
        }
        JsonNode changes = item.path("changes");
        if (!changes.isArray()) {
            return null;
        }
        for (JsonNode change : changes) {
            if (change.isObject()) {
                return shortText(change.path("path").isTextual()
                        ? change.path("path").asText() : null);
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static CliRunStats parseStats(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        Long input = longOrNull(usage, "input_tokens");
        Long output = longOrNull(usage, "output_tokens");
        Long cacheRead = longOrNull(usage, "cached_input_tokens");
        Long total = input == null && output == null
                ? null
                : (input == null ? 0 : input) + (output == null ? 0 : output);
        // CliRunStats 共 9 个字段（brief 代码漏写末位 contextWindowTokens 的 null，补齐以通过编译）
        CliRunStats stats = new CliRunStats(null, null, total, input, output, cacheRead, null, null, null);
        return stats.isEmpty() ? null : stats;
    }

    private static String errorMessage(JsonNode event) {
        if (event.path("message").isTextual()) {
            return event.path("message").asText();
        }
        JsonNode error = event.path("error");
        if (error.isObject() && error.path("message").isTextual()) {
            return error.path("message").asText();
        }
        return "Codex 执行失败";
    }
}

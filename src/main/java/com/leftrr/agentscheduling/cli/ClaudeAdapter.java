package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Claude Code 适配器，对齐 TS claude-adapter.ts。 */
public final class ClaudeAdapter implements CliAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // JS /\s+/g：UNICODE_CHARACTER_CLASS 对齐 NBSP 等空白（残余差异：﻿，注释见计划 Review Focus 1）
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Map<String, String> TOOL_LABELS = Map.ofEntries(
            Map.entry("Agent", "启动子任务"),
            Map.entry("Bash", "运行命令"),
            Map.entry("Edit", "修改文件"),
            Map.entry("Glob", "查找文件"),
            Map.entry("Grep", "搜索代码"),
            Map.entry("Read", "读取文件"),
            Map.entry("Task", "启动子任务"),
            Map.entry("TaskOutput", "等待子任务完成"),
            Map.entry("WebFetch", "读取网页"),
            Map.entry("WebSearch", "搜索资料"),
            Map.entry("Write", "写入文件"));

    @Override public CliId id() { return CliId.CLAUDE; }
    @Override public String command() { return "claude"; }
    @Override public String displayName() { return "Claude Code"; }

    @Override
    public List<String> buildArgs(String prompt, CliPromptInput promptInput) {
        return outputArgs(prompt, promptInput);
    }

    @Override
    public List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput) {
        List<String> args = new ArrayList<>();
        args.add("--resume");
        args.add(sessionId);
        args.addAll(outputArgs(prompt, promptInput));
        return args;
    }

    @Override
    public CliCompactPlan buildCompactPlan(String sessionId, String instructions) {
        String trimmed = instructions == null ? "" : JsText.jsTrim(instructions);
        String command = trimmed.isEmpty() ? "/compact" : "/compact " + trimmed;
        // 对齐 TS `command: this.command`（Task 8 CliProcess.spawn(plan.command(), ...) 消费可执行名）
        // prompt 走 stdin（-p 之后无参数），runClaudeCompact 需要这份文本写入子进程
        return new CliCompactPlan.ClaudeStreamJson(
                command(), buildResumeArgs(command, sessionId, CliPromptInput.current()), command);
    }

    private static List<String> outputArgs(String prompt, CliPromptInput promptInput) {
        List<String> args = new ArrayList<>();
        args.add("--dangerously-skip-permissions");
        args.add("-p");
        if (promptInput == CliPromptInput.ARGUMENT) {
            args.add(prompt);
        }
        args.add("--output-format");
        args.add("stream-json");
        args.add("--verbose");
        args.addAll(AppTools.claudeAppToolArgs());
        return args;
    }

    @Override
    public List<CliEvent> parseEvents(String line) {
        JsonNode event;
        try {
            event = MAPPER.readTree(line);
        } catch (Exception e) {
            return List.of();
        }

        String sessionId = text(event, "session_id");
        if ("system".equals(text(event, "type")) && "init".equals(text(event, "subtype"))
                && sessionId != null) {
            return List.of(new CliEvent.SessionEvent(sessionId));
        }
        if ("assistant".equals(text(event, "type"))) {
            JsonNode message = event.path("message");
            Long usedTokens = usageTokens(message.path("usage"));
            List<CliEvent> events = new ArrayList<>();
            if (usedTokens != null) {
                events.add(new CliEvent.ContextEvent(usedTokens));
            }
            for (JsonNode block : values(message.path("content"))) {
                if (!block.isObject() || !"tool_use".equals(text(block, "type"))) {
                    continue;
                }
                String id = text(block, "id");
                String name = text(block, "name");
                if (id == null || name == null) {
                    continue;
                }
                JsonNode input = block.path("input");
                events.add(new CliEvent.ToolStartEvent(id, name,
                        TOOL_LABELS.getOrDefault(name, "调用 " + name),
                        toolDetail(name, input)));
                if (AppTools.CLAUDE_CLARIFICATION_TOOL_NAME.equals(name)) {
                    events.add(new CliEvent.ToolCallEvent(id, AppTools.CLARIFICATION_TOOL_NAME, input));
                }
                if (AppTools.CLAUDE_PRODUCT_SPEC_TOOL_NAME.equals(name)) {
                    events.add(new CliEvent.ToolCallEvent(id, AppTools.PRODUCT_SPEC_TOOL_NAME, input));
                }
                if (AppTools.CLAUDE_DISPATCH_TASK_TOOL_NAME.equals(name)) {
                    events.add(new CliEvent.ToolCallEvent(id, AppTools.DISPATCH_TASK_TOOL_NAME, input));
                }
            }
            return events;
        }
        if ("user".equals(text(event, "type"))) {
            List<CliEvent> events = new ArrayList<>();
            for (JsonNode block : values(event.path("message").path("content"))) {
                if (!block.isObject() || !"tool_result".equals(text(block, "type"))) {
                    continue;
                }
                String toolUseId = text(block, "tool_use_id");
                if (toolUseId == null) {
                    continue;
                }
                events.add(new CliEvent.ToolEndEvent(toolUseId, block.path("is_error").asBoolean(false)));
            }
            return events;
        }
        if (!"result".equals(text(event, "type"))) {
            return List.of();
        }
        if (event.path("is_error").asBoolean(false)) {
            JsonNode result = event.path("result");
            return List.of(new CliEvent.ErrorEvent(
                    result.isTextual() ? result.asText() : "Claude Code 执行失败", sessionId));
        }
        JsonNode result = event.path("result");
        if (!result.isTextual()) {
            return List.of();
        }
        return List.of(new CliEvent.ResultEvent(result.asText(), sessionId, parseStats(event)));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null; // JSON 数字均有限，等价 JS Number.isFinite 判定
    }

    private static Integer intOrNull(JsonNode node, String field) {
        Long value = longOrNull(node, field);
        return value == null ? null : value.intValue();
    }

    /** 对齐 usageTokens()：只累加存在的数值字段，全缺失返回 null。 */
    private static Long usageTokens(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        long sum = 0;
        boolean any = false;
        for (String field : new String[] {
                "input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"}) {
            Long value = longOrNull(usage, field);
            if (value != null) {
                sum += value;
                any = true;
            }
        }
        return any ? sum : null;
    }

    /** 对齐 contextWindowTokens()：Object.values → 取 >0 的最大 contextWindow，无合法值 null。 */
    private static Long contextWindowTokens(JsonNode modelUsage) {
        if (!modelUsage.isObject()) {
            return null;
        }
        long max = Long.MIN_VALUE;
        for (JsonNode usage : modelUsage) {
            if (!usage.isObject()) {
                continue;
            }
            JsonNode window = usage.path("contextWindow");
            if (window.isNumber() && window.asLong() > 0) {
                max = Math.max(max, window.asLong());
            }
        }
        return max == Long.MIN_VALUE ? null : max;
    }

    private static CliRunStats parseStats(JsonNode event) {
        JsonNode usage = event.path("usage").isObject() ? event.path("usage") : MAPPER.createObjectNode();
        CliRunStats stats = new CliRunStats(
                intOrNull(event, "duration_ms"),
                intOrNull(event, "num_turns"),
                usageTokens(usage),
                longOrNull(usage, "input_tokens"),
                longOrNull(usage, "output_tokens"),
                longOrNull(usage, "cache_read_input_tokens"),
                longOrNull(usage, "cache_creation_input_tokens"),
                null, // contextUsedTokens：TS parseStats 从不设置该字段（由 TaskProgress 聚合 context 事件填充）
                contextWindowTokens(event.path("modelUsage")));
        return stats.isEmpty() ? null : stats;
    }

    /** 对齐 shortPath()：normalize 反斜杠 → 取末 2 段（绝对）或末 3 段（相对），元素不足从头取。 */
    private static String shortPath(JsonNode value) {
        if (!value.isTextual()) {
            return null;
        }
        String v = value.asText();
        if (v.isEmpty()) {
            return null;
        }
        String normalized = v.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        int from = normalized.startsWith("/")
                ? Math.max(0, parts.size() - 2)
                : Math.max(0, parts.size() - 3);
        return String.join("/", parts.subList(from, parts.size()));
    }

    /** 对齐 shortText()：\s+ 折叠 → jsTrim → 超长截 71 字符加 …。 */
    private static String shortText(JsonNode value, int maxLength) {
        if (!value.isTextual()) {
            return null;
        }
        String text = JsText.jsTrim(WHITESPACE.matcher(value.asText()).replaceAll(" "));
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text;
    }

    private static String toolDetail(String name, JsonNode input) {
        if (!input.isObject()) {
            return null;
        }
        return switch (name) {
            case "Read", "Edit", "Write" -> shortPath(input.path("file_path"));
            case "Glob", "Grep" -> shortText(input.path("pattern"), 72);
            case "Bash", "Agent", "Task" -> shortText(input.path("description"), 72);
            case "WebSearch" -> shortText(input.path("query"), 72);
            default -> null;
        };
    }

    /** 对齐 messageBlocks()：content 为数组时取其中对象元素，否则空。 */
    private static Iterable<JsonNode> values(JsonNode node) {
        return node.isArray() ? node : Collections.emptyList();
    }
}

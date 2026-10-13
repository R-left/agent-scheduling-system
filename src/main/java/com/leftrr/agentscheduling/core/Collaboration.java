package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.JsText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * bot 间协作（dispatch_task）。对应 TS 版 src/core/collaboration.ts。
 * zod schema 用 JsonNode 手工校验表达（设计裁决 1）：safeParse → 返回 null 不抛。
 */
public final class Collaboration {
    public static final String DISPATCH_TASK_TOOL_NAME = "dispatch_task";
    private static final Pattern TARGET_BOT_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    private Collaboration() {}

    /** 对齐 DispatchTaskRequest（z.infer）；expectedOutput null 表示可选缺省。 */
    public record DispatchTaskRequest(
            String targetBotId,
            String objective,
            String instruction,
            String expectedOutput) {}

    /** 对齐 CollaborationMessage 接口；ownerUnionId/expectedOutput null 表示 undefined。 */
    public record CollaborationMessage(
            String dispatchId,
            String taskId,
            String ownerOpenId,
            String ownerUnionId,
            String fromBotId,
            String toBotId,
            String reportToBotId,
            String objective,
            String instruction,
            String expectedOutput,
            int round,
            int maxRounds,
            String workspaceDir) {}

    /** 对齐 CollaborationOrigin 接口。 */
    public record CollaborationOrigin(
            String taskId,
            String fromBotId,
            String reportToBotId,
            int round,
            int maxRounds) {}

    /** 对齐 DispatchTaskRequestSchema.safeParse：非法返回 null。 */
    public static DispatchTaskRequest parseDispatchTaskRequest(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        JsonNode targetNode = input.get("targetBotId");
        if (targetNode == null || !targetNode.isTextual()
                || !TARGET_BOT_ID.matcher(targetNode.asText()).matches()) {
            return null;
        }
        String objective = trimmedText(input.get("objective"));
        if (objective == null || objective.isEmpty() || objective.length() > 200) {
            return null;
        }
        String instruction = trimmedText(input.get("instruction"));
        if (instruction == null || instruction.isEmpty() || instruction.length() > 2000) {
            return null;
        }
        String expectedOutput = null;
        JsonNode expectedNode = input.get("expectedOutput");
        if (expectedNode != null) { // zod .optional() 拒绝显式 null：键存在即必须合法
            expectedOutput = trimmedText(expectedNode);
            if (expectedOutput == null || expectedOutput.isEmpty() || expectedOutput.length() > 500) {
                return null;
            }
        }
        return new DispatchTaskRequest(targetNode.asText(), objective, instruction, expectedOutput);
    }

    /** 对齐 findDispatchTaskRequest()：从后向前、safeParse 失败继续向前、命中即返。 */
    public static DispatchTaskRequest findDispatchTaskRequest(List<CliRunResult.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return null;
        }
        for (int index = toolCalls.size() - 1; index >= 0; index--) {
            CliRunResult.ToolCall call = toolCalls.get(index);
            if (call == null || !DISPATCH_TASK_TOOL_NAME.equals(call.toolName())) {
                continue;
            }
            DispatchTaskRequest parsed = parseDispatchTaskRequest(call.input());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    public static CollaborationOrigin collaborationOrigin(CollaborationMessage message) {
        return new CollaborationOrigin(message.taskId(), message.fromBotId(),
                message.reportToBotId(), message.round(), message.maxRounds());
    }

    public static String buildCollaborationPrompt(CollaborationMessage message) {
        List<String> sections = new ArrayList<>();
        sections.add("协作目标：" + message.objective());
        sections.add("执行要求：" + message.instruction());
        if (message.expectedOutput() != null && !message.expectedOutput().isEmpty()) {
            sections.add("期望产出：" + message.expectedOutput());
        }
        sections.add("完成后，把结果交回 " + message.reportToBotId()
                + " 继续组织后续工作；已经可以交付时，明确给出最终结论。");
        return String.join("\n\n", sections);
    }

    public static String collaborationTurnKey(CollaborationMessage message) {
        return message.taskId() + ":" + message.round() + ":" + message.toBotId();
    }

    /** z.string().trim()：JS trim 语义 + 非文本判失败。 */
    private static String trimmedText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return JsText.jsTrim(node.asText());
    }

    /**
     * 内存收件箱（TS CollaborationInbox；重启即失，roadmap「运行时状态」口径）。
     *
     * <p>并发形态（M7 Task 5 累积裁决 a，Task 2 审查 Minor ㉇）：TS 单事件循环下
     * register（dispatch 线程）与 consume（消息线程）天然串行；Java 两侧在不同虚拟线程，
     * 裸 LinkedHashMap 跨线程竞争。register/consume 均收 {@code synchronized}——consume 的
     * get+check+remove 是复合操作，ConcurrentHashMap 只能保证单键原子性、无法表达
     * 「校验通过即取走」的整段语义（两个并发 consume 可能都读到非 null 而双取走），
     * 方法级互斥才与 TS 同同步段原子性逐点对齐。方法体内无阻塞调用，锁竞争面仅
     * 协作派发/回传路径，无死锁风险。LinkedHashMap 保持原字段不动。</p>
     */
    public static final class CollaborationInbox {
        private final Map<String, CollaborationMessage> messages = new LinkedHashMap<>();

        public synchronized void register(CollaborationMessage message) {
            messages.put(message.dispatchId(), message);
        }

        /** dispatchId 不存在或 toBotId 不匹配返回 null 且不删除（对齐 TS）。 */
        public synchronized CollaborationMessage consume(String dispatchId, String toBotId) {
            CollaborationMessage message = messages.get(dispatchId);
            if (message == null || !message.toBotId().equals(toBotId)) {
                return null;
            }
            messages.remove(dispatchId);
            return message;
        }
    }
}

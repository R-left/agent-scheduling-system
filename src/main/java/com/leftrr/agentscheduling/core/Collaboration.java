package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.dto.cli.CliRunResult;
import com.leftrr.agentscheduling.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * bot 间协作（dispatch_task）。
 * 请求用 JsonNode 手工校验：非法返回 null 不抛。
 */
public final class Collaboration {
    public static final String DISPATCH_TASK_TOOL_NAME = "dispatch_task";
    private static final Pattern TARGET_BOT_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    private Collaboration() {}

    /** 协作派发请求；expectedOutput null 表示可选缺省。 */
    public record DispatchTaskRequest(
            String targetBotId,
            String objective,
            String instruction,
            String expectedOutput) {}

    /** 协作消息；ownerUnionId/expectedOutput null 表示缺省。 */
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

    /** 协作来源信息（taskId / 发起 bot / 汇报 bot / 轮次）。 */
    public record CollaborationOrigin(
            String taskId,
            String fromBotId,
            String reportToBotId,
            int round,
            int maxRounds) {}

    /** 解析 dispatch_task 请求入参：非法返回 null。 */
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
        if (expectedNode != null) { // 可选字段拒绝显式 null：键存在即必须合法
            expectedOutput = trimmedText(expectedNode);
            if (expectedOutput == null || expectedOutput.isEmpty() || expectedOutput.length() > 500) {
                return null;
            }
        }
        return new DispatchTaskRequest(targetNode.asText(), objective, instruction, expectedOutput);
    }

    /** 从后向前扫描工具调用：解析失败继续向前、命中即返。 */
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

    /** 按完整 Unicode 空白全集 trim；非文本节点判失败。 */
    private static String trimmedText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return StringUtil.trim(node.asText());
    }

    /**
     * 内存收件箱（重启即失）。
     * register 与 consume 在不同虚拟线程上跑，而 consume 是 get+check+remove 复合操作，
     * 换 ConcurrentHashMap 也只保单键原子，两个并发 consume 会都读到非空双取走，
     * 所以方法级 synchronized 整段互斥。锁内无阻塞调用，无死锁风险。
     */
    public static final class CollaborationInbox {
        private final Map<String, CollaborationMessage> messages = new LinkedHashMap<>();

        public synchronized void register(CollaborationMessage message) {
            messages.put(message.dispatchId(), message);
        }

        /** dispatchId 不存在或 toBotId 不匹配返回 null 且不删除。 */
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

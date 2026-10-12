package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliRunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 6：Collaboration 测试（对齐 TS 版 src/core/collaboration.ts 语义）。
 * ToolCall input 用 Jackson readTree 构造。
 */
class CollaborationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 合法 dispatch_task input 模板，可覆盖字段。 */
    private static JsonNode validInput() {
        return json("""
                {"targetBotId":"writer","objective":"写周报","instruction":"按模板写","expectedOutput":"md 文件"}
                """);
    }

    private static CliRunResult.ToolCall call(String toolName, JsonNode input) {
        return new CliRunResult.ToolCall("tool-1", toolName, input);
    }

    // 1. 两个 dispatch_task 调用均合法 → 返回靠后者（从后向前）
    @Test
    void findReturnsLaterOfTwoValidCalls() {
        JsonNode first = json("""
                {"targetBotId":"a","objective":"第一","instruction":"做一"}
                """);
        JsonNode second = json("""
                {"targetBotId":"b","objective":"第二","instruction":"做二"}
                """);
        var result = Collaboration.findDispatchTaskRequest(List.of(
                call("dispatch_task", first), call("other_tool", json("{}")),
                call("dispatch_task", second)));
        assertEquals("b", result.targetBotId());
        assertEquals("第二", result.objective());
        assertEquals("做二", result.instruction());
        assertNull(result.expectedOutput());
    }

    // 2. 靠后的非法、靠前的合法 → 返回靠前者（safeParse 失败继续向前）
    @Test
    void findSkipsInvalidLaterCallAndReturnsEarlierValid() {
        JsonNode earlier = validInput();
        JsonNode laterInvalid = json("""
                {"targetBotId":"Writer-1","objective":"非法目标","instruction":"做"}
                """);
        var result = Collaboration.findDispatchTaskRequest(List.of(
                call("dispatch_task", earlier), call("dispatch_task", laterInvalid)));
        assertEquals("writer", result.targetBotId());
    }

    // 3a. 全部非法 → null
    @Test
    void allInvalidReturnsNull() {
        assertNull(Collaboration.findDispatchTaskRequest(List.of(
                call("dispatch_task", json("{}")),
                call("dispatch_task", json("{\"targetBotId\":\"x\"}")))));
    }

    // 3b. 列表无 dispatch_task → null
    @Test
    void noDispatchTaskCallReturnsNull() {
        assertNull(Collaboration.findDispatchTaskRequest(List.of(
                call("other_tool", validInput()))));
    }

    // 3c. null 列表 → null
    @Test
    void nullListReturnsNull() {
        assertNull(Collaboration.findDispatchTaskRequest(null));
    }

    // 4. targetBotId 正则：大写、连字符开头 → null；w1_2-x → 通过
    @Test
    void targetBotIdRegex() {
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"Writer-1\",\"objective\":\"o\",\"instruction\":\"i\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"-writer\",\"objective\":\"o\",\"instruction\":\"i\"}")));
        var ok = Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w1_2-x\",\"objective\":\"o\",\"instruction\":\"i\"}"));
        assertEquals("w1_2-x", ok.targetBotId());
    }

    // 5. objective：trim 后 1 字符通过且值为 trim 后文本；201 字符 null；全空白 null；恰 200 字符通过
    @Test
    void objectiveTrimAndLength() {
        var one = Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"  x \",\"instruction\":\"i\"}"));
        assertEquals("x", one.objective());
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"" + "a".repeat(201) + "\",\"instruction\":\"i\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"   \",\"instruction\":\"i\"}")));
        var exact = Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"" + "a".repeat(200) + "\",\"instruction\":\"i\"}"));
        assertEquals(200, exact.objective().length());
    }

    // 6. instruction：恰 2000 字符通过；2001 null；全空白 null
    @Test
    void instructionTrimAndLength() {
        var exact = Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"" + "a".repeat(2000) + "\"}"));
        assertEquals(2000, exact.instruction().length());
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"" + "a".repeat(2001) + "\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"  \"}")));
    }

    // 7. expectedOutput：缺省 → null；合法值 trim 保留；501 null；空串 null；显式 JSON null → null
    @Test
    void expectedOutputRules() {
        var absent = Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"i\"}"));
        assertNull(absent.expectedOutput());
        var trimmed = Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"i\",\"expectedOutput\":\"  out  \"}"));
        assertEquals("out", trimmed.expectedOutput());
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"i\",\"expectedOutput\":\""
                        + "a".repeat(501) + "\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"i\",\"expectedOutput\":\"\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json(
                "{\"targetBotId\":\"w\",\"objective\":\"o\",\"instruction\":\"i\",\"expectedOutput\":null}")));
    }

    // 8. 缺任一必填键 → null；input 非对象（数组、字符串）→ null
    @Test
    void missingKeysAndNonObjectInput() {
        assertNull(Collaboration.parseDispatchTaskRequest(json("{\"objective\":\"o\",\"instruction\":\"i\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json("{\"targetBotId\":\"w\",\"instruction\":\"i\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json("{\"targetBotId\":\"w\",\"objective\":\"o\"}")));
        assertNull(Collaboration.parseDispatchTaskRequest(json("[1,2]")));
        assertNull(Collaboration.parseDispatchTaskRequest(json("\"text\"")));
    }

    // 9. collaborationTurnKey → "task-1:3:writer"
    @Test
    void turnKeyFormat() {
        var message = new Collaboration.CollaborationMessage(
                "d1", "task-1", "ou owner", null, "leader", "writer", "leader",
                "obj", "inst", null, 3, 3, "C:/ws");
        assertEquals("task-1:3:writer", Collaboration.collaborationTurnKey(message));
    }

    // 10. buildCollaborationPrompt：带 expectedOutput 四段；不带三段；文案逐字
    @Test
    void promptWithAndWithoutExpectedOutput() {
        var with = new Collaboration.CollaborationMessage(
                "d1", "task-1", "ou", null, "leader", "writer", "leader",
                "整理需求", "逐条列出", "需求清单 md", 1, 3, "C:/ws");
        assertEquals("协作目标：整理需求\n\n执行要求：逐条列出\n\n期望产出：需求清单 md\n\n"
                        + "完成后，把结果交回 leader 继续组织后续工作；已经可以交付时，明确给出最终结论。",
                Collaboration.buildCollaborationPrompt(with));

        var without = new Collaboration.CollaborationMessage(
                "d1", "task-1", "ou", null, "leader", "writer", "leader",
                "整理需求", "逐条列出", null, 1, 3, "C:/ws");
        String prompt = Collaboration.buildCollaborationPrompt(without);
        assertEquals("协作目标：整理需求\n\n执行要求：逐条列出\n\n"
                        + "完成后，把结果交回 leader 继续组织后续工作；已经可以交付时，明确给出最终结论。",
                prompt);
        // filter(Boolean) 语义：expectedOutput 为空串同样剔除
        var empty = new Collaboration.CollaborationMessage(
                "d1", "task-1", "ou", null, "leader", "writer", "leader",
                "整理需求", "逐条列出", "", 1, 3, "C:/ws");
        assertEquals(prompt, Collaboration.buildCollaborationPrompt(empty));
    }

    // 11. collaborationOrigin → 字段逐一相等
    @Test
    void originCopiesFields() {
        var message = new Collaboration.CollaborationMessage(
                "d1", "task-1", "ou", "un", "leader", "writer", "leader",
                "obj", "inst", "out", 2, 5, "C:/ws");
        var origin = Collaboration.collaborationOrigin(message);
        assertEquals("task-1", origin.taskId());
        assertEquals("leader", origin.fromBotId());
        assertEquals("leader", origin.reportToBotId());
        assertEquals(2, origin.round());
        assertEquals(5, origin.maxRounds());
    }

    // 12. CollaborationInbox：consume 正确者取走并删除；不匹配不删除；未知 dispatchId null
    @Test
    void inboxRegisterConsumeAndMismatch() {
        var inbox = new Collaboration.CollaborationInbox();
        var message = new Collaboration.CollaborationMessage(
                "d1", "task-1", "ou", null, "leader", "writer", "leader",
                "obj", "inst", null, 1, 3, "C:/ws");
        inbox.register(message);

        assertNull(inbox.consume("d1", "coder")); // toBotId 不匹配 → null 且不删除
        assertNull(inbox.consume("unknown", "writer")); // 未知 dispatchId → null

        var consumed = inbox.consume("d1", "writer");
        assertEquals(message, consumed);
        assertNull(inbox.consume("d1", "writer")); // 已删除，二次 consume null
    }
}

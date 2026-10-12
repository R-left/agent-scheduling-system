package com.leftrr.agentscheduling.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliEventTest {
    @Test
    void constructsAllEightEventKinds() throws Exception {
        // 冒烟：编译期验证 sealed 许可与 record 构造，并断言字段值
        CliRunStats stats = new CliRunStats(1, 2, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        CliEvent.SessionEvent session = new CliEvent.SessionEvent("s1");
        assertEquals("s1", session.sessionId());

        CliEvent.ToolStartEvent toolStart = new CliEvent.ToolStartEvent("t1", "Bash", "标签", "明细");
        assertEquals("t1", toolStart.toolUseId());
        assertEquals("Bash", toolStart.toolName());
        assertEquals("标签", toolStart.label());
        assertEquals("明细", toolStart.detail());

        CliEvent.ToolEndEvent toolEnd = new CliEvent.ToolEndEvent("t1", true);
        assertEquals("t1", toolEnd.toolUseId());
        assertTrue(toolEnd.failed());

        CliEvent.ContextEvent context = new CliEvent.ContextEvent(1024L);
        assertEquals(1024L, context.usedTokens());

        CliEvent.StatsEvent statsEvent = new CliEvent.StatsEvent(stats);
        assertEquals(stats, statsEvent.stats());

        var mapper = new ObjectMapper();
        var input = mapper.createObjectNode().put("q", "x");
        CliEvent.ToolCallEvent toolCall = new CliEvent.ToolCallEvent("t2", "Search", input);
        assertEquals("t2", toolCall.toolUseId());
        assertEquals("Search", toolCall.toolName());
        assertEquals("x", toolCall.input().get("q").asText());

        CliEvent.ResultEvent result = new CliEvent.ResultEvent("答案", "s1", stats);
        assertEquals("答案", result.answer());
        assertEquals("s1", result.sessionId());
        assertEquals(stats, result.stats());

        CliEvent.ErrorEvent error = new CliEvent.ErrorEvent("出错了", "s1");
        assertEquals("出错了", error.message());
        assertEquals("s1", error.sessionId());
    }
}

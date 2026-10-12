package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.SlashCommand.CdCommand;
import com.leftrr.agentscheduling.core.SlashCommand.CompactCommand;
import com.leftrr.agentscheduling.core.SlashCommand.SimpleCommand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandParserTest {
    @Test
    void parsesSimpleCommands() {
        assertEquals(Optional.of(new SimpleCommand(SimpleCommand.SimpleName.CLOSE)),
                CommandParser.parseCommand("/close"));
        assertEquals(Optional.of(new SimpleCommand(SimpleCommand.SimpleName.TEAM)),
                CommandParser.parseCommand("  /team  "));
        assertEquals(Optional.of(new SimpleCommand(SimpleCommand.SimpleName.STATUS)),
                CommandParser.parseCommand("@Assistant /status"));
    }

    @Test
    void rejectsNonCommands() {
        assertEquals(Optional.empty(), CommandParser.parseCommand("你好"));
        assertEquals(Optional.empty(), CommandParser.parseCommand("/unknown"));
        assertEquals(Optional.empty(), CommandParser.parseCommand("/close extra"));
        assertEquals(Optional.empty(), CommandParser.parseCommand("/compactx"));
    }

    @Test
    void parsesCdWithAndWithoutPath() {
        assertEquals(Optional.of(new CdCommand("D:\\work")),
                CommandParser.parseCommand("/cd D:\\work"));
        var bare = (CdCommand) CommandParser.parseCommand("/cd").orElseThrow();
        assertNull(bare.path());
        var withMention = (CdCommand) CommandParser.parseCommand("@bot /cd  ./ws").orElseThrow();
        assertEquals("./ws", withMention.path());
    }

    @Test
    void parsesCompactWithMultilineInstructions() {
        var compact = (CompactCommand) CommandParser.parseCommand(
                "/compact 重点保留讨论结论\n第二行").orElseThrow();
        assertEquals("重点保留讨论结论\n第二行", compact.instructions());
        var bare = (CompactCommand) CommandParser.parseCommand("/compact").orElseThrow();
        assertNull(bare.instructions());
    }

    @Test
    void parsesCliRequest() {
        assertEquals(Optional.of(new CliRequest(CliId.CLAUDE, "检查项目状态")),
                CommandParser.parseCliRequest("/claude 检查项目状态"));
        assertEquals(Optional.of(new CliRequest(CliId.CODEX, "")),
                CommandParser.parseCliRequest("/codex"));
        assertEquals(Optional.of(new CliRequest(CliId.CODEX, "修复 bug")),
                CommandParser.parseCliRequest("@助手 /codex 修复 bug"));
        // "/claude " 尾部空格 trim 后与 "/claude" 无异：group(2) 为 null → prompt 空串，
        // 与 TS 版 `?? ''` 及 index.ts 的"空 prompt 提示补任务"流程一致。
        // 简报原文此处误写为 Optional.empty（与简报自己的补充说明矛盾），按 TS 实测行为修正。
        assertEquals(Optional.of(new CliRequest(CliId.CLAUDE, "")),
                CommandParser.parseCliRequest("/claude "));
    }

    @Test
    void nbspAfterMentionStillMatches() {
        // JS 的 \s 匹配 NBSP(\u00a0)，Java 需 UNICODE_CHARACTER_CLASS 对齐
        assertEquals(Optional.of(new SimpleCommand(SimpleCommand.SimpleName.HELP)),
                CommandParser.parseCommand("@助手\u00a0/help"));
    }

    @Test
    void trimsNbspAroundCommandLikeJavaScript() {
        // JS trim 剥 NBSP( )，Java String.trim() 只剥 ≤ U+0020 —— jsTrim 对齐 ECMAScript
        assertEquals(Optional.of(new SimpleCommand(SimpleCommand.SimpleName.STATUS)),
                CommandParser.parseCommand(" /status "));
    }

    @Test
    void trimsTrailingNbspOnCliRequestPrompt() {
        // 尾部 NBSP 应被剥掉：与 "/claude " 行为一致，group(2) 无内容 → prompt 空串
        assertEquals(Optional.of(new CliRequest(CliId.CLAUDE, "")),
                CommandParser.parseCliRequest("/claude "));
    }
}

package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.cli.CliId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应 TS 版 src/core/team-registry.ts 的语义对齐用例（brief 9 用例清单）。
 * BotConfig 用测试内私有工厂构造，不经过配置解析。
 */
class TeamRegistryTest {
    @TempDir
    Path tempDir;

    // ---------- fixture ----------

    private static BotRegistry.BotConfig config(String id, String role, List<String> skills,
            String workspaceDir) {
        return new BotRegistry.BotConfig(id, "app-" + id, "secret-" + id, CliId.CLAUDE,
                role, skills, "", workspaceDir, 16);
    }

    private static BotRegistry.BotConfig leaderConfig() {
        return config("boss", "团队负责人", List.of("to-spec", "to-tickets"),
                "D:/workspaces/boss");
    }

    private static BotRegistry.BotConfig memberConfig() {
        return config("dev", "开发工程师", List.of(), "D:/workspaces/dev");
    }

    // ---------- 用例 1：leader 不在 configs ----------

    @Test
    void leaderNotInConfigsThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new TeamRegistry("boss", List.of(memberConfig())));
        assertEquals("Team Leader 不存在: boss", e.getMessage());
    }

    // ---------- 用例 2：leader() 返回 leaderBotId 对应的 BotConfig ----------

    @Test
    void leaderReturnsLeaderConfig() {
        BotRegistry.BotConfig leader = leaderConfig();
        BotRegistry.BotConfig member = memberConfig();
        TeamRegistry registry = new TeamRegistry("boss", List.of(leader, member));
        assertEquals(leader, registry.leader());
        assertEquals("boss", registry.leaderBotId());
    }

    // ---------- 用例 3：members() 按构造插入序返回全部成员（含 leader） ----------

    @Test
    void membersKeepsInsertionOrderIncludingLeader() {
        BotRegistry.BotConfig leader = leaderConfig();
        BotRegistry.BotConfig member = memberConfig();
        BotRegistry.BotConfig another = config("qa", "测试工程师", List.of("review"),
                "D:/workspaces/qa");
        TeamRegistry registry = new TeamRegistry("boss", List.of(member, leader, another));
        assertEquals(List.of(member, leader, another), registry.members());
    }

    // ---------- 用例 4：get 未知 id 返回 null，已知 id 返回对应成员 ----------

    @Test
    void getUnknownReturnsNullAndGetExistingReturnsMember() {
        BotRegistry.BotConfig leader = leaderConfig();
        BotRegistry.BotConfig member = memberConfig();
        TeamRegistry registry = new TeamRegistry("boss", List.of(leader, member));
        assertNull(registry.get("ghost"));
        assertEquals(member, registry.get("dev"));
        assertEquals(leader, registry.get("boss"));
    }

    // ---------- 用例 5：contextFor 全量文案逐字断言 ----------

    @Test
    void contextForFullTextMatchesTsVersion() {
        TeamRegistry registry = new TeamRegistry("boss",
                List.of(leaderConfig(), memberConfig()));
        String expected = String.join("\n",
                "你所在的 Agent 团队：",
                "- boss（Team Leader）：团队负责人；Skills：$to-spec、$to-tickets",
                "- dev：开发工程师",
                "你当前以 boss 的身份工作。只处理交给你的职责；需要其他成员参与时，清楚说明希望交给谁以及期望结果。",
                "团队名单中的成员都是真实的飞书 bot。CLI 内部子 Agent 适合处理临时分工，不能冒充这些长期团队成员。",
                "需要把任务交给其他成员时，使用 dispatch_task 工具，由 Agent OS 发送协作卡片并真正 @ 对方。"
                        + "只有 CEO 助理可以在运行时调用该工具；targetBotId 必须来自上面的团队名单，不能填写自己。");
        assertEquals(expected, registry.contextFor("boss"));
    }

    // ---------- 用例 6：无 Skill 成员的行内无 Skills 段 ----------

    @Test
    void contextForOmitsSkillsSectionWhenNoSkills() {
        TeamRegistry registry = new TeamRegistry("boss",
                List.of(leaderConfig(), memberConfig()));
        String context = registry.contextFor("dev");
        assertTrue(context.contains("- dev：开发工程师\n"));
        assertFalse(context.contains("；Skills：\n"));
        assertEquals(1, countSkillsSegments(context));
    }

    private static int countSkillsSegments(String text) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("；Skills：", index)) >= 0) {
            count++;
            index += 1;
        }
        return count;
    }

    // ---------- 用例 7：contextFor 传入不在名册的 id ----------

    @Test
    void contextForUnknownMemberThrows() {
        TeamRegistry registry = new TeamRegistry("boss", List.of(leaderConfig()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.contextFor("ghost"));
        assertEquals("团队成员不存在: ghost", e.getMessage());
    }

    // ---------- 用例 8：工作区存在 Skill 文件则不缺失 ----------

    @Test
    void findMissingSkillsSkipsExistingWorkspaceSkill() throws IOException {
        Path skillFile = tempDir.resolve(".agents").resolve("skills").resolve("review")
                .resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "review skill");
        BotRegistry.BotConfig bot = config("qa", "测试工程师", List.of("review"),
                tempDir.toString());
        TeamRegistry registry = new TeamRegistry("qa", List.of(bot));
        assertTrue(registry.findMissingSkills().isEmpty());
    }

    // ---------- 用例 9：缺失 skill 的 searchedPaths 顺序与累积 ----------

    @Test
    void findMissingSkillsReportsSearchedPathsAndAccumulates() {
        // 与 TS 同假设：主目录下不存在同名 skill，user.home 指向真实主目录不影响判定
        String home = System.getProperty("user.home");
        BotRegistry.BotConfig first = config("qa", "测试工程师",
                List.of("ghost-skill", "missing-one"), tempDir.resolve("qa-ws").toString());
        BotRegistry.BotConfig second = config("dev", "开发工程师",
                List.of("no-such-skill"), tempDir.resolve("dev-ws").toString());
        TeamRegistry registry = new TeamRegistry("qa", List.of(first, second));

        List<TeamRegistry.MissingSkill> missing = registry.findMissingSkills();

        assertEquals(3, missing.size());
        assertMissingSkill(missing.get(0), "qa", "ghost-skill",
                tempDir.resolve("qa-ws").toString(), home);
        assertMissingSkill(missing.get(1), "qa", "missing-one",
                tempDir.resolve("qa-ws").toString(), home);
        assertMissingSkill(missing.get(2), "dev", "no-such-skill",
                tempDir.resolve("dev-ws").toString(), home);
    }

    private static void assertMissingSkill(TeamRegistry.MissingSkill actual, String botId,
            String skill, String workspaceDir, String home) {
        assertEquals(botId, actual.botId());
        assertEquals(skill, actual.skill());
        List<String> expectedPaths = List.of(
                Path.of(workspaceDir, ".agents", "skills", skill, "SKILL.md").toString(),
                Path.of(workspaceDir, ".claude", "skills", skill, "SKILL.md").toString(),
                Path.of(home, ".agents", "skills", skill, "SKILL.md").toString(),
                Path.of(home, ".claude", "skills", skill, "SKILL.md").toString(),
                Path.of(home, ".codex", "skills", skill, "SKILL.md").toString());
        assertEquals(expectedPaths, actual.searchedPaths());
    }
}

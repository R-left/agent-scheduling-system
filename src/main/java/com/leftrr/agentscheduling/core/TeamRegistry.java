package com.leftrr.agentscheduling.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 团队名册：leader + 成员、团队上下文提示词、Skill 存在性检测。
 * 对应 TS 版 src/core/team-registry.ts。configs 用 LinkedHashMap 保持构造插入序。
 */
public final class TeamRegistry {
    private final Map<String, BotRegistry.BotConfig> configs = new LinkedHashMap<>();
    private final String leaderBotId;

    public TeamRegistry(String leaderBotId, List<BotRegistry.BotConfig> configs) {
        this.leaderBotId = leaderBotId;
        for (BotRegistry.BotConfig config : configs) {
            this.configs.put(config.id(), config);
        }
        if (!this.configs.containsKey(leaderBotId)) {
            throw new IllegalArgumentException("Team Leader 不存在: " + leaderBotId);
        }
    }

    public String leaderBotId() {
        return leaderBotId;
    }

    public BotRegistry.BotConfig leader() {
        return configs.get(leaderBotId);
    }

    public List<BotRegistry.BotConfig> members() {
        return List.copyOf(configs.values());
    }

    /** 未知 id 返回 null（对齐 TS `BotConfig | undefined`）。 */
    public BotRegistry.BotConfig get(String botId) {
        return configs.get(botId);
    }

    public String contextFor(String currentBotId) {
        BotRegistry.BotConfig current = configs.get(currentBotId);
        if (current == null) {
            throw new IllegalArgumentException("团队成员不存在: " + currentBotId);
        }
        List<String> roster = new ArrayList<>();
        for (BotRegistry.BotConfig member : members()) {
            String leader = member.id().equals(leaderBotId) ? "（Team Leader）" : "";
            String skills = member.skills().isEmpty()
                    ? ""
                    : "；Skills：" + member.skills().stream()
                            .map(skill -> "$" + skill)
                            .collect(Collectors.joining("、"));
            roster.add("- " + member.id() + leader + "：" + member.role() + skills);
        }
        List<String> lines = new ArrayList<>();
        lines.add("你所在的 Agent 团队：");
        lines.addAll(roster);
        lines.add("你当前以 " + current.id() + " 的身份工作。只处理交给你的职责；需要其他成员参与时，清楚说明希望交给谁以及期望结果。");
        lines.add("团队名单中的成员都是真实的飞书 bot。CLI 内部子 Agent 适合处理临时分工，不能冒充这些长期团队成员。");
        lines.add("需要把任务交给其他成员时，使用 dispatch_task 工具，由 Agent OS 发送协作卡片并真正 @ 对方。只有 CEO 助理可以在运行时调用该工具；targetBotId 必须来自上面的团队名单，不能填写自己。");
        return String.join("\n", lines);
    }

    /**
     * 对齐 findMissingSkills()（TS async，本实现同步）。检测语义对齐 fs access：
     * 路径存在（不要求是文件类型）即视为命中，不查权限可达性；
     * 检测中的 IO 异常按「不存在」处理（对齐 TS catch 继续）。
     */
    public List<MissingSkill> findMissingSkills() {
        List<MissingSkill> missing = new ArrayList<>();
        for (BotRegistry.BotConfig config : members()) {
            for (String skill : config.skills()) {
                List<String> searchedPaths = List.of(
                        Path.of(config.workspaceDir(), ".agents", "skills", skill, "SKILL.md").toString(),
                        Path.of(config.workspaceDir(), ".claude", "skills", skill, "SKILL.md").toString(),
                        Path.of(System.getProperty("user.home"), ".agents", "skills", skill, "SKILL.md").toString(),
                        Path.of(System.getProperty("user.home"), ".claude", "skills", skill, "SKILL.md").toString(),
                        Path.of(System.getProperty("user.home"), ".codex", "skills", skill, "SKILL.md").toString());
                if (!somePathExists(searchedPaths)) {
                    missing.add(new MissingSkill(config.id(), skill, searchedPaths));
                }
            }
        }
        return missing;
    }

    private static boolean somePathExists(List<String> paths) {
        for (String path : paths) {
            if (Files.exists(Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    /** 对齐 TS MissingSkill 接口。 */
    public record MissingSkill(String botId, String skill, List<String> searchedPaths) {}
}

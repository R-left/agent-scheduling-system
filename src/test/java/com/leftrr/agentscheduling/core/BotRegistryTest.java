package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.cli.CliId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应 TS 版 src/core/bot-registry.test.ts 的语义对齐用例（brief 24 用例清单）。
 * fixture 用 @TempDir 写临时 JSON 文件；校验类用例直接对 JsonNode 调 parseAgentOsConfig。
 */
class BotRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ---------- fixture ----------

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> envOf(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            env.put(pairs[i], pairs[i + 1]);
        }
        return env;
    }

    private static Map<String, String> minimalEnv() {
        return envOf("LEADER_APP_ID", "appid", "LEADER_APP_SECRET", "secret");
    }

    private static final String MINIMAL_CONFIG = """
            {
              "teamLeader": "leader",
              "bots": [
                {
                  "id": "leader",
                  "appIdEnv": "LEADER_APP_ID",
                  "appSecretEnv": "LEADER_APP_SECRET",
                  "defaultCli": "claude",
                  "role": "组长"
                }
              ]
            }
            """;

    private BotRegistry.BotConfig botWithSkills(String... skills) {
        return new BotRegistry.BotConfig("alpha", "appid", "secret", CliId.CLAUDE,
                "开发", List.of(skills), "", tempDir.toString(), 16);
    }

    private static String baseDir(Path dir) {
        return dir.toAbsolutePath().toString();
    }

    // ---------- 用例 1：最小合法配置 → 各默认值就位 ----------

    @Test
    void minimalConfigAppliesDefaults() {
        BotRegistry.AgentOsConfig config = BotRegistry.parseAgentOsConfig(
                json(MINIMAL_CONFIG), minimalEnv(), baseDir(tempDir));
        assertEquals("leader", config.teamLeaderId());
        assertEquals(ProductDeliveryMode.LARK_DOC, config.defaultProductDeliveryMode());
        assertEquals(1, config.bots().size());
        BotRegistry.BotConfig bot = config.bots().get(0);
        assertEquals("leader", bot.id());
        assertEquals("appid", bot.appId());
        assertEquals("secret", bot.appSecret());
        assertEquals(CliId.CLAUDE, bot.defaultCliId());
        assertEquals("组长", bot.role());
        assertEquals(List.of(), bot.skills());
        assertEquals("", bot.systemPrompt());
        assertEquals(16, bot.collaborationMaxRounds());
        // workspace 回退 "." 按 baseDirectory 解析
        assertEquals(Path.of(baseDir(tempDir)).normalize().toString(), bot.workspaceDir());
    }

    // ---------- 用例 2：全字段显式配置 → 逐字段透传 ----------

    @Test
    void fullExplicitConfigPassesThrough() {
        String config = """
                {
                  "teamLeader": "alpha",
                  "defaultProductDeliveryMode": "local",
                  "bots": [
                    {
                      "id": "alpha",
                      "appIdEnv": "ALPHA_APP_ID",
                      "appSecretEnv": "ALPHA_APP_SECRET",
                      "defaultCli": "codex",
                      "role": "  开发  ",
                      "skills": ["to-spec", "to-tickets"],
                      "workspace": "ws-alpha",
                      "systemPrompt": "  系统提示  ",
                      "collaborationMaxRounds": 8,
                      "enabled": true
                    }
                  ]
                }
                """;
        BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(
                json(config), envOf("ALPHA_APP_ID", "a", "ALPHA_APP_SECRET", "s"), baseDir(tempDir));
        assertEquals(ProductDeliveryMode.LOCAL, parsed.defaultProductDeliveryMode());
        assertEquals(1, parsed.bots().size());
        BotRegistry.BotConfig bot = parsed.bots().get(0);
        assertEquals("alpha", bot.id());
        assertEquals("a", bot.appId());
        assertEquals("s", bot.appSecret());
        assertEquals(CliId.CODEX, bot.defaultCliId());
        assertEquals("开发", bot.role());
        assertEquals(List.of("to-spec", "to-tickets"), bot.skills());
        assertEquals(tempDir.resolve("ws-alpha").normalize().toString(), bot.workspaceDir());
        assertEquals("系统提示", bot.systemPrompt());
        assertEquals(8, bot.collaborationMaxRounds());
    }

    // ---------- 用例 3：bot id 违规三例 ----------

    @Test
    void rejectsInvalidBotIds() {
        for (String bad : List.of("ALPHA", "-alpha", "a".repeat(33))) {
            String config = """
                    {
                      "teamLeader": "leader",
                      "bots": [
                        {"id": "%s", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r"}
                      ]
                    }
                    """.formatted(bad);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)));
            assertEquals("bot id 只能使用小写字母、数字、连字符和下划线", ex.getMessage());
        }
    }

    // ---------- 用例 4：teamLeader 违规 ----------

    @Test
    void rejectsInvalidTeamLeader() {
        String config = """
                {
                  "teamLeader": "Leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r"}
                  ]
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)));
        assertEquals("teamLeader 只能使用小写字母、数字、连字符和下划线", ex.getMessage());
    }

    // ---------- 用例 5：appIdEnv / appSecretEnv 违规 ----------

    @Test
    void rejectsInvalidEnvNames() {
        String lowercase = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "lower_id", "appSecretEnv": "B", "defaultCli": "claude", "role": "r"}
                  ]
                }
                """;
        IllegalArgumentException exLower = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(lowercase), minimalEnv(), baseDir(tempDir)));
        assertEquals("appIdEnv 只能使用大写字母、数字和下划线", exLower.getMessage());

        String digitStart = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "1APP", "appSecretEnv": "bad-secret", "defaultCli": "claude", "role": "r"}
                  ]
                }
                """;
        IllegalArgumentException exDigit = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(digitStart), minimalEnv(), baseDir(tempDir)));
        assertEquals("appIdEnv 只能使用大写字母、数字和下划线", exDigit.getMessage());

        String secret = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "x", "defaultCli": "claude", "role": "r"}
                  ]
                }
                """;
        IllegalArgumentException exSecret = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(secret), minimalEnv(), baseDir(tempDir)));
        assertEquals("appSecretEnv 只能使用大写字母、数字和下划线", exSecret.getMessage());
    }

    // ---------- 用例 6：defaultCli 违规 ----------

    @Test
    void rejectsInvalidDefaultCli() {
        String config = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "bun", "role": "r"}
                  ]
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)));
        assertEquals("defaultCli 只能是 claude 或 codex", ex.getMessage());
    }

    // ---------- 用例 7：role 全空白 / 首尾空白 ----------

    @Test
    void roleTrimsAndRejectsBlank() {
        String blank = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "   "}
                  ]
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(blank), envOf("A", "a", "B", "b"), baseDir(tempDir)));
        assertEquals("role 不能为空", ex.getMessage());

        String padded = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "  组长  "}
                  ]
                }
                """;
        BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(
                json(padded), envOf("A", "a", "B", "b"), baseDir(tempDir));
        assertEquals("组长", parsed.bots().get(0).role());
    }

    // ---------- 用例 8：skill 名违规 / 重复去重保序 ----------

    @Test
    void skillNamesValidateAndDedupe() {
        String uppercase = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude",
                     "role": "r", "skills": ["To-spec"]}
                  ]
                }
                """;
        IllegalArgumentException exUpper = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(uppercase), minimalEnv(), baseDir(tempDir)));
        assertEquals("skill 名只能使用小写字母、数字和连字符", exUpper.getMessage());

        String underscore = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude",
                     "role": "r", "skills": ["a_b"]}
                  ]
                }
                """;
        IllegalArgumentException exUnder = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(underscore), minimalEnv(), baseDir(tempDir)));
        assertEquals("skill 名只能使用小写字母、数字和连字符", exUnder.getMessage());

        String dup = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude",
                     "role": "r", "skills": ["a", "b", "a"]}
                  ]
                }
                """;
        BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(
                json(dup), envOf("A", "a", "B", "b"), baseDir(tempDir));
        assertEquals(List.of("a", "b"), parsed.bots().get(0).skills());
    }

    // ---------- 用例 9：collaborationMaxRounds 违规 ----------

    @Test
    void rejectsInvalidCollaborationMaxRounds() {
        for (String raw : List.of("0", "33", "16.5", "\"abc\"")) {
            String config = """
                    {
                      "teamLeader": "leader",
                      "bots": [
                        {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude",
                         "role": "r", "collaborationMaxRounds": %s}
                      ]
                    }
                    """.formatted(raw);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)),
                    "raw = " + raw);
            assertEquals("collaborationMaxRounds 必须是 1 到 32 之间的整数", ex.getMessage());
        }
    }

    // ---------- 用例 9b：collaborationMaxRounds 浮点整值（对齐 JSON.parse + Number.isInteger） ----------

    @Test
    void acceptsIntegralFloatCollaborationMaxRounds() {
        // 16.0 与 8e0 均为整数浮点值，z.number().int() 经 JSON.parse 接受
        for (String raw : List.of("16.0", "8e0")) {
            String config = """
                    {
                      "teamLeader": "leader",
                      "bots": [
                        {"id": "leader", "appIdEnv": "LEADER_APP_ID", "appSecretEnv": "LEADER_APP_SECRET",
                         "defaultCli": "claude", "role": "r", "collaborationMaxRounds": %s}
                      ]
                    }
                    """.formatted(raw);
            BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(
                    json(config), minimalEnv(), baseDir(tempDir));
            assertEquals(raw.equals("16.0") ? 16 : 8, parsed.bots().get(0).collaborationMaxRounds(),
                    "raw = " + raw);
        }
    }

    // ---------- 用例 10：bot id 重复（disabled 也查重） ----------

    @Test
    void rejectsDuplicateBotIdsIncludingDisabled() {
        String config = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "alpha", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r"},
                    {"id": "alpha", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r",
                     "enabled": false}
                  ]
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)));
        assertEquals("bot id 不能重复: alpha", ex.getMessage());
    }

    // ---------- 用例 11：enabled 过滤 / 全部 disabled ----------

    @Test
    void filtersDisabledBots() {
        String config = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r"},
                    {"id": "helper", "appIdEnv": "C", "appSecretEnv": "D", "defaultCli": "codex", "role": "r",
                     "enabled": false}
                  ]
                }
                """;
        BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(
                json(config), envOf("A", "a", "B", "b", "C", "c", "D", "d"), baseDir(tempDir));
        assertEquals(1, parsed.bots().size());
        assertEquals("leader", parsed.bots().get(0).id());
    }

    @Test
    void rejectsAllDisabled() {
        String config = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r",
                     "enabled": false}
                  ]
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)));
        assertEquals("至少需要启用一个 bot", ex.getMessage());
    }

    // ---------- 用例 12：缺 appId env / 空白 env / 首尾空白 trim ----------

    @Test
    void missingOrBlankEnvThrowsAndTrimsPaddedValue() {
        String config = """
                {
                  "teamLeader": "alpha",
                  "bots": [
                    {"id": "alpha", "appIdEnv": "ALPHA_APP_ID", "appSecretEnv": "ALPHA_APP_SECRET",
                     "defaultCli": "claude", "role": "r"}
                  ]
                }
                """;

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config), Map.of(), baseDir(tempDir)));
        assertEquals("bot alpha 缺少环境变量 ALPHA_APP_ID", missing.getMessage());

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config),
                        envOf("ALPHA_APP_ID", "   ", "ALPHA_APP_SECRET", "s"), baseDir(tempDir)));
        assertEquals("bot alpha 缺少环境变量 ALPHA_APP_ID", blank.getMessage());

        BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(json(config),
                envOf("ALPHA_APP_ID", " appid ", "ALPHA_APP_SECRET", " secret "), baseDir(tempDir));
        assertEquals("appid", parsed.bots().get(0).appId());
        assertEquals("secret", parsed.bots().get(0).appSecret());
    }

    // ---------- 用例 13：workspace 回退链（?? 不跳过空串） ----------

    @Test
    void workspaceFallbackChain() {
        String template = """
                {
                  "teamLeader": "alpha",
                  "bots": [
                    {"id": "alpha", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude",
                     "role": "r"%s}
                  ]
                }
                """;

        // 显式 workspace 优先于环境变量
        String explicit = template.formatted(", \"workspace\": \"explicit-ws\"");
        BotRegistry.AgentOsConfig withExplicit = BotRegistry.parseAgentOsConfig(
                json(explicit),
                envOf("A", "a", "B", "b", "CLI_WORKDIR", "from-cli", "CLAUDE_WORKDIR", "from-claude"),
                baseDir(tempDir));
        assertEquals(tempDir.resolve("explicit-ws").normalize().toString(),
                withExplicit.bots().get(0).workspaceDir());

        // 无 workspace：CLI_WORKDIR 优先于 CLAUDE_WORKDIR
        String noWorkspace = template.formatted("");
        BotRegistry.AgentOsConfig withCli = BotRegistry.parseAgentOsConfig(
                json(noWorkspace),
                envOf("A", "a", "B", "b", "CLI_WORKDIR", "from-cli", "CLAUDE_WORKDIR", "from-claude"),
                baseDir(tempDir));
        assertEquals(tempDir.resolve("from-cli").normalize().toString(),
                withCli.bots().get(0).workspaceDir());

        // 仅 CLAUDE_WORKDIR
        BotRegistry.AgentOsConfig withClaude = BotRegistry.parseAgentOsConfig(
                json(noWorkspace),
                envOf("A", "a", "B", "b", "CLAUDE_WORKDIR", "from-claude"),
                baseDir(tempDir));
        assertEquals(tempDir.resolve("from-claude").normalize().toString(),
                withClaude.bots().get(0).workspaceDir());

        // 都缺 → "." 按 baseDirectory 解析
        BotRegistry.AgentOsConfig withDot = BotRegistry.parseAgentOsConfig(
                json(noWorkspace), envOf("A", "a", "B", "b"), baseDir(tempDir));
        assertEquals(Path.of(baseDir(tempDir)).normalize().toString(),
                withDot.bots().get(0).workspaceDir());

        // CLI_WORKDIR=""（空串）时 ?? 仍选中它 → resolveWorkspacePath 报「工作目录不能为空」
        IllegalArgumentException emptyCli = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(noWorkspace),
                        envOf("A", "a", "B", "b", "CLI_WORKDIR", "", "CLAUDE_WORKDIR", "from-claude"),
                        baseDir(tempDir)));
        assertEquals("工作目录不能为空", emptyCli.getMessage());
    }

    // ---------- 用例 14：相对路径按 baseDirectory 解析 ----------

    @Test
    void resolvesRelativeWorkspaceAgainstBaseDirectory() {
        String config = """
                {
                  "teamLeader": "alpha",
                  "bots": [
                    {"id": "alpha", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude",
                     "role": "r", "workspace": "ws/inner"}
                  ]
                }
                """;
        BotRegistry.AgentOsConfig parsed = BotRegistry.parseAgentOsConfig(
                json(config), envOf("A", "a", "B", "b"), baseDir(tempDir));
        assertEquals(tempDir.resolve("ws").resolve("inner").normalize().toString(),
                parsed.bots().get(0).workspaceDir());
    }

    // ---------- 用例 15：teamLeader 指向 disabled 的 bot ----------

    @Test
    void rejectsTeamLeaderPointingToDisabledBot() {
        String config = """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "claude", "role": "r",
                     "enabled": false},
                    {"id": "worker", "appIdEnv": "C", "appSecretEnv": "D", "defaultCli": "codex", "role": "r"}
                  ]
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(config),
                        envOf("A", "a", "B", "b", "C", "c", "D", "d"), baseDir(tempDir)));
        assertEquals("teamLeader 指向未启用的 bot: leader", ex.getMessage());
    }

    // ---------- 用例 16：根节点非对象 / bots 缺失或空 ----------

    @Test
    void rejectsNonObjectRootAndEmptyBots() {
        IllegalArgumentException array = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json("[]"), minimalEnv(), baseDir(tempDir)));
        assertEquals("配置根节点必须是 JSON 对象", array.getMessage());

        IllegalArgumentException string = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json("\"config\""), minimalEnv(), baseDir(tempDir)));
        assertEquals("配置根节点必须是 JSON 对象", string.getMessage());

        String noBots = """
                { "teamLeader": "leader" }
                """;
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(noBots), minimalEnv(), baseDir(tempDir)));
        assertEquals("bots 至少需要配置一个", missing.getMessage());

        String emptyBots = """
                { "teamLeader": "leader", "bots": [] }
                """;
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.parseAgentOsConfig(json(emptyBots), minimalEnv(), baseDir(tempDir)));
        assertEquals("bots 至少需要配置一个", empty.getMessage());
    }

    // ---------- 用例 16b：defaultProductDeliveryMode 非字符串 / 非法值 ----------

    @Test
    void rejectsInvalidDefaultProductDeliveryMode() {
        // 42（非字符串）、"bogus"（枚举外）、显式 null、true（非字符串）均拒绝，消息逐字
        for (String raw : List.of("42", "\"bogus\"", "null", "true")) {
            String config = """
                    {
                      "teamLeader": "leader",
                      "defaultProductDeliveryMode": %s,
                      "bots": [
                        {"id": "leader", "appIdEnv": "LEADER_APP_ID", "appSecretEnv": "LEADER_APP_SECRET",
                         "defaultCli": "claude", "role": "r"}
                      ]
                    }
                    """.formatted(raw);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> BotRegistry.parseAgentOsConfig(json(config), minimalEnv(), baseDir(tempDir)),
                    "raw = " + raw);
            assertEquals("defaultProductDeliveryMode 只能是 local 或 lark-doc", ex.getMessage());
        }
    }

    // ---------- 用例 17：parseBotConfigs 返回 bots 列表 ----------

    @Test
    void parseBotConfigsReturnsBotList() {
        List<BotRegistry.BotConfig> bots = BotRegistry.parseBotConfigs(
                json(MINIMAL_CONFIG), minimalEnv(), baseDir(tempDir));
        assertEquals(1, bots.size());
        assertEquals("leader", bots.get(0).id());
    }

    // ---------- 用例 18：loadAgentOsConfig 文件不存在 ----------

    @Test
    void loadMissingFileThrows() {
        Path missing = tempDir.resolve("no-such-bots.json");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.loadAgentOsConfig(missing.toString(), minimalEnv(), baseDir(tempDir)));
        assertEquals("找不到 bot 配置文件: " + missing
                + "。请复制 config/bots.example.json 后填写配置。", ex.getMessage());
    }

    // ---------- 用例 19：非法 JSON / schema 违规均包装 ----------

    @Test
    void loadWrapsParseErrors() throws IOException {
        Path badJson = tempDir.resolve("bad-json.json");
        Files.writeString(badJson, "{not json");
        IllegalArgumentException exJson = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.loadAgentOsConfig(badJson.toString(), minimalEnv(), baseDir(tempDir)));
        assertTrue(exJson.getMessage().startsWith("bot 配置文件格式错误: "),
                exJson.getMessage());

        Path badSchema = tempDir.resolve("bad-schema.json");
        Files.writeString(badSchema, """
                {
                  "teamLeader": "leader",
                  "bots": [
                    {"id": "leader", "appIdEnv": "A", "appSecretEnv": "B", "defaultCli": "bun", "role": "r"}
                  ]
                }
                """);
        IllegalArgumentException exSchema = assertThrows(IllegalArgumentException.class,
                () -> BotRegistry.loadAgentOsConfig(badSchema.toString(), minimalEnv(), baseDir(tempDir)));
        assertEquals("bot 配置文件格式错误: defaultCli 只能是 claude 或 codex", exSchema.getMessage());
    }

    // ---------- 用例 20：合法文件加载 ----------

    @Test
    void loadsValidFile() throws IOException {
        Path file = tempDir.resolve("bots.json");
        Files.writeString(file, MINIMAL_CONFIG);
        BotRegistry.AgentOsConfig config = BotRegistry.loadAgentOsConfig(
                file.toString(), minimalEnv(), baseDir(tempDir));
        assertEquals("leader", config.teamLeaderId());
        assertEquals(1, config.bots().size());

        List<BotRegistry.BotConfig> bots = BotRegistry.loadBotConfigs(
                file.toString(), minimalEnv(), baseDir(tempDir));
        assertEquals(1, bots.size());
        assertEquals("leader", bots.get(0).id());
    }

    // ---------- 用例 21：交付规则块按 skill 命中 ----------

    @Test
    void buildBotPromptDeliveryPolicyBySkills() {
        String withToSpec = BotRegistry.buildBotPrompt(botWithSkills("to-spec"), "做");
        assertTrue(withToSpec.contains("产品方案交付规则（必须遵守）："), withToSpec);
        assertTrue(withToSpec.contains("- 当前默认交付方式：lark-doc。"), withToSpec);

        assertTrue(BotRegistry.buildBotPrompt(botWithSkills("to-tickets"), "做")
                .contains("产品方案交付规则（必须遵守）："));
        assertTrue(BotRegistry.buildBotPrompt(botWithSkills("lark-doc"), "做")
                .contains("产品方案交付规则（必须遵守）："));

        String without = BotRegistry.buildBotPrompt(botWithSkills("other"), "做");
        assertFalse(without.contains("产品方案交付规则"), without);
    }

    @Test
    void buildBotPromptDeliveryPolicyReflectsMode() {
        String prompt = BotRegistry.buildBotPrompt(botWithSkills("to-spec"), "做",
                "", ProductDeliveryMode.LOCAL);
        assertTrue(prompt.contains("- 当前默认交付方式：local。"), prompt);
    }

    // ---------- 用例 22：Skill 加载规则块 ----------

    @Test
    void buildBotPromptSkillLoadingRules() {
        String prompt = BotRegistry.buildBotPrompt(botWithSkills("a", "b"), "做");
        assertTrue(prompt.contains("项目 Skill 加载规则（优先级不可颠倒）："), prompt);
        assertTrue(prompt.contains("本次任务必须执行的项目 Skill：$a、$b"), prompt);

        String noSkills = BotRegistry.buildBotPrompt(botWithSkills(), "做");
        assertFalse(noSkills.contains("项目 Skill 加载规则"), noSkills);
    }

    // ---------- 用例 23：空白段剔除与段序 ----------

    @Test
    void buildBotPromptSectionOrderAndBlankRemoval() {
        String withBlankSections = BotRegistry.buildBotPrompt(
                new BotRegistry.BotConfig("alpha", "appid", "secret", CliId.CLAUDE,
                        "开发", List.of(), "   ", tempDir.toString(), 16),
                "做", "   ", ProductDeliveryMode.LARK_DOC);
        assertFalse(withBlankSections.startsWith("\n\n"));
        assertTrue(withBlankSections.startsWith("你的角色：开发"), withBlankSections);
        // 角色段与飞书输出规则段直接相邻（中间空白段被 filter(Boolean) 剔除）
        assertTrue(withBlankSections.contains("你的角色：开发\n\n飞书输出规则（必须遵守）："),
                withBlankSections);
        assertTrue(withBlankSections.endsWith("当前任务：做"), withBlankSections);

        // skill 取 to-spec 使交付规则块出现（覆盖全段序断言）
        String full = BotRegistry.buildBotPrompt(
                new BotRegistry.BotConfig("alpha", "appid", "secret", CliId.CLAUDE,
                        "开发", List.of("to-spec"), "sys", tempDir.toString(), 16),
                "做", "team", ProductDeliveryMode.LARK_DOC);
        // 段序：角色 → systemPrompt → 团队上下文 → 交付规则 → Skill 规则 → 飞书输出规则 → 当前任务
        List<String> markers = List.of("你的角色：开发", "sys", "team", "产品方案交付规则",
                "项目 Skill 加载规则", "飞书输出规则", "当前任务：做");
        int prev = -1;
        for (String marker : markers) {
            int index = full.indexOf(marker);
            assertTrue(index > prev, marker + " order violated: " + full);
            prev = index;
        }
        // 段间以 \n\n 连接
        assertTrue(full.contains("sys\n\nteam"), full);
    }

    // ---------- 用例 24：飞书输出规则 4 行逐字 ----------

    @Test
    void buildBotPromptFeishuOutputPolicyVerbatim() {
        String prompt = BotRegistry.buildBotPrompt(botWithSkills(), "做");
        assertTrue(prompt.contains("""
                飞书输出规则（必须遵守）：
                - 最终回复控制在 1200 个中文字符以内，先给结论，再给必要依据和下一步。
                - 不在回复中粘贴完整代码、长日志或整份产品文档，也不要输出 Markdown 表格。
                - 详细产物写入当前工作区文件。回复只提供简短摘要和文件路径。
                - 需要用户决策时，必须调用 request_clarification 工具；不要用大段文字列出问题。工具调用后停止继续推断，等待用户回答。"""), prompt);
    }
}

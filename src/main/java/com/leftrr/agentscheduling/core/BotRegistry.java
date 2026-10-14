package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.util.StringUtil;
import com.leftrr.agentscheduling.dto.cli.CliId;
import com.leftrr.agentscheduling.dto.core.ProductDeliveryMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * bot 注册配置读取与校验。构造期逐字段校验，失败抛 IllegalArgumentException。
 */
public final class BotRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final Pattern SKILL_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private BotRegistry() {}

    /** 单个 bot 的最终配置形态。 */
    public record BotConfig(
            String id,
            String appId,
            String appSecret,
            CliId defaultCliId,
            String role,
            List<String> skills,
            String systemPrompt,
            String workspaceDir,
            int collaborationMaxRounds) {}

    /** 全量配置形态（teamLeader + 默认交付模式 + bots）。 */
    public record AgentOsConfig(
            String teamLeaderId,
            ProductDeliveryMode defaultProductDeliveryMode,
            List<BotConfig> bots) {}

    /** 解析中间态：enabled 过滤发生在校验之后，故携带原始字段。凭证只支持直填。 */
    private record RawBot(
            String id,
            String appId,
            String appSecret,
            CliId defaultCli,
            String role,
            List<String> skills,
            String workspace,
            String systemPrompt,
            int collaborationMaxRounds,
            boolean enabled) {}

    public static AgentOsConfig parseAgentOsConfig(JsonNode input, String baseDirectory) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("配置根节点必须是 JSON 对象");
        }
        String teamLeader = patternField(input, "teamLeader", ID_PATTERN,
                "teamLeader 只能使用小写字母、数字、连字符和下划线");
        ProductDeliveryMode deliveryMode = ProductDeliveryMode.LARK_DOC;
        JsonNode deliveryNode = input.get("defaultProductDeliveryMode");
        if (deliveryNode != null) {
            String raw = textOrNull(deliveryNode);
            ProductDeliveryMode parsed = raw == null ? null : ProductDeliveryMode.fromId(raw);
            if (parsed == null) {
                throw new IllegalArgumentException("defaultProductDeliveryMode 只能是 local 或 lark-doc");
            }
            deliveryMode = parsed;
        }
        JsonNode botsNode = input.get("bots");
        if (botsNode == null || !botsNode.isArray() || botsNode.isEmpty()) {
            throw new IllegalArgumentException("bots 至少需要配置一个");
        }
        List<RawBot> parsed = new ArrayList<>();
        for (int index = 0; index < botsNode.size(); index++) {
            parsed.add(parseBot(botsNode.get(index), index));
        }
        Set<String> ids = new HashSet<>();
        for (RawBot bot : parsed) {
            if (!ids.add(bot.id())) {
                throw new IllegalArgumentException("bot id 不能重复: " + bot.id());
            }
        }
        List<BotConfig> configs = new ArrayList<>();
        for (RawBot bot : parsed) {
            if (!bot.enabled()) {
                continue;
            }
            // workspace 缺省兜底为当前目录（空串不算缺省，交由 resolveWorkspacePath 报错）
            String workspace = bot.workspace() != null ? bot.workspace() : ".";
            configs.add(new BotConfig(bot.id(), bot.appId(), bot.appSecret(), bot.defaultCli(),
                    bot.role(), dedupe(bot.skills()), bot.systemPrompt(),
                    Workspace.resolveWorkspacePath(workspace, baseDirectory),
                    bot.collaborationMaxRounds()));
        }
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("至少需要启用一个 bot");
        }
        Set<String> enabledIds = new HashSet<>();
        for (BotConfig config : configs) {
            enabledIds.add(config.id());
        }
        if (!enabledIds.contains(teamLeader)) {
            throw new IllegalArgumentException("teamLeader 指向未启用的 bot: " + teamLeader);
        }
        return new AgentOsConfig(teamLeader, deliveryMode, List.copyOf(configs));
    }

    /**
     * 默认配置文件路径：{@code ~/.agentos/bots.json}（user.home 在 Windows/macOS
     * 均指向用户主目录，无平台分支）。
     */
    public static java.nio.file.Path defaultConfigFile() {
        return java.nio.file.Path.of(System.getProperty("user.home"), ".agentos", "bots.json");
    }

    /**
     * 从 {@link #defaultConfigFile()} 读取 bot 配置。文件不存在时把 classpath 内置
     * 模板 bots.example.json 生成到该路径，然后抛错让用户填凭证后重启——
     * 不静默降级、不用占位凭证起服务。
     * workspace 相对路径按配置文件所在目录（~/.agentos）解析，与 CWD 无关。
     */
    public static AgentOsConfig loadAgentOsConfig() {
        java.nio.file.Path configFile = defaultConfigFile();
        if (!java.nio.file.Files.exists(configFile)) {
            createConfigTemplate(configFile);
            throw new IllegalArgumentException(
                    "配置文件不存在，已生成模板 " + configFile + "，请填写 appId/appSecret 后重新启动。");
        }
        String content;
        try {
            content = java.nio.file.Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "bot 配置文件读取失败: " + configFile + ": " + e.getMessage());
        }
        return parseConfigJson(content, configFile.getParent().toString());
    }

    /** 把 classpath 内置模板复制为用户配置文件（父目录不存在则创建）。 */
    private static void createConfigTemplate(java.nio.file.Path configFile) {
        try (java.io.InputStream in = BotRegistry.class.getResourceAsStream("/bots.example.json")) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 下找不到配置模板 bots.example.json");
            }
            java.nio.file.Files.createDirectories(configFile.getParent());
            java.nio.file.Files.copy(in, configFile);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "配置模板生成失败: " + configFile + ": " + e.getMessage());
        }
    }

    private static AgentOsConfig parseConfigJson(String content, String baseDirectory) {
        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (IOException e) {
            throw new IllegalArgumentException("bot 配置文件格式错误: " + e.getMessage());
        }
        try {
            return parseAgentOsConfig(root, baseDirectory);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bot 配置文件格式错误: " + e.getMessage());
        }
    }

    /** 拼接 bot 系统提示词（角色 / 提示词 / 团队上下文 / 交付规则 / Skill 规则 / 任务）。 */
    public static String buildBotPrompt(BotConfig config, String prompt, String teamContext,
            ProductDeliveryMode defaultProductDeliveryMode) {
        boolean managesProductDocuments = config.skills().stream()
                .anyMatch(skill -> "to-spec".equals(skill) || "to-tickets".equals(skill)
                        || "lark-doc".equals(skill));
        String productDeliveryPolicy = managesProductDocuments
                ? String.join("\n",
                        "产品方案交付规则（必须遵守）：",
                        "- 当前默认交付方式：" + defaultProductDeliveryMode.id() + "。",
                        "- 用户明确指定本地 Markdown 或飞书云文档时，以用户本次选择覆盖默认值。",
                        "- 不要为了选择交付格式单独发起澄清。",
                        "- 方案产物完成后必须实际调用 request_spec_approval，并提交最终采用的 deliveryMode 与对应产物字段。",
                        "- 不能只在普通回复中罗列 deliveryMode、documentUrl、specPath 或 ticketsPath。工具调用成功后停止本轮。")
                : "";
        String feishuOutputPolicy = String.join("\n",
                "飞书输出规则（必须遵守）：",
                "- 最终回复控制在 1200 个中文字符以内，先给结论，再给必要依据和下一步。",
                "- 不在回复中粘贴完整代码、长日志或整份产品文档，也不要输出 Markdown 表格。",
                "- 详细产物写入当前工作区文件。回复只提供简短摘要和文件路径。",
                "- 需要用户决策时，必须调用 request_clarification 工具；不要用大段文字列出问题。工具调用后停止继续推断，等待用户回答。");
        String skillsSection = config.skills().isEmpty()
                ? ""
                : String.join("\n",
                        "项目 Skill 加载规则（优先级不可颠倒）：",
                        "- 对配置中声明的每个 Skill，先读取当前工作区 `.agents/skills/<skill>/SKILL.md`。",
                        "- 上述路径不存在时，再读取当前工作区 `.claude/skills/<skill>/SKILL.md`。",
                        "- 只有两个工作区路径都不存在时，才允许回退到用户级或全局同名 Skill；不得因全局 Skill 同名而跳过工作区版本。",
                        "本次任务必须执行的项目 Skill：" + config.skills().stream()
                                .map(skill -> "$" + skill)
                                .collect(Collectors.joining("、")));
        List<String> sections = List.of(
                "你的角色：" + config.role(),
                // 按完整 Unicode 空白全集 trim（含 NBSP/全角空格），非 Java String.trim
                StringUtil.trim(config.systemPrompt()),
                teamContext == null ? "" : StringUtil.trim(teamContext),
                productDeliveryPolicy,
                skillsSection,
                feishuOutputPolicy,
                "当前任务：" + prompt);
        return sections.stream()
                .filter(section -> !section.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    public static String buildBotPrompt(BotConfig config, String prompt) {
        return buildBotPrompt(config, prompt, "", ProductDeliveryMode.LARK_DOC);
    }

    private static RawBot parseBot(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("bots[" + index + "] 必须是 JSON 对象");
        }
        String id = patternField(node, "id", ID_PATTERN,
                "bot id 只能使用小写字母、数字、连字符和下划线");
        String appId = requiredTrimmedText(node, "appId",
                "bot " + id + " 缺少 appId");
        String appSecret = requiredTrimmedText(node, "appSecret",
                "bot " + id + " 缺少 appSecret");
        String defaultCliRaw = textOrNull(node.get("defaultCli"));
        CliId defaultCli = defaultCliRaw == null ? null : CliId.fromId(defaultCliRaw).orElse(null);
        if (defaultCli == null) {
            throw new IllegalArgumentException("defaultCli 只能是 claude 或 codex");
        }
        String role = requiredTrimmedText(node, "role", "role 不能为空");
        List<String> skills = new ArrayList<>();
        JsonNode skillsNode = node.get("skills");
        if (skillsNode != null) {
            if (!skillsNode.isArray()) {
                throw new IllegalArgumentException("skills 必须是字符串数组");
            }
            for (JsonNode skill : skillsNode) {
                String value = textOrNull(skill);
                if (value == null || !SKILL_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException("skill 名只能使用小写字母、数字和连字符");
                }
                skills.add(value);
            }
        }
        String workspace = optionalTrimmedText(node, "workspace");
        if (workspace != null && workspace.isEmpty()) {
            throw new IllegalArgumentException("workspace 不能为空");
        }
        String systemPrompt = optionalTrimmedText(node, "systemPrompt");
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        int collaborationMaxRounds = 16;
        JsonNode roundsNode = node.get("collaborationMaxRounds");
        if (roundsNode != null) {
            // JSON 数值无整型区分，16.0 / 1e2 也视为整数，
            // canConvertToExactIntegral 对浮点节点额外检查无小数部分
            if (!roundsNode.canConvertToExactIntegral()
                    || roundsNode.asInt() < 1 || roundsNode.asInt() > 32) {
                throw new IllegalArgumentException("collaborationMaxRounds 必须是 1 到 32 之间的整数");
            }
            collaborationMaxRounds = roundsNode.asInt();
        }
        boolean enabled = true;
        JsonNode enabledNode = node.get("enabled");
        if (enabledNode != null) {
            if (!enabledNode.isBoolean()) {
                throw new IllegalArgumentException("enabled 必须是布尔值");
            }
            enabled = enabledNode.asBoolean();
        }
        return new RawBot(id, appId, appSecret, defaultCli, role,
                List.copyOf(skills), workspace, systemPrompt, collaborationMaxRounds, enabled);
    }

    private static String patternField(JsonNode node, String field, Pattern pattern, String message) {
        String value = textOrNull(node.get(field));
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requiredTrimmedText(JsonNode node, String field, String message) {
        String value = optionalTrimmedText(node, field);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /** 字段缺席 → null；存在但非字符串 → 抛；返回按 Unicode 空白全集 trim 后的文本。 */
    private static String optionalTrimmedText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        return StringUtil.trim(value.asText());
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** 去重保序。 */
    private static List<String> dedupe(List<String> skills) {
        return List.copyOf(new LinkedHashSet<>(skills));
    }
}

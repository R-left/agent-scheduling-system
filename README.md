# agent-scheduling-system

以飞书话题群为操作界面、Claude Code / Codex 为执行引擎的多agent协作系统。

每个 bot 是一个独立配置的飞书应用，拥有自己的角色、Skill 集与工作区；成员 bot 之间可以互相派发任务，共同完成「澄清需求 → 产品方案 → 实现验收」的协作流程。

## 环境要求

- **JDK 21+**、Maven 3.6+
- **Windows**（进程树清理依赖 `taskkill /t /f`；其他平台未适配）
- `claude` 与 `codex` CLI 在 PATH 中（headless 模式）
- 按需的外部工具：`lark-cli`（lark-doc Skill 生成云文档）、`agent-browser`（浏览器验收）

## 快速开始

```bash
mvn package -DskipTests
java -jar target/agent-os.jar
```

1. **首次启动**：检测到 `~/.agentos/bots.json` 不存在时，自动生成配置模板并退出；
2. **填写凭证**：编辑 `~/.agentos/bots.json`，把每个 bot 的 `appId` / `appSecret` 换成真实飞书应用凭证；
3. **重新启动**：`java -jar target/agent-os.jar`，日志出现各 bot 「已连接」即就绪。

凭证只存在于 `~/.agentos/`（用户目录），不进仓库、与 CWD 无关——fat jar 从任意目录启动行为一致。

## 配置：~/.agentos/bots.json

```jsonc
{
  "teamLeader": "ceo-assistant",          // CEO 助理 bot id，唯一有权 dispatch_task 的成员
  "defaultProductDeliveryMode": "lark-doc", // 产品方案默认交付模式：lark-doc | local
  "bots": [
    {
      "id": "ceo-assistant",              // bot 唯一 id（小写字母/数字/_/-）
      "appId": "cli_xxx",                 // 飞书应用 App ID
      "appSecret": "xxx",                 // 飞书应用 App Secret
      "defaultCli": "claude",             // 默认执行引擎：claude | codex
      "workspace": "../agent-os-team-example", // 相对路径按 ~/.agentos 解析
      "role": "CEO 助理，……",              // 角色描述（拼进系统提示词）
      "skills": [],                       // Skill 名单，缺失只告警不阻断
      "systemPrompt": "……"                // bot 专属系统提示词
      // "collaborationMaxRounds": 16     // 可选：协作轮次上限（默认值见 TeamRegistry）
    }
  ]
}
```

完整模板见 `bots.example.json`。

### Skill 查找路径

配置里的每个 skill 名按以下顺序探测（找到任一 `SKILL.md` 即可，启动时缺失会打 `[Skill]` 告警）：

1. `<workspace>/.agents/skills/<skill>/SKILL.md`
2. `<workspace>/.claude/skills/<skill>/SKILL.md`
3. `~/.agents/skills/<skill>/SKILL.md`
4. `~/.claude/skills/<skill>/SKILL.md`
5. `~/.codex/skills/<skill>/SKILL.md`

## 使用方式

在飞书话题群里 @ 对应 bot 说话即可；bot 会话按「chatId + threadId」隔离，同一话题内上下文连续。

### 斜杠命令

| 命令 | 作用 |
|---|---|
| `/status` | 查看当前话题的会话状态（引擎、工作区、CLI 会话、token 用量） |
| `/new` | 丢弃当前会话，下条消息新建 |
| `/resume` | 从 CLI 原生历史会话中恢复（交互卡片选择） |
| `/cd <path>` | 切换本会话的工作目录 |
| `/compact [说明]` | 压缩当前会话上下文（CLI 原生 /compact） |
| `/close` | 关闭当前会话 |
| `/team` | 查看团队名册（leader、成员、Skill 就绪情况） |
| `/claude <prompt>` / `/codex <prompt>` | 本条消息临时指定执行引擎 |

### 内置协作流程

- **需求澄清**：产品 bot 装有 `grill-me` Skill 时，可调用 `request_clarification` 工具发起飞书问题卡（1-5 题、每题 2-4 选项），用户逐题作答后自动续跑；
- **产品方案**：产品 bot 以 `request_spec_approval` 提交唯一待确认产物——`lark-doc` 模式交云文档 URL，`local` 模式交工作区内的 spec/tickets 文件路径（会校验真实存在）；用户在确认卡上「通过」后进入开发；
- **任务派发**：CEO 助理调用 `dispatch_task` 把任务交给名册成员（确定性派发，带轮次上限与防重），成员完成后结果自动交回；文档评论 @bot 也能触发按评论改文档的执行流。

## 架构

```text
src/main/java/com/leftrr/agentscheduling/
├── Main.java        # 入口：装配 + 飞书长连接保活
├── core/            # 领域核心（会话/协作/澄清/产品流程状态机，不碰 SDK 与子进程）
├── cli/             # CLI 执行引擎适配层（claude/codex → 统一事件协议）
├── im/              # 飞书接入层（WS 长连接、REST、消息解析、卡片）
├── app/             # 应用编排（命令分流、收尾分支、卡片回调、协作服务）
├── mcp/             # agent_scheduling MCP 工具（长驻 HTTP server + CLI 参数构造）
├── dto/             # 纯数据类型（按 core/cli/im 分包）
├── util/            # 通用工具（StringUtil / IsoTimeUtil / JsonUtil）
└── logging/         # logback 扩展（按级别分流 stdout/stderr）
```

依赖方向：`core ← mcp ← cli ← im ← app ← index`，无运行时循环依赖。

### MCP 工具的实现方式

三个工具（`request_clarification` / `request_spec_approval` / `dispatch_task`）由主进程**内嵌的 streamable HTTP MCP server** 提供（`mcp/HttpAppToolsServer`：内嵌 Tomcat、随机端口只绑 127.0.0.1、Bearer token 鉴权），全 bot 共享一个实例——不按运行拉起子进程。server 只做 schema 校验与固定文案回执；真实副作用（发卡、建流程）由主进程从 CLI 事件流截获 tool_call 后完成。

## 运行时数据

- `~/.agentos/data/sessions.json` — 会话持久化（原子写、旧格式迁移、中断恢复）
- `~/.agentos/data/product-spec-flows.json` — 产品方案流程
- `~/.agentos/data/downloads/` — 消息附件
- 内存态（重启即失）：澄清流程、协作收件箱、进行中任务、防重集合

日志走 logback：info 为主走 stdout，失败类 error 定向 stderr（分流规则见 `src/main/resources/logback.xml` 头注释）。

## 常见问题

- **MCP 工具没挂上**：logback 里把 `io.modelcontextprotocol` 的 level 临时调到 `debug` 观察握手；claude 用 `type:http + headers`，codex 的 `-c mcp_servers.agent_scheduling.http_headers` 键名如不生效请核对你本机 codex 版本的配置文档。
- **启动报 workspace 不存在**：`bots.json` 里的 `workspace` 按 `~/.agentos` 解析相对路径，请确认目录真实存在。
- **`[Skill]` 告警**：对应 Skill 未安装到上列任一查找路径，不影响启动，但相关流程（如澄清、产品提交）不会被该 bot 触发。

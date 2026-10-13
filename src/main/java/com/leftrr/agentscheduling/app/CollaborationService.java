package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.CollaborationCards;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/**
 * bot 间协作派发服务。对齐 TS 版 src/app/collaboration-service.ts（95 行）：
 * 六步控制流——target/reportTo 就绪检查、组装 CollaborationMessage 落账、
 * 发协作卡、发 mention 通知，任何一步失败回收收件箱后重抛，成功打日志。
 *
 * <p>TS→Java 差异：TS async/await → Java 同步调用（Bot 门面全同步，设计裁决 9b）；
 * TS Error → RuntimeException（对齐 M6 Task 3 先例）；TS undefined → null。
 * replyCard/replyMention 均不声明受检异常（M4 实际签名），故 dispatch 无 throws。</p>
 */
public class CollaborationService {

    /** M7 Task 0（裁决 2）：TS console.log 成功日志的 SLF4J 对位（走根 STDOUT appender）。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(CollaborationService.class);

    /**
     * 对齐 TS CollaborationDispatch 接口（collaboration-service.ts:9-24）；
     * 按仓库惯例（BotOptions 先例）落为嵌套 record。ownerUnionId/expectedOutput
     * null 表示 TS undefined。
     */
    public record CollaborationDispatch(
            BotRegistry.BotConfig senderConfig,
            Bot senderBot,
            String replyToMessageId,
            String targetBotId,
            String taskId,
            String ownerOpenId,
            String ownerUnionId,
            String reportToBotId,
            String objective,
            String instruction,
            String expectedOutput,
            int round,
            int maxRounds,
            String workspaceDir) {}

    private final AppRuntime runtime;

    public CollaborationService(AppRuntime runtime) {
        this.runtime = runtime;
    }

    /** TS dispatch（collaboration-service.ts:29-93）。 */
    public void dispatch(CollaborationDispatch options) {
        BotRuntime target = runtime.botRuntimes().get(options.targetBotId());
        if (target == null) {
            throw new RuntimeException("协作 bot 尚未就绪: " + options.targetBotId());
        }
        BotRuntime reportTo = runtime.botRuntimes().get(options.reportToBotId());
        if (reportTo == null) {
            throw new RuntimeException("结果接收 bot 尚未就绪: " + options.reportToBotId());
        }

        // randomUUID().replaceAll('-', '').slice(0, 12) → 同构的 UUID 截断
        Collaboration.CollaborationMessage collaboration = new Collaboration.CollaborationMessage(
                UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                options.taskId(),
                options.ownerOpenId(),
                options.ownerUnionId(),
                options.senderConfig().id(),
                options.targetBotId(),
                options.reportToBotId(),
                options.objective(),
                options.instruction(),
                options.expectedOutput(),
                options.round(),
                options.maxRounds(),
                options.workspaceDir());
        runtime.collaborationInbox().register(collaboration);

        try {
            // TS: botRuntimes.get(senderConfig.id)?.identity.name ?? senderConfig.id
            BotRuntime senderRuntime = runtime.botRuntimes().get(options.senderConfig().id());
            String senderName = senderRuntime != null
                    ? senderRuntime.identity().name()
                    : options.senderConfig().id();
            // TS basename(workspaceDir) → Path.of(...).getFileName()；
            // ⑳：根路径（如 C:\、/）的 getFileName() 返回 null → .toString() 会 NPE；
            // TS basename 对根路径同样不抛，但输出不等价——win32 返回盘符（如 "C:"）、
            // posix 返回空串，Java 无同构形态可取，null-safe 回落完整 workspaceDir 字符串
            //（M6 挂账⑳注释措辞修正：原「返回盘符/路径本身」描述不准确，按路线图 M6 收口备案原文改）
            Path workspacePath = Path.of(options.workspaceDir());
            String workspaceName = workspacePath.getFileName() != null
                    ? workspacePath.getFileName().toString() : options.workspaceDir();
            String cardMessageId = options.senderBot().replyCard(
                    options.replyToMessageId(),
                    CollaborationCards.buildCollaborationCard(
                            new CollaborationCards.CollaborationCardOptions(
                                    senderName,
                                    target.identity().name(),
                                    reportTo.identity().name(),
                                    workspaceName,
                                    options.objective(),
                                    options.instruction(),
                                    options.expectedOutput(),
                                    options.round(),
                                    options.maxRounds())),
                    true);
            if (cardMessageId == null) {
                throw new RuntimeException("飞书没有返回协作卡片 message_id");
            }
            String mentionMessageId = options.senderBot().replyMention(
                    cardMessageId,
                    target.identity(),
                    options.round() == 1
                            ? "新的协作任务：" + options.objective()
                                    + "（任务编号：" + collaboration.dispatchId() + "），请查看上方卡片。"
                            : "协作结果已经返回（任务编号：" + collaboration.dispatchId()
                                    + "），请查看上方卡片。",
                    true);
            if (mentionMessageId == null) {
                throw new RuntimeException("飞书没有返回协作通知 message_id");
            }
        } catch (RuntimeException error) {
            // 任何一步失败：回收刚落账的消息后重抛（对齐 TS catch 分支）
            runtime.collaborationInbox().consume(collaboration.dispatchId(), collaboration.toBotId());
            throw error;
        }
        // ㉒：M7 Task 0（裁决 2）切 SLF4J（{} 占位与原拼接等价，文案不变）
        LOGGER.info("[协作] task={} {} -> {} round={}/{}",
                options.taskId(), options.senderConfig().id(),
                options.targetBotId(), options.round(), options.maxRounds());
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.dto.app.BotRuntime;
import com.leftrr.agentscheduling.im.Bot;
import com.leftrr.agentscheduling.im.CollaborationCards;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * bot 间协作派发：落账 → 发协作卡 → 发 @通知，任何一步失败回收收件箱后重抛。
 * 全程同步调用；replyCard/replyMention 均不声明受检异常，故 dispatch 无 throws。
 */
public class CollaborationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollaborationService.class);

    /** ownerUnionId / expectedOutput 为 null 表示缺省。 */
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

    public void dispatch(CollaborationDispatch options) {
        BotRuntime target = runtime.botRuntimes().get(options.targetBotId());
        if (target == null) {
            throw new RuntimeException("协作 bot 尚未就绪: " + options.targetBotId());
        }
        BotRuntime reportTo = runtime.botRuntimes().get(options.reportToBotId());
        if (reportTo == null) {
            throw new RuntimeException("结果接收 bot 尚未就绪: " + options.reportToBotId());
        }

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
            BotRuntime senderRuntime = runtime.botRuntimes().get(options.senderConfig().id());
            String senderName = senderRuntime != null
                    ? senderRuntime.identity().name()
                    : options.senderConfig().id();
            // 根路径（C:\、/）的 getFileName() 返回 null，直接 toString 会 NPE——
            // 回落完整 workspaceDir
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
            // 失败回收刚落账的消息，避免留下孤儿协作消息
            runtime.collaborationInbox().consume(collaboration.dispatchId(), collaboration.toBotId());
            throw error;
        }
        LOGGER.info("[协作] task={} {} -> {} round={}/{}",
                options.taskId(), options.senderConfig().id(),
                options.targetBotId(), options.round(), options.maxRounds());
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliId;
import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.BotRegistry;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.im.BotIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 Task 1：AppRuntime/BotRuntime 装配测试（轻）。
 * 对齐 TS 版 src/app/runtime.ts 的纯类型语义；并发容器口径按裁决 2 钉住。
 */
class AppRuntimeTest {

    private static BotRegistry.BotConfig config(String id) {
        return new BotRegistry.BotConfig(id, "app-" + id, "secret", CliId.CLAUDE,
                "工程师", List.of(), null, "C:/ws/" + id, 3);
    }

    // 全参构造：九字段逐一同引用装配（对齐 TS 对象字面量组装）
    @Test
    void fullConstructorAssemblesAllNineFields() {
        SessionManager sessions = new SessionManager();
        TeamRegistry teamRegistry = new TeamRegistry("leader", List.of(config("leader")));
        Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
        Map<String, Long> contextWindows = new ConcurrentHashMap<>();
        Map<String, BotRuntime> botRuntimes = new ConcurrentHashMap<>();
        Set<String> processedCollaborationTurns = ConcurrentHashMap.newKeySet();
        Collaboration.CollaborationInbox inbox = new Collaboration.CollaborationInbox();
        Clarification.ClarificationFlowStore clarificationFlows =
                new Clarification.ClarificationFlowStore();
        ProductSpec.ProductSpecFlowStore productSpecFlows = new ProductSpec.ProductSpecFlowStore();

        AppRuntime runtime = new AppRuntime(sessions, teamRegistry, activeRuns, contextWindows,
                botRuntimes, processedCollaborationTurns, inbox, clarificationFlows,
                productSpecFlows);

        assertSame(sessions, runtime.sessions());
        assertSame(teamRegistry, runtime.teamRegistry());
        assertSame(activeRuns, runtime.activeRuns());
        assertSame(contextWindows, runtime.contextWindows());
        assertSame(botRuntimes, runtime.botRuntimes());
        assertSame(processedCollaborationTurns, runtime.processedCollaborationTurns());
        assertSame(inbox, runtime.collaborationInbox());
        assertSame(clarificationFlows, runtime.clarificationFlows());
        assertSame(productSpecFlows, runtime.productSpecFlows());
    }

    // 默认构造：内存容器按裁决 2 落并发容器，store/inbox 新建
    @Test
    void defaultConstructorCreatesConcurrentContainersAndStores() {
        AppRuntime runtime = new AppRuntime(new SessionManager(),
                new TeamRegistry("leader", List.of(config("leader"))));

        assertInstanceOf(ConcurrentHashMap.class, runtime.activeRuns());
        assertInstanceOf(ConcurrentHashMap.class, runtime.contextWindows());
        assertInstanceOf(ConcurrentHashMap.class, runtime.botRuntimes());
        // newKeySet() 的实现类不承诺，用可观察行为验证：放取一致
        runtime.processedCollaborationTurns().add("turn-1");
        assertTrue(runtime.processedCollaborationTurns().contains("turn-1"));

        assertInstanceOf(Collaboration.CollaborationInbox.class, runtime.collaborationInbox());
        assertInstanceOf(Clarification.ClarificationFlowStore.class, runtime.clarificationFlows());
        assertInstanceOf(ProductSpec.ProductSpecFlowStore.class, runtime.productSpecFlows());
    }

    // BotRuntime record：三分量持有（对齐 TS interface BotRuntime）
    @Test
    void botRuntimeRecordHoldsThreeComponents() {
        BotRegistry.BotConfig config = config("worker");
        // bot 用 null 占位：record 仅持有引用，装配测试不触达 Bot 行为
        BotRuntime botRuntime = new BotRuntime(config, null, new BotIdentity("ou_x", "工人"));

        assertSame(config, botRuntime.config());
        assertEquals("ou_x", botRuntime.identity().openId());
        assertEquals("工人", botRuntime.identity().name());
    }
}

package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.ActiveRun;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.ProductSpec;
import com.leftrr.agentscheduling.core.SessionManager;
import com.leftrr.agentscheduling.core.TeamRegistry;
import com.leftrr.agentscheduling.dto.app.BotRuntime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享运行时上下文。这些容器会被多条虚拟线程并发读写，统一落并发容器：
 * activeRuns / botRuntimes / contextWindows 用 ConcurrentHashMap、
 * processedCollaborationTurns 用 ConcurrentHashMap.newKeySet()。
 */
public final class AppRuntime {
    private final SessionManager sessions;
    private final TeamRegistry teamRegistry;
    private final Map<String, ActiveRun> activeRuns;
    private final Map<String, Long> contextWindows;
    private final Map<String, BotRuntime> botRuntimes;
    private final Set<String> processedCollaborationTurns;
    private final Collaboration.CollaborationInbox collaborationInbox;
    private final Clarification.ClarificationFlowStore clarificationFlows;
    private final ProductSpec.ProductSpecFlowStore productSpecFlows;

    /** 全参构造：九字段显式装配（Main 生产装配用）。 */
    public AppRuntime(SessionManager sessions, TeamRegistry teamRegistry,
            Map<String, ActiveRun> activeRuns, Map<String, Long> contextWindows,
            Map<String, BotRuntime> botRuntimes, Set<String> processedCollaborationTurns,
            Collaboration.CollaborationInbox collaborationInbox,
            Clarification.ClarificationFlowStore clarificationFlows,
            ProductSpec.ProductSpecFlowStore productSpecFlows) {
        this.sessions = sessions;
        this.teamRegistry = teamRegistry;
        this.activeRuns = activeRuns;
        this.contextWindows = contextWindows;
        this.botRuntimes = botRuntimes;
        this.processedCollaborationTurns = processedCollaborationTurns;
        this.collaborationInbox = collaborationInbox;
        this.clarificationFlows = clarificationFlows;
        this.productSpecFlows = productSpecFlows;
    }

    /** 默认构造：内存容器统一新建并发容器，productSpecFlows 生产组装时换持久化子类。 */
    public AppRuntime(SessionManager sessions, TeamRegistry teamRegistry) {
        this(sessions, teamRegistry,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                ConcurrentHashMap.newKeySet(),
                new Collaboration.CollaborationInbox(),
                new Clarification.ClarificationFlowStore(),
                new ProductSpec.ProductSpecFlowStore());
    }

    public SessionManager sessions() {
        return sessions;
    }

    public TeamRegistry teamRegistry() {
        return teamRegistry;
    }

    public Map<String, ActiveRun> activeRuns() {
        return activeRuns;
    }

    public Map<String, Long> contextWindows() {
        return contextWindows;
    }

    public Map<String, BotRuntime> botRuntimes() {
        return botRuntimes;
    }

    public Set<String> processedCollaborationTurns() {
        return processedCollaborationTurns;
    }

    public Collaboration.CollaborationInbox collaborationInbox() {
        return collaborationInbox;
    }

    public Clarification.ClarificationFlowStore clarificationFlows() {
        return clarificationFlows;
    }

    public ProductSpec.ProductSpecFlowStore productSpecFlows() {
        return productSpecFlows;
    }
}

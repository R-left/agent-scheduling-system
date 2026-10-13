package com.leftrr.agentscheduling.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ExactLevelFilter 决策矩阵（M7 Task 5 累积裁决 b 的 logback.xml 组装依赖）。 */
class ExactLevelFilterTest {

    private static LoggingEvent event(Level level) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(ExactLevelFilterTest.class);
        return new LoggingEvent("fqcn", logger, level, "m", null, null);
    }

    private static ExactLevelFilter startedFilter(String level, FilterReply onMatch,
            FilterReply onMismatch) {
        ExactLevelFilter filter = new ExactLevelFilter();
        filter.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        filter.setLevel(level);
        filter.setOnMatch(onMatch);
        filter.setOnMismatch(onMismatch);
        filter.start();
        return filter;
    }

    @Test
    void 仅匹配配置等级_对位logbackxml两个方向() {
        // STDERR 版：仅 ERROR 通过
        ExactLevelFilter acceptError = startedFilter("ERROR",
                FilterReply.ACCEPT, FilterReply.DENY);
        assertEquals(FilterReply.ACCEPT, acceptError.decide(event(Level.ERROR)));
        assertEquals(FilterReply.DENY, acceptError.decide(event(Level.WARN)));
        assertEquals(FilterReply.DENY, acceptError.decide(event(Level.INFO)));

        // STDOUT 版：仅剔除 ERROR
        ExactLevelFilter denyError = startedFilter("ERROR",
                FilterReply.DENY, FilterReply.ACCEPT);
        assertEquals(FilterReply.DENY, denyError.decide(event(Level.ERROR)));
        assertEquals(FilterReply.ACCEPT, denyError.decide(event(Level.WARN)));
        assertEquals(FilterReply.ACCEPT, denyError.decide(event(Level.INFO)));
    }

    @Test
    void 未启动或未配置level_恒NEUTRAL() {
        ExactLevelFilter notStarted = new ExactLevelFilter();
        notStarted.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        notStarted.setLevel("ERROR");
        assertEquals(FilterReply.NEUTRAL, notStarted.decide(event(Level.ERROR)));

        // level 未配置 → start() 拒绝启动 → decide NEUTRAL（logback Filter 生命周期约定）
        ExactLevelFilter noLevel = new ExactLevelFilter();
        noLevel.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        noLevel.setOnMatch(FilterReply.ACCEPT);
        noLevel.setOnMismatch(FilterReply.DENY);
        noLevel.start();
        assertEquals(FilterReply.NEUTRAL, noLevel.decide(event(Level.ERROR)));
    }
}

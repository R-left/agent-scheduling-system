package com.leftrr.agentscheduling.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.AbstractMatcherFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 只匹配某个精确等级的过滤器：等于配置等级的走 onMatch，其余走 onMismatch。
 *
 * <p>为什么要有它：要 console.error 走 STDERR、console.log 留在 STDOUT，同一 logger
 * 挂两个 appender 即可（STDOUT 版 DENY error，STDERR 版只 ACCEPT error）。
 * logback 内置 LevelFilter 只会「仅放行某等级」，表达不了「仅剔除某等级」，所以补这个。
 * marker 要侵入所有日志调用点、TurboFilter 改不了 appender 路由，都不合适。</p>
 */
public final class ExactLevelFilter extends AbstractMatcherFilter<ILoggingEvent> {

    private Level level;

    /**
     * joran 注入等级名。不能用 Level.toLevel 的默认值兜底——拼错会静默变 DEBUG，
     * 这里显式 addError 并留 null，让 start() 拒绝启动，配置错误别悄悄改路由。
     */
    public void setLevel(String levelName) {
        this.level = Level.toLevel(levelName, null);
        if (this.level == null) {
            addError("未知等级名: " + levelName + "，过滤器拒绝启动");
        }
    }

    @Override
    public void start() {
        if (this.level == null) {
            addError("level 未配置，过滤器拒绝启动");
            return;
        }
        super.start();
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!isStarted()) {
            return FilterReply.NEUTRAL;
        }
        // logback 标准等级为单例常量，同一 Level 实例间 == 成立
        return event.getLevel() == this.level ? getOnMatch() : getOnMismatch();
    }
}

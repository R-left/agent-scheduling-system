package com.leftrr.agentscheduling.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.AbstractMatcherFilter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 精确等级过滤器：仅按「事件等级 == 配置等级」判定 ACCEPT/DENY，其余走 onMismatch。
 *
 * <p>用途（M7 Task 5 累积裁决 b，Task 2 审查 concern ⑦）：TS 侧 orchestrator/scheduler
 * 的 console.error 需对位到 STDERR，而 console.log 保持在 STDOUT——这两个类的日志以
 * info 为主、error 为辅，logger 级 appender（additivity=false）会把 info/warn 一并
 * 吞进同一出口或整类静默，均不可接受。方案评估：</p>
 * <ul>
 * <li>marker：需要逐个 error 日志点加标记，侵入全部业务调用点，否决；</li>
 * <li>TurboFilter：全局链上只能 ACCEPT/DENY/NEUTRAL，无法把事件改道另一 appender，否决；</li>
 * <li>按 appender 挂 Filter（本类）：logback 原生扩展点，零业务侵入——同一 logger 挂
 *     两个 appender（STDOUT 版 DENY error / STDERR 版仅 ACCEPT error），logback.xml
 *     组装即可。logback 内置 LevelFilter 已能表达「仅 ACCEPT 某等级」（onMismatch=DENY），
 *     但表达不了反向「仅剔除某等级」（STDOUT 版），故补本类统一两个方向的写法。</li>
 * </ul>
 *
 * <p>配置方式（logback.xml，joran 属性注入）：{@code <level>} 收字符串经
 * {@link Level#toLevel(String)} 解析（未配置时 start() 拒绝启动，decide 恒 NEUTRAL，
 * 与 logback Filter 生命周期约定一致）；{@code <onMatch>/<onMismatch>} 由 joran 按
 * 枚举名直填 {@link FilterReply}（ACCEPT/DENY/NEUTRAL）。</p>
 */
public final class ExactLevelFilter extends AbstractMatcherFilter<ILoggingEvent> {

    private Level level;

    /** joran 注入：等级名（ERROR/WARN/INFO/…），经 Level.toLevel 解析。 */
    public void setLevel(String levelName) {
        this.level = Level.toLevel(levelName);
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

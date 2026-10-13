package com.leftrr.agentscheduling.app;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M7 Task 0（裁决 2/R12）测试助手：捕获指定类 logger 的 SLF4J 事件消息。
 *
 * <p>切换备案：被切锚点的日志改经 logback 输出，而 ConsoleAppender 在
 * {@code start()} 时即缓存 System.out/err 流引用（logback-core 1.6.5
 * ConsoleAppender#start → target.getStream()），测试的 System.setOut/setErr
 * 替换发生在其之后、捕获不到——故改在 logger 上挂记录 appender 截获事件，
 * 断言 {@code getFormattedMessage()}（即 {} 占位展开后的消息体）文案逐字，
 * 与切换前 System.out/err 的消息体保持同一断言文本。</p>
 */
final class LogbackCapture extends AppenderBase<ILoggingEvent> {

    private final List<String> messages = new ArrayList<>();
    private Logger logger;

    private LogbackCapture() {}

    /** 在指定类对应 logger 上挂捕获 appender；用完必须调 {@link #detach()}。 */
    static LogbackCapture attach(Class<?> loggerClass) {
        LogbackCapture capture = new LogbackCapture();
        capture.logger = (Logger) LoggerFactory.getLogger(loggerClass);
        capture.setContext(capture.logger.getLoggerContext());
        capture.logger.addAppender(capture);
        capture.start();
        return capture;
    }

    void detach() {
        logger.detachAppender(this);
        stop();
    }

    /** 捕获到的格式化消息体（占位符已展开），与 SLF4J 调用顺序一致。 */
    List<String> messages() {
        return messages;
    }

    /** 断言恰好一条消息并返回其内容（对位切换前 stdout.strip() 全量等值断言）。 */
    String singleMessage() {
        assertEquals(1, messages.size(), "应恰好一条日志，实际: " + messages);
        return messages.get(0);
    }

    @Override
    protected void append(ILoggingEvent event) {
        messages.add(event.getFormattedMessage());
    }
}

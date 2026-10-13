package com.leftrr.agentscheduling;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main 装配测试的日志捕获（app 包 LogbackCapture 的同构副本——该类包私有且
 * Main 测试位于根包，不可跨包复用；捕获语义与断言口径一致）。
 */
final class MainLogCapture extends AppenderBase<ILoggingEvent> {

    private final List<String> messages = new ArrayList<>();
    private Logger logger;

    private MainLogCapture() {}

    static MainLogCapture attach(Class<?> loggerClass) {
        MainLogCapture capture = new MainLogCapture();
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

    List<String> messages() {
        return messages;
    }

    @Override
    protected void append(ILoggingEvent event) {
        messages.add(event.getFormattedMessage());
    }
}

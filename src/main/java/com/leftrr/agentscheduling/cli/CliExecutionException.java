package com.leftrr.agentscheduling.cli;

import java.io.IOException;

/** 对齐 runCli/compact 的失败路径（TS Promise reject → Java 受检 IOException）。 */
public class CliExecutionException extends IOException {
    public CliExecutionException(String message) {
        super(message);
    }
}

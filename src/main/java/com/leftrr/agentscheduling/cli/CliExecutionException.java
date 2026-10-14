package com.leftrr.agentscheduling.cli;

import java.io.IOException;

/** runCli / compact 执行失败（受检 IOException）。 */
public class CliExecutionException extends IOException {
    public CliExecutionException(String message) {
        super(message);
    }
}

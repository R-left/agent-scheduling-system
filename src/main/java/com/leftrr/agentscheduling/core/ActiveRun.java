package com.leftrr.agentscheduling.core;

/**
 * 一次执行中的任务。对应 TS 版 src/core/task-abort.ts 的 ActiveRun。
 * TS 版会在 abort 前原地改写 cancelMode，故此处为可变类而非 record。
 */
public final class ActiveRun {
    private final CancellationToken token;
    private final String ownerOpenId;
    private volatile CancelMode cancelMode;

    public ActiveRun(CancellationToken token, String ownerOpenId) {
        this.token = token;
        this.ownerOpenId = ownerOpenId;
    }

    public CancellationToken token() {
        return token;
    }

    public String ownerOpenId() {
        return ownerOpenId;
    }

    public CancelMode cancelMode() {
        return cancelMode;
    }

    public void setCancelMode(CancelMode cancelMode) {
        this.cancelMode = cancelMode;
    }
}

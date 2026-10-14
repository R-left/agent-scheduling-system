package com.leftrr.agentscheduling.core;

import com.leftrr.agentscheduling.dto.core.CancelMode;

/**
 * 一次执行中的任务。abort 前会原地改写 cancelMode，故为可变类而非 record。
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

    /**
     * 坑：cancelMode 要等 token.cancel() 触发的全部回调跑完才可见，
     * onCancel 里读这里恒为 null，回调内别依赖它。
     */
    public CancelMode cancelMode() {
        return cancelMode;
    }

    public void setCancelMode(CancelMode cancelMode) {
        this.cancelMode = cancelMode;
    }
}

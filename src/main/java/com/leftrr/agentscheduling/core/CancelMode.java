package com.leftrr.agentscheduling.core;

/** 取消语义：普通停止 / 停止并关闭会话。对应 TS 版 cancelMode: 'stop' | 'close'。 */
public enum CancelMode {
    STOP,
    CLOSE
}

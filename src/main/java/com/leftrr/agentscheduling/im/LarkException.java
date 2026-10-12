package com.leftrr.agentscheduling.im;

/** 飞书业务失败（requireSuccess 抛出）与飞书调用异常。对齐 TS 版 lark.ts 的 Error 抛出。 */
public class LarkException extends RuntimeException {

    public LarkException(String message) {
        super(message);
    }

    public LarkException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.leftrr.agentscheduling.core;

/**
 * 产品方案交付模式。对应 TS 版 src/core/bot-registry.ts 的 z.enum(['local', 'lark-doc'])。
 * 小写标识串用于错误消息与未来 JSON 序列化，与 TS 版一致。
 */
public enum ProductDeliveryMode {
    LOCAL("local"),
    LARK_DOC("lark-doc");

    private final String id;

    ProductDeliveryMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 未知值返回 null（调用方负责报错，对齐 zod 判失败）。 */
    public static ProductDeliveryMode fromId(String value) {
        for (ProductDeliveryMode mode : values()) {
            if (mode.id.equals(value)) return mode;
        }
        return null;
    }
}

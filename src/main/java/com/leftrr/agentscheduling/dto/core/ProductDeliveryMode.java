package com.leftrr.agentscheduling.dto.core;

/**
 * 产品方案交付模式。
 * 小写标识串用于错误消息与未来 JSON 序列化。
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

    /** 未知值返回 null（调用方负责报错）。 */
    public static ProductDeliveryMode fromId(String value) {
        for (ProductDeliveryMode mode : values()) {
            if (mode.id.equals(value)) return mode;
        }
        return null;
    }
}

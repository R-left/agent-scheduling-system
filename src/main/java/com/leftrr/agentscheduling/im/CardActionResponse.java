package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 对齐 TS 版 src/im/lark.ts:74-77 的 CardActionResponse。
 *
 * @param toast TS toast?: { type, content }，缺失为 null
 * @param card  TS card?: { type: 'raw', data: CardJson } 的 CardJson 子树
 *              （type 包裹由 FeishuBots 转 SDK 响应模型时补上），缺失为 null
 */
public record CardActionResponse(Toast toast, JsonNode card) {

    /** TS toast 的 { type, content }。 */
    public record Toast(ToastType type, String content) {
    }

    /** TS 'success' | 'info' | 'warning' | 'error' 字面量 → id。 */
    public enum ToastType {
        SUCCESS("success"),
        INFO("info"),
        WARNING("warning"),
        ERROR("error");

        private final String id;

        ToastType(String id) {
            this.id = id;
        }

        /** 对齐 TS 字面量（brief 用例 9）。 */
        public String id() {
            return id;
        }
    }
}

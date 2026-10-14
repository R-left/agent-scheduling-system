package com.leftrr.agentscheduling.dto.im;

import com.fasterxml.jackson.databind.JsonNode;
/**
 * 卡片回调的响应体。
 *
 * @param toast 可选的 toast 提示，缺失为 null
 * @param card  可选的更新后卡片 JSON 子树
 *              （type 包裹由 FeishuBots 转 SDK 响应模型时补上），缺失为 null
 */
public record CardActionResponse(Toast toast, JsonNode card) {

    /** toast 的 { type, content } 结构。 */
    public record Toast(ToastType type, String content) {
    }

    /** toast 类型字面量 → id。 */
    public enum ToastType {
        SUCCESS("success"),
        INFO("info"),
        WARNING("warning"),
        ERROR("error");

        private final String id;

        ToastType(String id) {
            this.id = id;
        }

        /** 返回飞书 API 要求的字面量 id。 */
        public String id() {
            return id;
        }
    }
}

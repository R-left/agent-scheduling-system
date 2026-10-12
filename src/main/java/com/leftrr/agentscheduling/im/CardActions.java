package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * 卡片动作解析。对齐 TS 版 src/im/lark.ts:79-89 的 parseCardAction。
 */
public final class CardActions {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private CardActions() {
    }

    /**
     * 对齐 TS isRecord（typeof value === 'object' &amp;&amp; value !== null）：
     * 数组也算 object、会通过（brief 用例 4 钉住）；null 与标量 → 空对象。
     */
    private static JsonNode asRecordOrEmpty(JsonNode node) {
        if (node.isObject() || node.isArray()) {
            return node;
        }
        return FACTORY.objectNode();
    }

    /**
     * 近似 TS `data?.operator?.{field} ?? data?.operator_id?.{field} ?? ''`（?? 链）。
     * 差异（T2②）：TS ?? 仅在 null/undefined 时回退——operator 存在但 open_id 为空串时
     * TS 直接返回空串、不再看 operator_id；本实现把缺失/null 渲染出的 "" 也视作回退信号
     * （brief Step 2 取舍），空串时会多走一档 operator_id 回退。
     */
    private static String firstText(JsonNode operator, String field, JsonNode operatorId) {
        String primary = operator.path(field).asText("");
        if (!primary.isEmpty()) {
            return primary;
        }
        return operatorId.path(field).asText("");
    }

    /** 对齐 lark.ts:79-89。 */
    public static CardAction parseCardAction(JsonNode data) {
        // T2①：TS data?.x 可选链对 null data 吞成 undefined，与空对象同果；Java .path() 会 NPE
        if (data == null) {
            data = FACTORY.objectNode();
        }
        JsonNode action = data.path("action");
        String messageId = data.path("context").path("open_message_id").asText("");
        if (messageId.isEmpty()) {
            // TS 第 86 行 data?.context?.open_message_id ?? data?.open_message_id ?? ''
            messageId = data.path("open_message_id").asText("");
        }
        return new CardAction(
                firstText(data.path("operator"), "open_id", data.path("operator_id")),
                firstText(data.path("operator"), "union_id", data.path("operator_id")),
                messageId,
                asRecordOrEmpty(action.path("value")),
                asRecordOrEmpty(action.path("form_value")));
    }
}

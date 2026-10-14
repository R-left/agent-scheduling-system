package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.leftrr.agentscheduling.dto.im.CardAction;

/**
 * 卡片动作解析。
 */
public final class CardActions {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private CardActions() {
    }

    /**
     * 对象或数组原样返回；null 与标量 → 空对象。
     */
    private static JsonNode asRecordOrEmpty(JsonNode node) {
        if (node.isObject() || node.isArray()) {
            return node;
        }
        return FACTORY.objectNode();
    }

    /**
     * 取 operator.field，缺失或空串时回退 operator_id.field。
     */
    private static String firstText(JsonNode operator, String field, JsonNode operatorId) {
        String primary = operator.path(field).asText("");
        if (!primary.isEmpty()) {
            return primary;
        }
        return operatorId.path(field).asText("");
    }

    public static CardAction parseCardAction(JsonNode data) {
        if (data == null) {
            data = FACTORY.objectNode();
        }
        JsonNode action = data.path("action");
        String messageId = data.path("context").path("open_message_id").asText("");
        if (messageId.isEmpty()) {
            // 兜底：有的回调把 open_message_id 放在顶层
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

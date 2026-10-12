package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐 TS 版 src/im/lark.ts:79-89 的 parseCardAction（M4 Task 2 brief Step 1，
 * 用例编号 1-5 与 brief 一一对应）。
 */
class CardActionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void case01_fullDataParsesAllFiveFields() {
        JsonNode data = json("""
                {"operator":{"open_id":"ou_1","union_id":"on_1"},
                 "context":{"open_message_id":"om_1"},
                 "action":{"value":{"k":"v"},"form_value":{"f":"1"}}}""");
        CardAction action = CardActions.parseCardAction(data);
        assertEquals("ou_1", action.operatorOpenId());
        assertEquals("on_1", action.operatorUnionId());
        assertEquals("om_1", action.messageId());
        assertEquals(json("{\"k\":\"v\"}"), action.value());
        assertEquals(json("{\"f\":\"1\"}"), action.formValue());
    }

    @Test
    void case02_missingOperatorFallsBackToOperatorId() {
        // card.action 事件两种形态：operator 缺失时回退 operator_id（TS ?? 链）
        JsonNode data = json("""
                {"operator_id":{"open_id":"ou_2","union_id":"on_2"},
                 "action":{"value":{},"form_value":{}}}""");
        CardAction action = CardActions.parseCardAction(data);
        assertEquals("ou_2", action.operatorOpenId());
        assertEquals("on_2", action.operatorUnionId());
    }

    @Test
    void case03_missingContextFallsBackToTopLevelMessageId() {
        // TS data?.context?.open_message_id ?? data?.open_message_id
        JsonNode data = json("""
                {"open_message_id":"om_top",
                 "action":{"value":{},"form_value":{}}}""");
        assertEquals("om_top", CardActions.parseCardAction(data).messageId());
    }

    @Test
    void case04_valueArrayPassesThroughStringAndNullYieldEmptyObject() {
        // TS isRecord：typeof []==='object' 且 !== null → 数组通过 isRecord，原样保留
        JsonNode arrayValue = json("""
                {"action":{"value":[1,2],"form_value":{}}}""");
        assertEquals(json("[1,2]"), CardActions.parseCardAction(arrayValue).value());

        // 字符串 → 空对象
        JsonNode stringValue = json("""
                {"action":{"value":"x","form_value":{}}}""");
        assertEquals(json("{}"), CardActions.parseCardAction(stringValue).value());

        // null → 空对象
        JsonNode nullValue = json("""
                {"action":{"value":null,"form_value":{}}}""");
        assertEquals(json("{}"), CardActions.parseCardAction(nullValue).value());
    }

    @Test
    void case05_emptyDataYieldsEmptyStringsAndEmptyObjects() {
        CardAction action = CardActions.parseCardAction(json("{}"));
        assertEquals("", action.operatorOpenId());
        assertEquals("", action.operatorUnionId());
        assertEquals("", action.messageId());
        assertTrue(action.value().isObject() && action.value().isEmpty());
        assertTrue(action.formValue().isObject() && action.formValue().isEmpty());
    }
}

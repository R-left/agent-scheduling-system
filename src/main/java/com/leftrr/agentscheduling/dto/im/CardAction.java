package com.leftrr.agentscheduling.dto.im;

import com.fasterxml.jackson.databind.JsonNode;
/**
 * 飞书卡片回调中的按钮动作信息。
 * value / formValue 为 JSON 对象或数组（见 CardActions）。
 */
public record CardAction(String operatorOpenId, String operatorUnionId, String messageId,
                         JsonNode value, JsonNode formValue) {
}

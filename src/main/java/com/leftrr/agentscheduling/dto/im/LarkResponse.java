package com.leftrr.agentscheduling.dto.im;

import com.fasterxml.jackson.databind.JsonNode;
/**
 * 飞书 REST 响应信封（code/msg/data）。
 * code 为 null 表示响应没带 code 字段，requireSuccess 按成功处理。
 */
public record LarkResponse(Integer code, String msg, JsonNode data) {
}

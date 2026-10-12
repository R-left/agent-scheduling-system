package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 对齐 TS 版 src/im/lark.ts 飞书 REST 响应信封（code/msg/data）。
 * code 为 null 表示响应缺失 code 字段（TS undefined），requireSuccess 按成功处理
 * （M4 Task 9 Review Focus 4：truthy 判定 code != null && code != 0）。
 */
public record LarkResponse(Integer code, String msg, JsonNode data) {
}

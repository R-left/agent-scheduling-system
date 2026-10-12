package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 对齐 TS 版 src/im/lark.ts:66-72 的 CardAction。
 * value / formValue 为 TS Record&lt;string, unknown&gt; → JsonNode（对象或数组，见 CardActions）。
 */
public record CardAction(String operatorOpenId, String operatorUnionId, String messageId,
                         JsonNode value, JsonNode formValue) {
}

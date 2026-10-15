package com.leftrr.agentscheduling.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.core.Clarification;
import com.leftrr.agentscheduling.core.Collaboration;
import com.leftrr.agentscheduling.core.ProductSpec;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * 工具定义
 */
public final class ToolDefinitions {

    /**
     * 单个工具定义内部类
     * @param name
     * @param claudeToolName
     * @param title
     * @param description
     * @param inputSchema
     * @param rejection
     * @param receipt
     */
    public record ToolDefinition(
            String name,
            String claudeToolName,
            String title,
            String description,
            String inputSchema,
            /* 复校验失败回执*/
            String rejection,
            /* 正常回执 */
            String receipt,
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {}
    private static final ObjectMapper JACKSON = new ObjectMapper();
    private ToolDefinitions() {}

    public static final ToolDefinitions.ToolDefinition CLARIFICATION = new ToolDefinition(
        "request_clarification",
        "mcp__agent_scheduling__request_clarification",
        "向用户提问",
        "当产品需求仍有会实质影响方案的歧义时，调用此工具向用户展示飞书问题卡片。"
                + "一次最多提交 5 个问题，每题提供 2 到 4 个清晰选项。"
                + "提交后不要自行补全用户答案，本轮回复可以简短收束。",
        """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "minLength": 1,
              "maxLength": 80,
              "default": "需求澄清"
            },
            "intro": {
              "type": "string",
              "maxLength": 300,
              "default": ""
            },
            "questions": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "id": {
                    "type": "string",
                    "pattern": "^[a-z0-9_-]{1,32}$"
                  },
                  "prompt": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": 300
                  },
                  "options": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "id": {
                          "type": "string",
                          "pattern": "^[a-z0-9_-]{1,32}$"
                        },
                        "label": {
                          "type": "string",
                          "minLength": 1,
                          "maxLength": 100
                        }
                      },
                      "required": ["id", "label"]
                    },
                    "minItems": 2,
                    "maxItems": 4
                  },
                  "recommendedOptionId": {
                    "type": "string",
                    "pattern": "^[a-z0-9_-]{1,32}$"
                  }
                },
                "required": ["id", "prompt", "options"]
              },
              "minItems": 1,
              "maxItems": 5
            }
          },
          "required": ["questions"]
        }""",
        "入参未通过 Agent OS 校验，本次 request_clarification 调用已被拒绝。",
        "已把 {count} 个问题交给 Agent OS，请等待用户回答。",
        (exchange, request) -> {
            JsonNode input = toCoreJsonNode(request);
            Clarification.ClarificationRequest parsed = Clarification.parseRequest(input);
            if (parsed == null) {
                return rejection(ToolDefinitions.CLARIFICATION.rejection());
            }
            int count = input.path("questions").size();
            return McpSchema.CallToolResult.builder()
                    .addTextContent(ToolDefinitions.CLARIFICATION.receipt()
                            .replace("{count}", String.valueOf(count)))
                    .build();
        });

    public static final ToolDefinitions.ToolDefinition PRODUCT_SPEC = new ToolDefinition(
        "request_spec_approval",
        "mcp__agent_scheduling__request_spec_approval",
        "提交产品文档",
        "产品方案已经生成后，调用此工具提交唯一的待确认产物。"
                + "deliveryMode=local 时提交 specPath 与 ticketsPath，并确保文件真实存在。"
                + "deliveryMode=lark-doc 时只提交 documentUrl，且必须使用 lark-doc 创建或更新成功结果中的 document.url；"
                + "该文档必须同时包含产品说明与「实现任务（Tickets）」章节。"
                + "同一份方案不要同时维护本地 Markdown 和飞书云文档，避免两个来源互相覆盖。"
                + "summary 只写便于快速了解方案的摘要，完整内容保留在所选产物中。"
                + "提交前必须完成需求澄清，确保这份方案已经可以确认。"
                + "调用后停止工作，不要实现代码或委派团队成员。",
        """
        {
          "type": "object",
          "anyOf": [
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 80
                },
                "summary": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 500
                },
                "deliveryMode": {
                  "type": "string",
                  "const": "local"
                },
                "specPath": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 240
                },
                "ticketsPath": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 240
                }
              },
              "required": ["title", "summary", "deliveryMode", "specPath", "ticketsPath"],
              "additionalProperties": false
            },
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 80
                },
                "summary": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 500
                },
                "deliveryMode": {
                  "type": "string",
                  "const": "lark-doc"
                },
                "documentUrl": {
                  "type": "string",
                  "format": "uri"
                }
              },
              "required": ["title", "summary", "deliveryMode", "documentUrl"],
              "additionalProperties": false
            }
          ],
          "additionalProperties": false
        }""",
        "入参未通过 Agent OS 校验，本次 request_spec_approval 调用已被拒绝。",
        "唯一的产品方案产物已交给 Agent OS，等待用户查看。",
        (exchange, request) -> ProductSpec.parseRequest(toCoreJsonNode(request)) == null
                ? rejection(ToolDefinitions.PRODUCT_SPEC.rejection())
                : McpSchema.CallToolResult.builder()
                .addTextContent(ToolDefinitions.PRODUCT_SPEC.receipt())
                .build());

    public static final ToolDefinitions.ToolDefinition DISPATCH_TASK = new ToolDefinition(
        "dispatch_task",
        "mcp__agent_scheduling__dispatch_task",
        "把任务交给团队成员",
        "把任务确定性地交给一名已注册的长期团队成员。"
                + "只有 CEO 助理可以在运行时调用；产品经理和开发者调用会被拒绝。"
                + "targetBotId 必须是团队名单中的成员 id，不能填写自己。"
                + "objective 写协作目标，instruction 写交给对方的完整要求，expectedOutput 写期望产出。"
                + "调用后停止工作，等待对方完成并把结果交回。",
        """
        {
          "type": "object",
          "properties": {
            "targetBotId": {
              "type": "string",
              "pattern": "^[a-z0-9][a-z0-9_-]{0,31}$"
            },
            "objective": {
              "type": "string",
              "minLength": 1,
              "maxLength": 200
            },
            "instruction": {
              "type": "string",
              "minLength": 1,
              "maxLength": 2000
            },
            "expectedOutput": {
              "type": "string",
              "minLength": 1,
              "maxLength": 500
            }
          },
          "required": ["targetBotId", "objective", "instruction"]
        }""",
        "入参未通过 Agent OS 校验，本次 dispatch_task 调用已被拒绝。",
        "派发请求已交给 Agent OS，等待协作任务送达目标成员。",
        (exchange, request) -> Collaboration.parseDispatchTaskRequest(toCoreJsonNode(request)) == null
                ? rejection(ToolDefinitions.DISPATCH_TASK.rejection())
                : McpSchema.CallToolResult.builder()
                .addTextContent(ToolDefinitions.DISPATCH_TASK.receipt())
                .build());

    /**
     * 校验失败响应
     *
     * @param message
     * @return
     */
    private static McpSchema.CallToolResult rejection(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(Boolean.TRUE)
                .addTextContent(message)
                .build();
    }


    private static JsonNode toCoreJsonNode(McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        return arguments == null ? null : JACKSON.valueToTree(arguments);
    }
}

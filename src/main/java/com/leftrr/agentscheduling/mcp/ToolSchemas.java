package com.leftrr.agentscheduling.mcp;

/**
 * 三工具的 JSON Schema 常量（规范 JSON 文本，M5 设计裁决 5：字符串常量 + 黄金断言锚定，
 * 不用 Map 逐键构造）。
 *
 * <p>结构层逐字段对齐 TS zod schema（M5 设计裁决 1）：</p>
 * <ul>
 *   <li>类型 / minLength / maxLength / pattern / minItems / maxItems / const / format；</li>
 *   <li>zod default 字段（title/intro）保留 default 值且不进 required，optional 字段不进 required；</li>
 *   <li>trim() 无 JSON Schema 表示，不落键；</li>
 *   <li>discriminatedUnion → {@code anyOf} 两分支，各分支 {@code const} 判别字面量 +
 *       {@code additionalProperties:false}（对齐 product-spec.ts 的 .strict()）；
 *       非 strict 的 z.object（clarification / dispatch）不落 additionalProperties，对齐 TS
 *       zod→JSON Schema 产物；</li>
 *   <li>superRefine / refine 的语义校验（澄清问题与选项 ID 去重、推荐项指向、文档路径不出工作区、
 *       documentUrl 含 /docx/）无法用 JSON Schema 表达，不进 schema——由
 *       {@link AppToolsServer} handler 内 core {@code parseXxx(JsonNode)} 复校验。</li>
 * </ul>
 *
 * <p>properties 与 required 的键序逐字段对齐 TS zod 字段声明序（AppToolsServerTest 黄金断言锚定）。</p>
 */
public final class ToolSchemas {

    /** 对齐 TS ClarificationRequestSchema（src/core/clarification.ts 25-34）。 */
    public static final String CLARIFICATION_INPUT_SCHEMA = """
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
            }""";

    /** 对齐 TS ProductSpecRequestSchema（src/core/product-spec.ts 14-38）：discriminatedUnion → anyOf。 */
    public static final String PRODUCT_SPEC_INPUT_SCHEMA = """
            {
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
              ]
            }""";

    /** 对齐 TS DispatchTaskRequestSchema（src/core/collaboration.ts 27-32）。 */
    public static final String DISPATCH_TASK_INPUT_SCHEMA = """
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
            }""";

    private ToolSchemas() {}
}

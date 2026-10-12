package com.leftrr.agentscheduling.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 澄清流程测试夹具（M5 修复波次）：request_clarification 入参构造辅助。
 *
 * <p>原 {@link AppToolsServerTest} 与 {@link StdioSessionTest} 各持一份逐字重复的
 * question() / option() / clarifyArgs()，统一收拢于此（包级可见），测试语义不变。</p>
 */
final class McpTestFixtures {

    private McpTestFixtures() {
    }

    static Map<String, Object> option(String id, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        return option;
    }

    static Map<String, Object> question(String id) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", id);
        question.put("prompt", "方向？");
        question.put("options", List.of(option("opt_a", "方案 A"), option("opt_b", "方案 B")));
        return question;
    }

    /** 组装 request_clarification 的 questions 变长入参（泛型 varargs，签名内不写数组元素）。 */
    @SafeVarargs
    static Map<String, Object> clarifyArgs(Map<String, Object>... questions) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("questions", List.of(questions));
        return args;
    }
}

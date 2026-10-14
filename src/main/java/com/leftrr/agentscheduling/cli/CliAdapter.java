package com.leftrr.agentscheduling.cli;

import com.leftrr.agentscheduling.dto.cli.CliCompactPlan;
import com.leftrr.agentscheduling.dto.cli.CliEvent;
import com.leftrr.agentscheduling.dto.cli.CliId;
import java.util.List;

/** CLI 执行引擎适配器接口。parseEvents 对无法解析的行返回空列表，绝不抛异常。 */
public interface CliAdapter {
    CliId id();
    String command();
    String displayName();
    List<String> buildArgs(String prompt, CliPromptInput promptInput);
    List<String> buildResumeArgs(String prompt, String sessionId, CliPromptInput promptInput);
    /** instructions 为 null/空白时使用默认 /compact 指令（各适配器自行裁剪）。 */
    CliCompactPlan buildCompactPlan(String sessionId, String instructions);
    List<CliEvent> parseEvents(String line);
}

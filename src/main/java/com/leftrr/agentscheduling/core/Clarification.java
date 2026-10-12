package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.JsText;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 需求澄清流程。对应 TS 版 src/core/clarification.ts。
 * zod schema 用 JsonNode 手工校验表达（设计裁决 1）：safeParse → parseRequest 返回 null 不抛。
 * TS superRefine 的自定义消息（'同一道问题的选项 ID 不能重复'、'同一份澄清请求的问题 ID 不能重复'、
 * '推荐项必须指向当前问题中的选项'）在 TS 运行时经 safeParse 被丢弃、不外显，Java 侧只复刻判定，
 * 消息保留于注释备查。
 */
public final class Clarification {
    public static final String CLARIFICATION_TOOL_NAME = "request_clarification";
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private Clarification() {}

    public record Option(String id, String label) {}

    /** recommendedOptionId null 表示可选缺省。 */
    public record Question(String id, String prompt, List<Option> options, String recommendedOptionId) {}

    /** 对齐 ClarificationRequest（z.infer，含 default 填充后的形态）。 */
    public record ClarificationRequest(String title, String intro, List<Question> questions) {}

    public enum AnswerSource {
        USER("user"),
        AGENT("agent");

        private final String id;

        AnswerSource(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record ClarificationAnswer(String questionId, String prompt, String answer, AnswerSource source) {}

    /** 对齐 CreateClarificationFlowOptions；ownerUnionId/cardMessageId/collaboration null 表示 undefined。 */
    public record CreateFlowOptions(
            String taskId,
            String botId,
            String sessionId,
            String ownerOpenId,
            String ownerUnionId,
            Collaboration.CollaborationOrigin collaboration,
            String originalMessageId,
            String cardMessageId,
            boolean replyInThread,
            ClarificationRequest request) {}

    /** 对齐 answer()/answerWithRecommendation() 的返回形态。 */
    public record AnswerResult(ClarificationFlow flow, boolean complete) {}

    /** 可变流程对象（answers/currentIndex 原地推进，对齐 TS）。仅由本文件内的 store 操作可变字段。 */
    public static final class ClarificationFlow {
        private final String token;
        private final String taskId;
        private final String botId;
        private final String sessionId;
        private final String ownerOpenId;
        private final String ownerUnionId;
        private final Collaboration.CollaborationOrigin collaboration;
        private final String originalMessageId;
        private final String cardMessageId;
        private final boolean replyInThread;
        private final ClarificationRequest request;
        private int currentIndex;
        private final List<ClarificationAnswer> answers = new ArrayList<>();

        ClarificationFlow(String token, CreateFlowOptions options) {
            this.token = token;
            this.taskId = options.taskId();
            this.botId = options.botId();
            this.sessionId = options.sessionId();
            this.ownerOpenId = options.ownerOpenId();
            this.ownerUnionId = options.ownerUnionId();
            this.collaboration = options.collaboration();
            this.originalMessageId = options.originalMessageId();
            this.cardMessageId = options.cardMessageId();
            this.replyInThread = options.replyInThread();
            this.request = options.request();
        }

        public String token() { return token; }
        public String taskId() { return taskId; }
        public String botId() { return botId; }
        public String sessionId() { return sessionId; }
        public String ownerOpenId() { return ownerOpenId; }
        public String ownerUnionId() { return ownerUnionId; }
        public Collaboration.CollaborationOrigin collaboration() { return collaboration; }
        public String originalMessageId() { return originalMessageId; }
        public String cardMessageId() { return cardMessageId; }
        public boolean replyInThread() { return replyInThread; }
        public ClarificationRequest request() { return request; }
        public int currentIndex() { return currentIndex; }
        public List<ClarificationAnswer> answers() { return List.copyOf(answers); }
    }

    /** 对齐 ClarificationRequestSchema.safeParse（含 zod default 与 superRefine 判定）。 */
    public static ClarificationRequest parseRequest(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        String title;
        JsonNode titleNode = input.get("title");
        if (titleNode == null) {
            title = "需求澄清"; // zod default
        } else {
            title = trimmedText(titleNode);
            if (title == null || title.isEmpty() || title.length() > 80) {
                return null;
            }
        }
        String intro = "";
        if (input.has("intro")) {
            intro = trimmedText(input.get("intro"));
            if (intro == null || intro.length() > 300) {
                return null;
            }
        }
        JsonNode questionsNode = input.get("questions");
        if (questionsNode == null || !questionsNode.isArray()
                || questionsNode.size() < 1 || questionsNode.size() > 5) {
            return null;
        }
        List<Question> questions = new ArrayList<>();
        for (JsonNode questionNode : questionsNode) {
            Question question = parseQuestion(questionNode);
            if (question == null) {
                return null;
            }
            questions.add(question);
        }
        Set<String> questionIds = new LinkedHashSet<>();
        for (Question question : questions) {
            if (!questionIds.add(question.id())) {
                return null; // '同一份澄清请求的问题 ID 不能重复'
            }
        }
        return new ClarificationRequest(title, intro, List.copyOf(questions));
    }

    private static Question parseQuestion(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode idNode = node.get("id");
        String id = idNode != null && idNode.isTextual() && ID_PATTERN.matcher(idNode.asText()).matches()
                ? idNode.asText()
                : null;
        String prompt = trimmedText(node.get("prompt"));
        if (id == null || prompt == null || prompt.isEmpty() || prompt.length() > 300) {
            return null;
        }
        JsonNode optionsNode = node.get("options");
        if (optionsNode == null || !optionsNode.isArray()
                || optionsNode.size() < 2 || optionsNode.size() > 4) {
            return null;
        }
        List<Option> options = new ArrayList<>();
        for (JsonNode optionNode : optionsNode) {
            if (optionNode == null || !optionNode.isObject()) {
                return null;
            }
            JsonNode optionIdNode = optionNode.get("id");
            String optionId = optionIdNode != null && optionIdNode.isTextual()
                    && ID_PATTERN.matcher(optionIdNode.asText()).matches()
                    ? optionIdNode.asText()
                    : null;
            String label = trimmedText(optionNode.get("label"));
            if (optionId == null || label == null || label.isEmpty() || label.length() > 100) {
                return null;
            }
            options.add(new Option(optionId, label));
        }
        String recommendedOptionId = null;
        JsonNode recommendedNode = node.get("recommendedOptionId");
        if (recommendedNode != null) {
            if (!recommendedNode.isTextual() || !ID_PATTERN.matcher(recommendedNode.asText()).matches()) {
                return null;
            }
            recommendedOptionId = recommendedNode.asText();
        }
        Set<String> optionIds = new LinkedHashSet<>();
        for (Option option : options) {
            if (!optionIds.add(option.id())) {
                return null; // '同一道问题的选项 ID 不能重复'
            }
        }
        if (recommendedOptionId != null && !optionIds.contains(recommendedOptionId)) {
            return null; // '推荐项必须指向当前问题中的选项'
        }
        return new Question(id, prompt, List.copyOf(options), recommendedOptionId);
    }

    public static ClarificationRequest findClarificationRequest(List<CliRunResult.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return null;
        }
        for (int index = toolCalls.size() - 1; index >= 0; index--) {
            CliRunResult.ToolCall call = toolCalls.get(index);
            if (call == null || !CLARIFICATION_TOOL_NAME.equals(call.toolName())) {
                continue;
            }
            ClarificationRequest parsed = parseRequest(call.input());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** 对齐 isClarificationOwner(flow, operator)：双方 union 均为非空串时比 union，否则比 openId（=== 语义）。 */
    public static boolean isClarificationOwner(String ownerOpenId, String ownerUnionId,
            String operatorOpenId, String operatorUnionId) {
        if (ownerUnionId != null && !ownerUnionId.isEmpty()
                && operatorUnionId != null && !operatorUnionId.isEmpty()) {
            return ownerUnionId.equals(operatorUnionId);
        }
        // 对齐 TS `===`：null === null 亦为 true，不能用 != null && equals 短路
        return ownerOpenId == null ? operatorOpenId == null
                : ownerOpenId.equals(operatorOpenId);
    }

    public static String formatClarificationAnswers(ClarificationFlow flow) {
        List<String> lines = new ArrayList<>();
        List<ClarificationAnswer> answers = flow.answers();
        for (int index = 0; index < answers.size(); index++) {
            ClarificationAnswer answer = answers.get(index);
            lines.add((index + 1) + ". " + answer.prompt() + "\n"
                    + (answer.source() == AnswerSource.AGENT
                            ? "Agent 采用推荐方案：" + answer.answer()
                            : "用户回答：" + answer.answer()));
        }
        List<String> sections = new ArrayList<>();
        sections.add("用户已经通过飞书卡片回答了上一轮需求澄清问题。");
        sections.addAll(lines);
        sections.add("请基于这些答案继续工作。如果仍有会实质影响方案的未决问题，再次调用 request_clarification；否则直接整理清晰、可验收的产品结论。");
        return String.join("\n\n", sections);
    }

    public static String formatClarificationMessage(ClarificationFlow flow, String message) {
        String confirmed = !flow.answers().isEmpty()
                ? formatClarificationAnswers(flow)
                : "此前还没有确认任何选项。";
        List<Question> questions = flow.request().questions();
        Question currentQuestion = flow.currentIndex() < questions.size()
                ? questions.get(flow.currentIndex())
                : null;
        List<String> sections = new ArrayList<>();
        sections.add("用户没有继续点击上一张需求澄清卡片，而是在同一个飞书话题里补充了新的信息。旧卡片已经失效，这条消息仍属于同一个任务。");
        sections.add(confirmed);
        if (currentQuestion != null) {
            sections.add("上一张卡片正在询问：" + currentQuestion.prompt());
        }
        sections.add("用户的新消息：" + message);
        sections.add("请优先理解这条新消息对既有需求的修正。如果仍有关键歧义，重新调用 request_clarification；信息已经足够时，直接整理可验收的需求结论。");
        return sections.stream()
                .filter(section -> !section.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /** 内存流程库（TS ClarificationFlowStore；重启即失）。 */
    public static final class ClarificationFlowStore {
        private final Map<String, ClarificationFlow> flows = new LinkedHashMap<>();

        /** 同 (taskId, botId) 的旧流程全部删除（对齐 TS create 的全量清理；边迭代边删须用 Iterator.remove）。 */
        public ClarificationFlow create(CreateFlowOptions options) {
            for (Iterator<Map.Entry<String, ClarificationFlow>> iterator =
                    flows.entrySet().iterator(); iterator.hasNext(); ) {
                ClarificationFlow flow = iterator.next().getValue();
                if (flow.taskId().equals(options.taskId()) && flow.botId().equals(options.botId())) {
                    iterator.remove();
                }
            }
            ClarificationFlow flow = new ClarificationFlow(
                    UUID.randomUUID().toString().replace("-", ""), options);
            flows.put(flow.token(), flow);
            return flow;
        }

        public ClarificationFlow get(String token) {
            return flows.get(token);
        }

        public ClarificationFlow findForTask(String taskId, String botId) {
            for (ClarificationFlow flow : flows.values()) {
                if (flow.taskId().equals(taskId) && flow.botId().equals(botId)) {
                    return flow;
                }
            }
            return null;
        }

        public void delete(String token) {
            flows.remove(token);
        }

        /** 对齐 answer(token, questionId, answer)：source 默认 user。 */
        public AnswerResult answer(String token, String questionId, String answer) {
            return answer(token, questionId, answer, AnswerSource.USER);
        }

        public AnswerResult answer(String token, String questionId, String answer, AnswerSource source) {
            ClarificationFlow flow = flows.get(token);
            Question question = flow == null ? null : currentQuestion(flow);
            String normalized = answer == null ? "" : JsText.jsTrim(answer);
            if (flow == null || question == null || !question.id().equals(questionId)
                    || normalized.isEmpty()) {
                return null;
            }
            flow.answers.add(new ClarificationAnswer(question.id(), question.prompt(),
                    normalized, source));
            flow.currentIndex += 1;
            return new AnswerResult(flow,
                    flow.currentIndex >= flow.request().questions().size());
        }

        /** 对齐 answerWithRecommendation(token, allRemaining)：逐题采用推荐项（无则首个 option）。 */
        public AnswerResult answerWithRecommendation(String token, boolean allRemaining) {
            ClarificationFlow flow = flows.get(token);
            if (flow == null) {
                return null;
            }
            AnswerResult result = null;
            do {
                Question question = currentQuestion(flow);
                if (question == null) {
                    break;
                }
                Option recommended = null;
                for (Option option : question.options()) {
                    if (option.id().equals(question.recommendedOptionId())) {
                        recommended = option;
                        break;
                    }
                }
                if (recommended == null && !question.options().isEmpty()) {
                    recommended = question.options().get(0); // 对齐 ?? question.options[0]
                }
                if (recommended == null) {
                    return null;
                }
                result = answer(token, question.id(), recommended.label(), AnswerSource.AGENT);
                if (result == null || result.complete() || !allRemaining) {
                    return result;
                }
            } while (flow.currentIndex() < flow.request().questions().size());
            return new AnswerResult(flow,
                    flow.currentIndex() >= flow.request().questions().size());
        }

        private static Question currentQuestion(ClarificationFlow flow) {
            List<Question> questions = flow.request().questions();
            return flow.currentIndex() < questions.size()
                    ? questions.get(flow.currentIndex())
                    : null;
        }
    }

    private static String trimmedText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return JsText.jsTrim(node.asText());
    }
}

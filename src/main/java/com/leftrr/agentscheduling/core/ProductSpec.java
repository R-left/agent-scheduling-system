package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.cli.JsText;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产品方案提交（request_spec_approval）。对应 TS 版 src/core/product-spec.ts。
 * zod schema 用 JsonNode 手工校验表达（设计裁决 1）：safeParse → parseRequest 返回 null 不抛。
 */
public final class ProductSpec {
    public static final String PRODUCT_SPEC_TOOL_NAME = "request_spec_approval";
    private static final Pattern DOC_TOKEN = Pattern.compile("^/docx/([A-Za-z0-9_-]+)/?$");

    private ProductSpec() {}

    /**
     * 对齐 ProductSpecRequest 判别联合：deliveryMode 字面量由 parseRequest 写入
     * （"local" / "lark-doc"），构造点之外无法产生非法值。
     */
    public sealed interface ProductSpecRequest permits ProductSpecRequest.Local, ProductSpecRequest.Lark {
        String deliveryMode();
        String title();
        String summary();

        record Local(String deliveryMode, String title, String summary,
                String specPath, String ticketsPath) implements ProductSpecRequest {}

        record Lark(String deliveryMode, String title, String summary,
                String documentUrl) implements ProductSpecRequest {}
    }

    /** 流程状态。对应 TS 的 'pending' | 'approved' | 'expired'。 */
    public enum SpecStatus {
        PENDING("pending"),
        APPROVED("approved"),
        EXPIRED("expired");

        private final String id;

        SpecStatus(String id) {
            this.id = id;
        }

        @JsonValue
        public String id() {
            return id;
        }

        public static SpecStatus fromId(String value) {
            for (SpecStatus status : values()) {
                if (status.id.equals(value)) return status;
            }
            return null;
        }
    }

    /** 对齐 CreateProductSpecFlowOptions；ownerUnionId/collaboration null 表示 undefined。 */
    public record CreateFlowOptions(
            String taskId,
            String botId,
            String sessionId,
            String ownerOpenId,
            String ownerUnionId,
            Collaboration.CollaborationOrigin collaboration,
            ProductSpecRequest request) {}

    /**
     * 可变流程对象（approve 原地改 status/approvedAt，对齐 TS）。
     * 字段声明顺序 = 持久化 JSON 键序（Review Focus 3）；序列化走字段（@JsonAutoDetect）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
            getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE)
    public static final class ProductSpecFlow {
        private final String token;
        private final String taskId;
        private final String botId;
        private final String sessionId;
        private final String ownerOpenId;
        private final String ownerUnionId;
        private final Collaboration.CollaborationOrigin collaboration;
        private final ProductSpecRequest request;
        private SpecStatus status;
        private String approvedAt;

        ProductSpecFlow(String token, CreateFlowOptions options) {
            this(token, options.taskId(), options.botId(), options.sessionId(),
                    options.ownerOpenId(), options.ownerUnionId(), options.collaboration(),
                    options.request(), SpecStatus.PENDING, null);
        }

        /** 重建构造器：JsonProductSpecFlowStore 加载（Task 9）与 copy 用。 */
        public ProductSpecFlow(String token, String taskId, String botId, String sessionId,
                String ownerOpenId, String ownerUnionId,
                Collaboration.CollaborationOrigin collaboration, ProductSpecRequest request,
                SpecStatus status, String approvedAt) {
            this.token = token;
            this.taskId = taskId;
            this.botId = botId;
            this.sessionId = sessionId;
            this.ownerOpenId = ownerOpenId;
            this.ownerUnionId = ownerUnionId;
            this.collaboration = collaboration;
            this.request = request;
            this.status = status;
            this.approvedAt = approvedAt;
        }

        ProductSpecFlow copy() {
            return new ProductSpecFlow(token, taskId, botId, sessionId, ownerOpenId,
                    ownerUnionId, collaboration, request, status, approvedAt);
        }

        public String token() { return token; }
        public String taskId() { return taskId; }
        public String botId() { return botId; }
        public String sessionId() { return sessionId; }
        public String ownerOpenId() { return ownerOpenId; }
        public String ownerUnionId() { return ownerUnionId; }
        public Collaboration.CollaborationOrigin collaboration() { return collaboration; }
        public ProductSpecRequest request() { return request; }
        public SpecStatus status() { return status; }
        public String approvedAt() { return approvedAt; }
    }

    /** 对齐 ProductSpecRequestSchema.safeParse（discriminatedUnion + .strict() 未知键拒绝）。 */
    public static ProductSpecRequest parseRequest(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }
        JsonNode modeNode = input.get("deliveryMode");
        if (modeNode == null || !modeNode.isTextual()) {
            return null;
        }
        String title = trimmedText(input.get("title"));
        if (title == null || title.isEmpty() || title.length() > 80) {
            return null;
        }
        String summary = trimmedText(input.get("summary"));
        if (summary == null || summary.isEmpty() || summary.length() > 500) {
            return null;
        }
        switch (modeNode.asText()) {
            case "local" -> {
                if (!exactlyKeys(input, Set.of("deliveryMode", "title", "summary",
                        "specPath", "ticketsPath"))) {
                    return null;
                }
                String specPath = documentPath(input.get("specPath"));
                String ticketsPath = documentPath(input.get("ticketsPath"));
                if (specPath == null || ticketsPath == null) {
                    return null;
                }
                return new ProductSpecRequest.Local("local", title, summary, specPath, ticketsPath);
            }
            case "lark-doc" -> {
                if (!exactlyKeys(input, Set.of("deliveryMode", "title", "summary", "documentUrl"))) {
                    return null;
                }
                String documentUrl = larkDocumentUrl(input.get("documentUrl"));
                if (documentUrl == null) {
                    return null;
                }
                return new ProductSpecRequest.Lark("lark-doc", title, summary, documentUrl);
            }
            default -> {
                return null;
            }
        }
    }

    /** 对齐 WorkspaceDocumentPathSchema：trim、1..240、不以 / 开头、[\\/] 分段不含 '..'。 */
    static String documentPath(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = JsText.jsTrim(node.asText());
        if (value.isEmpty() || value.length() > 240) {
            return null;
        }
        if (value.startsWith("/")) {
            return null;
        }
        for (String segment : value.split("[\\\\/]")) {
            if ("..".equals(segment)) {
                return null;
            }
        }
        return value;
    }

    /**
     * 对齐 LarkDocumentUrlSchema：合法 URL + pathname 含 /docx/。
     * 已知分歧：zod z.url() 基于 WHATWG new URL()（容忍首尾空白等）；
     * Java 用 URI 严格解析，含空格等字符直接判非法——真实飞书文档链接无此形态。
     */
    static String larkDocumentUrl(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(node.asText());
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getPath() == null) {
            return null;
        }
        return uri.getPath().contains("/docx/") ? node.asText() : null;
    }

    /** 对齐 documentToken()：pathname 形如 /docx/<token> 或 /docx/<token>/ 时提取 token。 */
    static String documentToken(String documentUrl) {
        try {
            Matcher matcher = DOC_TOKEN.matcher(new URI(documentUrl).getPath());
            return matcher.matches() ? matcher.group(1) : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static ProductSpecRequest findProductSpecRequest(List<CliRunResult.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return null;
        }
        for (int index = toolCalls.size() - 1; index >= 0; index--) {
            CliRunResult.ToolCall call = toolCalls.get(index);
            if (call == null || !PRODUCT_SPEC_TOOL_NAME.equals(call.toolName())) {
                continue;
            }
            ProductSpecRequest parsed = parseRequest(call.input());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** 对齐 isProductSpecOwner()（与 clarification.ts 中的同名函数为 TS 侧的刻意重复）。 */
    public static boolean isProductSpecOwner(String ownerOpenId, String ownerUnionId,
            String operatorOpenId, String operatorUnionId) {
        if (ownerUnionId != null && !ownerUnionId.isEmpty()
                && operatorUnionId != null && !operatorUnionId.isEmpty()) {
            return ownerUnionId.equals(operatorUnionId);
        }
        return ownerOpenId != null && ownerOpenId.equals(operatorOpenId);
    }

    private static String trimmedText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return JsText.jsTrim(node.asText());
    }

    /** 对齐 .strict()：输入键集合必须与白名单完全一致。 */
    private static boolean exactlyKeys(JsonNode input, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        input.fieldNames().forEachRemaining(names::add);
        return allowed.equals(names);
    }

    /** 内存流程库（TS ProductSpecFlowStore；持久化子类在 Task 9）。 */
    public static class ProductSpecFlowStore {
        private final Map<String, ProductSpecFlow> flows = new LinkedHashMap<>();

        public ProductSpecFlowStore() {}

        public ProductSpecFlowStore(List<ProductSpecFlow> initialFlows) {
            for (ProductSpecFlow flow : initialFlows) {
                flows.put(flow.token(), flow);
            }
        }

        /** 同 (taskId, botId) 且 pending 的旧流程置 expired（对齐 TS；approved/expired 不动）。
         * 声明 throws IOException 供 Json 子类持久化失败透传（内存版不抛）。 */
        public ProductSpecFlow create(CreateFlowOptions options) throws IOException {
            for (ProductSpecFlow flow : flows.values()) {
                if (flow.taskId().equals(options.taskId())
                        && flow.botId().equals(options.botId())
                        && flow.status() == SpecStatus.PENDING) {
                    flow.status = SpecStatus.EXPIRED;
                }
            }
            ProductSpecFlow flow = new ProductSpecFlow(
                    UUID.randomUUID().toString().replace("-", ""), options);
            flows.put(flow.token(), flow);
            return flow;
        }

        public ProductSpecFlow get(String token) {
            return flows.get(token);
        }

        /** 对齐 findPendingByDocument()：Objects.equals 对齐 TS `===`（null==null 亦相等，实际不可达）。 */
        public ProductSpecFlow findPendingByDocument(String botId, String fileToken) {
            for (ProductSpecFlow flow : flows.values()) {
                if (Objects.equals(flow.botId(), botId)
                        && flow.status() == SpecStatus.PENDING
                        && flow.request() instanceof ProductSpecRequest.Lark lark
                        && Objects.equals(documentToken(lark.documentUrl()), fileToken)) {
                    return flow;
                }
            }
            return null;
        }

        public ProductSpecFlow approve(String token) throws IOException {
            ProductSpecFlow flow = flows.get(token);
            if (flow == null || flow.status() != SpecStatus.PENDING) {
                return null;
            }
            flow.status = SpecStatus.APPROVED;
            flow.approvedAt = IsoTime.now();
            return flow;
        }

        /** 对齐 protected snapshot()：structuredClone → 逐字段 copy（其余成员均为不可变 record）。 */
        protected List<ProductSpecFlow> snapshot() {
            List<ProductSpecFlow> copy = new ArrayList<>();
            for (ProductSpecFlow flow : flows.values()) {
                copy.add(flow.copy());
            }
            return copy;
        }

        protected void restore(List<ProductSpecFlow> snapshot) {
            flows.clear();
            for (ProductSpecFlow flow : snapshot) {
                flows.put(flow.token(), flow);
            }
        }
    }
}

package com.leftrr.agentscheduling.core;
import com.leftrr.agentscheduling.util.IsoTimeUtil;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.leftrr.agentscheduling.dto.cli.CliRunResult;
import com.leftrr.agentscheduling.util.StringUtil;

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
 * 产品方案提交（request_spec_approval）。
 * 请求用 JsonNode 手工校验：parseRequest 非法返回 null 不抛。
 */
public final class ProductSpec {
    public static final String PRODUCT_SPEC_TOOL_NAME = "request_spec_approval";
    private static final Pattern DOC_TOKEN = Pattern.compile("^/docx/([A-Za-z0-9_-]+)/?$");

    private ProductSpec() {}

    /**
     * sealed 判别联合：deliveryMode 字面量由 parseRequest 写入
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

    /** 流程状态。 */
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

    /** 创建流程的选项；ownerUnionId/collaboration null 表示缺省。 */
    public record CreateFlowOptions(
            String taskId,
            String botId,
            String sessionId,
            String ownerOpenId,
            String ownerUnionId,
            Collaboration.CollaborationOrigin collaboration,
            ProductSpecRequest request) {}

    /**
     * 可变流程对象（approve 原地改 status/approvedAt）。
     * 字段声明顺序 = 持久化 JSON 键序；序列化走字段（@JsonAutoDetect）。
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

        /** 重建构造器：JsonProductSpecFlowStore 加载与 copy 用。 */
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

    /** 解析 request_spec_approval 入参：未知键拒绝，非法返回 null。 */
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

    /** 工作区文档路径：trim、1..240、不以 / 开头、[\\/] 分段不含 '..'。 */
    static String documentPath(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = StringUtil.trim(node.asText());
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
     * 合法 URL + pathname 含 /docx/。
     * 边界说明：URI 严格解析，含空格等字符直接判非法（容忍度低于 WHATWG URL）——
     * 真实飞书文档链接无此形态。
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

    /** pathname 形如 /docx/<token> 或 /docx/<token>/ 时提取 token。 */
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

    /** 判定操作者是否为流程 owner（与 {@link Clarification} 中的同名判定同构，刻意重复）。 */
    public static boolean isProductSpecOwner(String ownerOpenId, String ownerUnionId,
            String operatorOpenId, String operatorUnionId) {
        if (ownerUnionId != null && !ownerUnionId.isEmpty()
                && operatorUnionId != null && !operatorUnionId.isEmpty()) {
            return ownerUnionId.equals(operatorUnionId);
        }
        // null 与 null 亦视为相等，不能用 != null && equals 短路
        return ownerOpenId == null ? operatorOpenId == null
                : ownerOpenId.equals(operatorOpenId);
    }

    private static String trimmedText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return StringUtil.trim(node.asText());
    }

    /** 输入键集合必须与白名单完全一致。 */
    private static boolean exactlyKeys(JsonNode input, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        input.fieldNames().forEachRemaining(names::add);
        return allowed.equals(names);
    }

    /** 内存流程库；持久化由子类 JsonProductSpecFlowStore 承担。 */
    public static class ProductSpecFlowStore {
        private final Map<String, ProductSpecFlow> flows = new LinkedHashMap<>();

        public ProductSpecFlowStore() {}

        public ProductSpecFlowStore(List<ProductSpecFlow> initialFlows) {
            for (ProductSpecFlow flow : initialFlows) {
                flows.put(flow.token(), flow);
            }
        }

        /** 同 (taskId, botId) 且 pending 的旧流程置 expired（approved/expired 不动）。
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

        /** 按文档 token 找 pending 流程（null 与 null 亦视为相等，实际不可达）。 */
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
            flow.approvedAt = IsoTimeUtil.now();
            return flow;
        }

        /** 逐字段 copy 成快照（flow 含可变字段，必须深拷贝）。 */
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

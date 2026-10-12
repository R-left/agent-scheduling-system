package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leftrr.agentscheduling.core.Collaboration.CollaborationOrigin;
import com.leftrr.agentscheduling.core.ProductSpec.CreateFlowOptions;
import com.leftrr.agentscheduling.core.ProductSpec.ProductSpecFlow;
import com.leftrr.agentscheduling.core.ProductSpec.ProductSpecRequest;
import com.leftrr.agentscheduling.core.ProductSpec.SpecStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 产品方案流程 JSON 持久化。对应 TS 版 src/core/product-spec-store.ts。
 * mutate = snapshot → 操作 → persist；persist 失败 → restore(snapshot) → 抛（对齐 TS）。
 */
public final class JsonProductSpecFlowStore extends ProductSpec.ProductSpecFlowStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;

    public JsonProductSpecFlowStore(String filePath) throws IOException {
        super(loadFlows(filePath));
        this.filePath = Path.of(filePath);
    }

    @Override
    public ProductSpecFlow create(CreateFlowOptions options) throws IOException {
        return mutate(() -> super.create(options));
    }

    @Override
    public ProductSpecFlow approve(String token) throws IOException {
        return mutate(() -> super.approve(token));
    }

    /** 对齐 TS mutate(operation: () => T)；Supplier.get 不允许抛受检异常，故自定函数式接口。 */
    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private <T> T mutate(IoSupplier<T> operation) throws IOException {
        List<ProductSpecFlow> previous = snapshot();
        try {
            T result = operation.get();
            persist();
            return result;
        } catch (IOException | RuntimeException e) {
            restore(previous);
            throw e;
        }
    }

    private void persist() throws IOException {
        JsonFormat.writeAtomic(filePath, JsonFormat.toJson(snapshot()) + "\n");
    }

    private static List<ProductSpecFlow> loadFlows(String filePath) throws IOException {
        String content;
        try {
            content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        JsonNode rows = MAPPER.readTree(content);
        if (!rows.isArray()) {
            throw new IOException("产品方案状态文件格式错误: " + filePath);
        }
        List<ProductSpecFlow> flows = new ArrayList<>();
        for (JsonNode row : rows) {
            ProductSpecFlow flow = parseFlow(row);
            if (flow != null) {
                flows.add(flow);
            }
        }
        return flows;
    }

    /** 对齐 ProductSpecFlowSchema.safeParse：非法行返回 null（调用方跳过）。 */
    static ProductSpecFlow parseFlow(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }
        String token = min1Text(row, "token");
        String taskId = min1Text(row, "taskId");
        String botId = min1Text(row, "botId");
        String sessionId = min1Text(row, "sessionId");
        String ownerOpenId = min1Text(row, "ownerOpenId");
        if (token == null || taskId == null || botId == null || sessionId == null
                || ownerOpenId == null) {
            return null;
        }
        String ownerUnionId = null;
        if (row.has("ownerUnionId")) {
            ownerUnionId = min1Text(row, "ownerUnionId");
            if (ownerUnionId == null) {
                return null;
            }
        }
        CollaborationOrigin collaboration = null;
        if (row.has("collaboration")) {
            collaboration = parseCollaborationOrigin(row.get("collaboration"));
            if (collaboration == null) {
                return null;
            }
        }
        ProductSpecRequest request = ProductSpec.parseRequest(row.get("request"));
        if (request == null) {
            return null;
        }
        String statusRaw = min1Text(row, "status");
        SpecStatus status = statusRaw == null ? null : SpecStatus.fromId(statusRaw);
        if (status == null) {
            return null;
        }
        String approvedAt = null;
        if (row.has("approvedAt")) {
            JsonNode approvedNode = row.get("approvedAt");
            approvedAt = approvedNode.isTextual() && IsoTime.isZodDateTime(approvedNode.asText())
                    ? approvedNode.asText()
                    : null;
            if (approvedAt == null) {
                return null;
            }
        }
        return new ProductSpecFlow(token, taskId, botId, sessionId, ownerOpenId, ownerUnionId,
                collaboration, request, status, approvedAt);
    }

    static CollaborationOrigin parseCollaborationOrigin(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String taskId = min1Text(node, "taskId");
        String fromBotId = min1Text(node, "fromBotId");
        String reportToBotId = min1Text(node, "reportToBotId");
        JsonNode round = node.get("round");
        JsonNode maxRounds = node.get("maxRounds");
        // z.number().int().positive() 经 JSON.parse 接受浮点整值（1.0 同 1，JS Number 无整型区分）。
        // Java 边界差：zod 可接受超过 int 范围的整数，但 Java int 会回绕，故拒绝并按既有非法行处理（返回 null 丢弃该行）。
        if (taskId == null || fromBotId == null || reportToBotId == null
                || !positiveIntValue(round) || !positiveIntValue(maxRounds)) {
            return null;
        }
        return new CollaborationOrigin(taskId, fromBotId, reportToBotId,
                (int) round.asLong(), (int) maxRounds.asLong());
    }

    /** 数值为整数且落在 1..Integer.MAX_VALUE（对齐 z.number().int().positive() + Java int 上界）。 */
    private static boolean positiveIntValue(JsonNode value) {
        return value != null && value.canConvertToExactIntegral()
                && value.asLong() >= 1 && value.asLong() <= Integer.MAX_VALUE;
    }

    private static String min1Text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && value.isTextual() && !value.asText().isEmpty()
                ? value.asText()
                : null;
    }
}

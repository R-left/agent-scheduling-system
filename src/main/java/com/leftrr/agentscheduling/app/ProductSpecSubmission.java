package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.cli.CliRunResult;
import com.leftrr.agentscheduling.core.ProductDeliveryMode;
import com.leftrr.agentscheduling.core.ProductSpec;

import java.io.IOException;

/**
 * 产品方案提交校验。对齐 TS 版 src/app/product-spec-submission.ts（48 行）。
 * TS 导出 interface ProductSpecSubmission、async ensureProductSpecSubmission 与私有
 * missingSubmissionPrompt；Java 收敛为同名 record（one public type per file），
 * ensure 与 Retry 接口挂为静态成员，名称与 TS 导出一一对应。
 */
public record ProductSpecSubmission(CliRunResult result, ProductSpec.ProductSpecRequest request) {

    /** TS options.retry 回调（(prompt, sessionId) => Promise&lt;CliRunResult&gt;）的函数式接口。 */
    @FunctionalInterface
    public interface Retry {
        CliRunResult retry(String prompt, String sessionId) throws IOException, InterruptedException;
    }

    /** TS ensureProductSpecSubmission（product-spec-submission.ts:13-35）。 */
    public static ProductSpecSubmission ensureProductSpecSubmission(CliRunResult result,
            ProductDeliveryMode defaultDeliveryMode, Retry retry)
            throws IOException, InterruptedException {
        ProductSpec.ProductSpecRequest existing =
                ProductSpec.findProductSpecRequest(result.toolCalls());
        if (existing != null) {
            return new ProductSpecSubmission(result, existing);
        }
        CliRunResult retried = retry.retry(
                missingSubmissionPrompt(defaultDeliveryMode), result.sessionId());
        ProductSpec.ProductSpecRequest recovered =
                ProductSpec.findProductSpecRequest(retried.toolCalls());
        if (recovered == null) {
            throw new RuntimeException(
                    "产品方案已经整理完成，但产品经理没有调用 request_spec_approval");
        }
        return new ProductSpecSubmission(retried, recovered);
    }

    /** TS 私有 missingSubmissionPrompt（product-spec-submission.ts:37-47）；五段文案逐字，包私有供测试钉住。 */
    static String missingSubmissionPrompt(ProductDeliveryMode defaultDeliveryMode) {
        return String.join("\n\n",
                "上一轮已经完成产品方案整理，但没有完成 Agent OS 的结构化提交。",
                "不要重新创建文档，也不要继续解释。请沿用上一轮已经生成的唯一产物，立即调用 request_spec_approval。",
                "没有明确覆盖时，deliveryMode 使用 " + defaultDeliveryMode.id() + "。",
                "必须实际调用工具；不能只在普通回复中写出 deliveryMode、documentUrl、specPath 或 ticketsPath。",
                "工具调用成功后立即结束本轮。");
    }
}

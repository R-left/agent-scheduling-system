package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.ProductSpec.ProductSpecRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 产品方案 local 交付模式下的文档存在性校验。对齐 TS 版 src/app/product-spec-documents.ts（41 行）：
 * spec 判 isFile；tickets 判 isDirectory 且目录下含 .md 文件；IO 异常一律按 missing 处理
 * （对齐 TS catch）；missing 非空时 throw RuntimeException，文案逐字。
 */
public final class ProductSpecDocuments {

    private ProductSpecDocuments() {}

    /** TS assertProductSpecDocuments（product-spec-documents.ts:5-40）。 */
    public static void assertProductSpecDocuments(String workspaceDir,
            ProductSpecRequest.Local request) {
        List<String> missing = new ArrayList<>();

        // TS try { stat; isFile } catch { missing }：Java Files.isRegularFile 对不存在/IO
        // 错误返回 false，但 Path.of 遇非法字符（如 NUL）抛 InvalidPathException，
        // 与 TS catch 捕获一切一致，归入 missing
        try {
            if (!Files.isRegularFile(Path.of(workspaceDir, request.specPath()))) {
                missing.add("Spec: " + request.specPath());
            }
        } catch (RuntimeException e) {
            missing.add("Spec: " + request.specPath());
        }

        try {
            Path ticketsDir = Path.of(workspaceDir, request.ticketsPath());
            boolean hasTicket = false;
            if (Files.isDirectory(ticketsDir)) {
                // TS readdir withFileTypes 过滤 isFile() && name.endsWith('.md')
                try (Stream<Path> entries = Files.list(ticketsDir)) {
                    hasTicket = entries.anyMatch(entry -> Files.isRegularFile(entry)
                            && entry.getFileName().toString().endsWith(".md"));
                }
            }
            // TS 判 !isDirectory() || !hasTicket：非目录时 hasTicket 恒 false，两分支合一
            if (!hasTicket) {
                missing.add("Tickets: " + request.ticketsPath());
            }
        } catch (RuntimeException | IOException e) {
            // IO 异常（目录不可读等）按 missing 处理，对齐 TS catch
            missing.add("Tickets: " + request.ticketsPath());
        }

        if (!missing.isEmpty()) {
            throw new RuntimeException("产品方案尚未完整写入工作区，不能展示。\n"
                    + missing.stream().map(item -> "- " + item).collect(Collectors.joining("\n")));
        }
    }
}

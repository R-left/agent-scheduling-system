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
 * local 交付的文档存在性校验：spec 判文件，tickets 判目录下有 .md；
 * IO 异常一律按 missing 处理，missing 非空即抛（文案为产品文案）。
 */
public final class ProductSpecDocuments {

    private ProductSpecDocuments() {}

    public static void assertProductSpecDocuments(String workspaceDir,
            ProductSpecRequest.Local request) {
        List<String> missing = new ArrayList<>();

        // Path.of 遇非法字符（如 NUL）抛 InvalidPathException，异常也归入 missing
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
                // isRegularFile 跟随符号链接，条目是指向常规文件的链接也判有工单
                try (Stream<Path> entries = Files.list(ticketsDir)) {
                    hasTicket = entries.anyMatch(entry -> Files.isRegularFile(entry)
                            && entry.getFileName().toString().endsWith(".md"));
                }
            }
            if (!hasTicket) {
                missing.add("Tickets: " + request.ticketsPath());
            }
        } catch (RuntimeException | IOException e) {
            missing.add("Tickets: " + request.ticketsPath());
        }

        if (!missing.isEmpty()) {
            throw new RuntimeException("产品方案尚未完整写入工作区，不能展示。\n"
                    + missing.stream().map(item -> "- " + item).collect(Collectors.joining("\n")));
        }
    }
}

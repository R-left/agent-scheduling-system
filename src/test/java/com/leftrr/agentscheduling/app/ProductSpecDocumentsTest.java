package com.leftrr.agentscheduling.app;

import com.leftrr.agentscheduling.core.ProductSpec.ProductSpecRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M6 Task 3：assertProductSpecDocuments 四路径测试（brief Step 1 ②）。
 * 对齐 TS 版 src/app/product-spec-documents.ts（41 行）：spec 判 isFile、tickets 判
 * isDirectory 且含 .md 文件、IO 异常按 missing 处理、失败 throw 文案逐字
 * （首行 + " - Spec: path" / " - Tickets: path"，TS join("\n")）。
 */
class ProductSpecDocumentsTest {

    private static final String SPEC_PATH = "spec/product-spec.md";
    private static final String TICKETS_PATH = "spec/tickets";

    @TempDir
    Path tmpDir;

    private static ProductSpecRequest.Local request(String specPath, String ticketsPath) {
        return new ProductSpecRequest.Local("local", "标题", "概述", specPath, ticketsPath);
    }

    private void writeSpec() throws IOException {
        Path spec = tmpDir.resolve(SPEC_PATH);
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, "# 产品方案", StandardCharsets.UTF_8);
    }

    private void writeTicket(String name) throws IOException {
        Path tickets = tmpDir.resolve(TICKETS_PATH);
        Files.createDirectories(tickets);
        Files.writeString(tickets.resolve(name), "# 工单", StandardCharsets.UTF_8);
    }

    // ---------- 路径 1：spec 文件缺失 ----------

    @Test
    void spec文件缺失时抛出且文案含Spec行() throws IOException {
        writeTicket("ticket-a.md"); // tickets 全齐，隔离 spec 单因素
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductSpecDocuments.assertProductSpecDocuments(
                        tmpDir.toString(), request(SPEC_PATH, TICKETS_PATH)));
        assertEquals("产品方案尚未完整写入工作区，不能展示。\n- Spec: " + SPEC_PATH,
                e.getMessage());
    }

    // ---------- 路径 2：tickets 目录存在但缺 .md ----------

    @Test
    void tickets目录缺md文件时抛出且文案含Tickets行() throws IOException {
        writeSpec();
        Files.createDirectories(tmpDir.resolve(TICKETS_PATH)); // 空目录
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductSpecDocuments.assertProductSpecDocuments(
                        tmpDir.toString(), request(SPEC_PATH, TICKETS_PATH)));
        assertEquals("产品方案尚未完整写入工作区，不能展示。\n- Tickets: " + TICKETS_PATH,
                e.getMessage());
    }

    // ---------- 路径 2 补充：tickets 目录只有非 .md 文件同样判缺 ----------

    @Test
    void tickets目录只有非md文件时同样判缺() throws IOException {
        writeSpec();
        writeTicket("ticket-b.txt"); // 非 .md 不计
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductSpecDocuments.assertProductSpecDocuments(
                        tmpDir.toString(), request(SPEC_PATH, TICKETS_PATH)));
        assertEquals("产品方案尚未完整写入工作区，不能展示。\n- Tickets: " + TICKETS_PATH,
                e.getMessage());
    }

    // ---------- 路径 2 补充：ticketsPath 指向普通文件（非目录）判缺且不抛 IO ----------

    @Test
    void ticketsPath指向普通文件时判缺() throws IOException {
        writeSpec();
        Files.writeString(tmpDir.resolve(TICKETS_PATH), "不是目录", StandardCharsets.UTF_8);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductSpecDocuments.assertProductSpecDocuments(
                        tmpDir.toString(), request(SPEC_PATH, TICKETS_PATH)));
        assertEquals("产品方案尚未完整写入工作区，不能展示。\n- Tickets: " + TICKETS_PATH,
                e.getMessage());
    }

    // ---------- 路径 3：全齐不抛 ----------

    @Test
    void spec与tickets全齐时不抛() throws IOException {
        writeSpec();
        writeTicket("ticket-a.md");
        assertDoesNotThrow(() -> ProductSpecDocuments.assertProductSpecDocuments(
                tmpDir.toString(), request(SPEC_PATH, TICKETS_PATH)));
    }

    // ---------- 路径 4：路径非法（IO/解析异常）按 missing 处理 ----------

    /**
     * TS try/catch 对 stat/readdir 异常一律按 missing 处理；Java 端对齐：路径含 NUL
     * 等非法字符时 Path.of 抛 InvalidPathException（RuntimeException，全平台一致），
     * 两个检查各自归入 missing，Spec 行在前 Tickets 行在后（对齐 TS push 顺序）。
     */
    @Test
    void 路径非法时按missing处理且Spec行在前Tickets行在后() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> ProductSpecDocuments.assertProductSpecDocuments(
                        tmpDir.toString(), request("spec\0bad.md", "tickets\0dir")));
        assertEquals("产品方案尚未完整写入工作区，不能展示。\n"
                        + "- Spec: spec\0bad.md\n"
                        + "- Tickets: tickets\0dir",
                e.getMessage());
    }
}

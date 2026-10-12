package com.leftrr.agentscheduling.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 持久化公共件（计划设计裁决 4）。
 * 输出形态逐字节对齐 TS JSON.stringify(value, null, 2)：
 * 2 空格缩进、对象与数组同缩进、"key": value（冒号后单空格、冒号前无空格）。
 * 原子写对齐 tmp + rename：写 ${target}.tmp 后 ATOMIC_MOVE。
 */
public final class JsonFormat {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY_PRINTER = createPrettyPrinter();

    private JsonFormat() {}

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
        return printer.withSeparators(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                // jackson 2.16+ 对空数组/空对象默认输出 "[ ]"/"{ }"，需置空对齐 JSON.stringify 的 "[]"/"{}"
                .withArrayEmptySeparator("")
                .withObjectEmptySeparator(""));
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writer(PRETTY_PRINTER).writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 对齐「mkdir 父目录 → 写 ${path}.tmp → rename」。ATOMIC_MOVE 不支持时退回 REPLACE_EXISTING。 */
    public static void writeAtomic(Path target, String content) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

package com.leftrr.agentscheduling.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行时数据落点：{@code ~/.agentos/data/}，与配置 {@code ~/.agentos/bots.json}
 * 同一约定根（user.home 跨 Windows/macOS）。持久化整体在仓库与 CWD 之外，
 * fat jar 从任意目录启动行为一致；写入方各自确保所需子目录存在。
 */
public final class AppPaths {

    private AppPaths() {}

    /** 数据根目录。 */
    public static Path dataDirectory() {
        return Path.of(System.getProperty("user.home"), ".agentos", "data");
    }

    /** 会话持久化文件。 */
    public static String sessionsFile() {
        return dataDirectory().resolve("sessions.json").toString();
    }

    /** 产品方案流程持久化文件。 */
    public static String productSpecFlowsFile() {
        return dataDirectory().resolve("product-spec-flows.json").toString();
    }

    /** 消息附件下载目录。 */
    public static String downloadsDirectory() {
        return dataDirectory().resolve("downloads").toString();
    }

    /** 启动时确保数据根目录存在；两个 JSON 存储不自建父目录，缺了会写失败。 */
    public static void ensureDataDirectory() throws IOException {
        Files.createDirectories(dataDirectory());
    }
}

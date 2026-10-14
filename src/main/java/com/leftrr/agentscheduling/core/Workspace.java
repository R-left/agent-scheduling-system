package com.leftrr.agentscheduling.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 工作目录路径解析与存在性校验。
 * 以 / 或 \ 开头即视为绝对路径。
 * 边界说明：drive-relative 输入（如 C:work，盘符无分隔符）按 base 相对解析，
 * 不解析到该盘符的进程 cwd；真实输入几乎不会出现。
 */
public final class Workspace {
    private static final Pattern DRIVE_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*$");

    private Workspace() {
    }

    public static String resolveWorkspacePath(String input) {
        return resolveWorkspacePath(input, Path.of("").toAbsolutePath().toString());
    }

    public static String resolveWorkspacePath(String input, String baseDirectory) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("工作目录不能为空");
        Path base = Path.of(baseDirectory).toAbsolutePath().normalize();
        Path resolved = isAbsoluteLike(value)
                ? toDriveAbsolute(Path.of(value), base)
                : base.resolve(value);
        return resolved.normalize().toString();
    }

    /**
     * 以 / 或 \ 开头但无盘符的路径，Java 视为盘符相对路径（isAbsolute 为 false）。
     * 挂到 base 所在盘符的根（如 /x + D:\base → D:\x）。
     */
    private static Path toDriveAbsolute(Path value, Path base) {
        if (value.isAbsolute()) return value;
        Path root = base.getRoot();
        if (root == null) return value;
        String rest = value.toString().replaceFirst("^[\\\\/]+", "");
        return rest.isEmpty() ? root : root.resolve(rest);
    }

    public static void ensureWorkspaceDirectory(String path) {
        Path target = Path.of(path);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("工作目录不存在: " + path);
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("工作目录不是文件夹: " + path);
        }
    }

    private static boolean isAbsoluteLike(String value) {
        return value.startsWith("/") || value.startsWith("\\") || DRIVE_ABSOLUTE.matcher(value).matches();
    }
}

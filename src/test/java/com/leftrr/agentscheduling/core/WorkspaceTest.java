package com.leftrr.agentscheduling.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsBlankInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Workspace.resolveWorkspacePath("   "));
        assertEquals("工作目录不能为空", e.getMessage());
    }

    @Test
    void resolvesRelativeAgainstBase() {
        String result = Workspace.resolveWorkspacePath("workspaces/bot-a", tempDir.toString());
        assertEquals(tempDir.resolve("workspaces").resolve("bot-a").toString(), result);
    }

    @Test
    void keepsWindowsStyleAbsolutePath() {
        String result = Workspace.resolveWorkspacePath("C:\\work\\bot", tempDir.toString());
        assertEquals(Path.of("C:\\work", "bot").toString(), result);
    }

    @Test
    void treatsLeadingSlashAsAbsoluteLikeNode() {
        // Node 的 path.isAbsolute('/x') 为 true；Java Path 视为盘符相对路径。
        // 语义对齐 Node：以 / 或 \ 开头即视为绝对路径（挂到当前盘符根）。
        String result = Workspace.resolveWorkspacePath("/workspaces/bot", tempDir.toString());
        assertTrue(Path.of(result).isAbsolute());
        // 必须挂在 base 所在盘符的根，而非系统当前盘符根
        assertEquals(tempDir.getRoot(), Path.of(result).getRoot());
        assertEquals(tempDir.getRoot().resolve("workspaces").resolve("bot").toString(), result);
    }

    @Test
    void ensurePassesForExistingDirectory() {
        Workspace.ensureWorkspaceDirectory(tempDir.toString());
    }

    @Test
    void ensureFailsForMissingDirectory() {
        String missing = tempDir.resolve("no-such-dir").toString();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Workspace.ensureWorkspaceDirectory(missing));
        assertEquals("工作目录不存在: " + missing, e.getMessage());
    }

    @Test
    void ensureFailsForPlainFile(@TempDir Path anotherTemp) throws IOException {
        Path file = anotherTemp.resolve("plain.txt");
        Files.writeString(file, "hi");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Workspace.ensureWorkspaceDirectory(file.toString()));
        assertEquals("工作目录不是文件夹: " + file, e.getMessage());
    }
}

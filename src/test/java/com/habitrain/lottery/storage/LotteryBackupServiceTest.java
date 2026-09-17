package com.habitrain.lottery.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LotteryBackupServiceTest {
    @TempDir
    Path temp;

    @Test
    void copyTreeRecursesAndPreservesRelativePaths() throws Exception {
        Path src = temp.resolve("habitrain_lottery");
        Path nested = src.resolve("players");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("a.json"), "{\"lootChance\":3}", StandardCharsets.UTF_8);
        Files.createDirectories(src.resolve("mail/players"));
        Files.writeString(src.resolve("mail/players/b.json"), "[]", StandardCharsets.UTF_8);

        Path dest = temp.resolve("lottery_backup").resolve("20260718-120000");
        LotteryBackupService.copyTree(src, dest);

        assertTrue(Files.isRegularFile(dest.resolve("players/a.json")));
        assertEquals("{\"lootChance\":3}", Files.readString(dest.resolve("players/a.json")));
        assertTrue(Files.isRegularFile(dest.resolve("mail/players/b.json")));
        assertFalse(Files.exists(LotteryBackupService.partialDir(dest)));
    }

    @Test
    void copyTreeFailsWhenSourceMissing() {
        Path src = temp.resolve("missing");
        Path dest = temp.resolve("out");
        assertThrows(Exception.class, () -> LotteryBackupService.copyTree(src, dest));
    }

    @Test
    void backupDestStaysUnderBackupRoot() throws Exception {
        Path backupRoot = temp.resolve("world").resolve("lottery_backup");
        Files.createDirectories(backupRoot);

        assertNull(LotteryBackupService.resolveBackupFolder(backupRoot, "../secret"));
        assertNull(LotteryBackupService.resolveBackupFolder(backupRoot, "..\\secret"));
        assertNull(LotteryBackupService.resolveBackupFolder(backupRoot, "a/b"));
        assertNull(LotteryBackupService.resolveBackupFolder(backupRoot, ".."));

        Path escaped = backupRoot.resolve("../secret").normalize();
        assertFalse(LotteryBackupService.isUnder(backupRoot, escaped));

        Path ok = LotteryBackupService.resolveBackupFolder(backupRoot, "20260718-120000");
        assertNotNull(ok);
        assertTrue(LotteryBackupService.isUnder(backupRoot, ok));
        assertEquals(backupRoot.toAbsolutePath().normalize(), ok.getParent());
    }
}

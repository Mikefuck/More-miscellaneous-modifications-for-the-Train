package com.habitrain.lottery.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LotteryHistoryStoreRotateTest {
    @TempDir
    Path temp;

    @Test
    void rotateByLineCountRenamesToDotOne() throws Exception {
        Path file = temp.resolve("player.jsonl");
        Files.writeString(file, "a\nb\nc\n", StandardCharsets.UTF_8);
        LotteryHistoryStore.rotateIfNeeded(file, 1_000_000L, 2);
        Path rotated = file.resolveSibling("player.jsonl.1");
        assertFalse(Files.exists(file));
        assertTrue(Files.isRegularFile(rotated));
        assertEquals("a\nb\nc\n", Files.readString(rotated, StandardCharsets.UTF_8));
    }

    @Test
    void rotateBySize() throws Exception {
        Path file = temp.resolve("big.jsonl");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        LotteryHistoryStore.rotateIfNeeded(file, 8L, 10_000);
        assertFalse(Files.exists(file));
        assertTrue(Files.isRegularFile(file.resolveSibling("big.jsonl.1")));
    }

    @Test
    void underLimitDoesNotRotate() throws Exception {
        Path file = temp.resolve("ok.jsonl");
        Files.writeString(file, "one\n", StandardCharsets.UTF_8);
        LotteryHistoryStore.rotateIfNeeded(file, 1_000_000L, 10);
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling("ok.jsonl.1")));
    }
}

package com.habitrain.lottery.backpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalBackpackStoreTest {
    @TempDir
    Path tmp;

    @Test
    void missingFileIsMissingNotAllZeroData() {
        LocalBackpackStore.LoadResult load = LocalBackpackStore.loadFrom(tmp.resolve("nope.json"));
        assertTrue(load.isMissing());
        assertFalse(load.ok());
        assertFalse(load.corrupt());
        assertNull(load.cards());
    }

    @Test
    void corruptFileIsCorruptNotAllZeroData() throws Exception {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        LocalBackpackStore.LoadResult load = LocalBackpackStore.loadFrom(file);
        assertTrue(load.corrupt());
        assertFalse(load.ok());
        assertFalse(load.isMissing());
        assertNull(load.cards());
    }

    @Test
    void existingZeroCountsAreOkNotMissing() throws Exception {
        Path file = tmp.resolve("zero.json");
        Files.writeString(file, "{\"version\":1,\"cards\":{\"killer\":0}}", StandardCharsets.UTF_8);
        LocalBackpackStore.LoadResult load = LocalBackpackStore.loadFrom(file);
        assertTrue(load.ok());
        assertFalse(load.isMissing());
        assertFalse(load.corrupt());
        assertNotNull(load.cards());
        assertEquals(0, load.cards().get("killer"));
    }
}

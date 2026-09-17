package com.habitrain.lottery.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLotteryStoreLoadTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void truncatedPrimaryReadsBak() throws Exception {
        Path file = temp.resolve("u.json");
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{\"lootChance\":7,\"coinNum\":3}", StandardCharsets.UTF_8);

        PlayerLotteryStore.DiskLoad load = PlayerLotteryStore.loadFromDiskForTest(file);
        assertTrue(load.ok());
        assertTrue(load.flushable());
        assertFalse(load.isCorrupt());
        assertEquals(7, load.data.lootChance);
        assertEquals(3, load.data.coinNum);
    }

    @Test
    void missingPrimaryReadsBak() throws Exception {
        Path file = temp.resolve("missing.json");
        Files.writeString(AtomicJsonFiles.bakPath(file), "{\"coinNum\":42}", StandardCharsets.UTF_8);

        PlayerLotteryStore.DiskLoad load = PlayerLotteryStore.loadFromDiskForTest(file);
        assertTrue(load.ok());
        assertTrue(load.flushable());
        assertEquals(42, load.data.coinNum);
    }

    @Test
    void bothUnreadableIsCorruptAndNotFlushable() throws Exception {
        Path file = temp.resolve("bad.json");
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{", StandardCharsets.UTF_8);

        PlayerLotteryStore.DiskLoad load = PlayerLotteryStore.loadFromDiskForTest(file);
        assertTrue(load.isCorrupt());
        assertFalse(load.flushable());
        assertNull(load.data);
    }

    @Test
    void missingBothIsNewAccountFlushableZeros() {
        Path file = temp.resolve("new.json");
        PlayerLotteryStore.DiskLoad load = PlayerLotteryStore.loadFromDiskForTest(file);
        assertTrue(load.isMissing());
        assertTrue(load.flushable());
        assertEquals(0, load.data.lootChance);
        assertEquals(0, load.data.coinNum);
    }

    @Test
    void corruptFileDoesNotCacheOrFlushZeros() throws Exception {
        WorldLotteryPaths.initForTest(temp);
        UUID id = UUID.randomUUID();
        Path file = WorldLotteryPaths.playerFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), "{", StandardCharsets.UTF_8);

        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.getOrLoad(id);
        assertTrue(store.isLoadFailed(id));
        store.update(id, d -> d.lootChance = 99);
        assertTrue(store.flush(id));
        assertFalse(store.isDirty(id));
        assertFalse(Files.isRegularFile(file));
    }
}

package com.habitrain.lottery.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerLotteryStoreResetTest {
    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void resetTurnsOffTakeoverAndDropsCache() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.onServerStarted(null);
        assertTrue(store.isTakeoverActive());

        UUID id = UUID.randomUUID();
        store.setCoinNum(id, 99);
        assertEquals(99, store.getCoinNum(id));

        store.reset();
        assertFalse(store.isTakeoverActive());
        assertTrue(WorldLotteryPaths.ready());

        Path file = WorldLotteryPaths.playerFile(id);
        assertNotNull(file);
        Files.writeString(file, "{\"coinNum\":3}", StandardCharsets.UTF_8);
        assertEquals(3, store.getCoinNum(id));
    }

    @Test
    void readyWithoutOnServerStartedIsNotTakeover() {
        WorldLotteryPaths.initForTests(temp);
        assertTrue(WorldLotteryPaths.ready());
        assertFalse(PlayerLotteryStore.get().isTakeoverActive());
    }

    @Test
    void abortResetsStoreAndPathsTogether() {
        WorldLotteryPaths.initForTests(temp);
        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.onServerStarted(null);
        assertTrue(store.isTakeoverActive());
        store.reset();
        WorldLotteryPaths.reset();
        assertFalse(WorldLotteryPaths.ready());
        assertFalse(store.isTakeoverActive());
    }
}

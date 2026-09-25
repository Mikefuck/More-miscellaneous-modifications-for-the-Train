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
    void resetTurnsOffStoreAndDropsCache() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.onServerStarted(null);
        assertTrue(store.isTakeoverActive());

        UUID id = UUID.randomUUID();
        store.setGreenApples(id, 99);
        assertEquals(99, store.getGreenApples(id));

        store.reset();
        assertFalse(store.isTakeoverActive());
        assertTrue(WorldLotteryPaths.ready());

        Path file = WorldLotteryPaths.playerFile(id);
        assertNotNull(file);
        Files.writeString(file, "{\"greenApples\":3}", StandardCharsets.UTF_8);
        assertEquals(3, store.getGreenApples(id));
    }

    @Test
    void readyWithoutOnServerStartedIsNotTakeover() {
        WorldLotteryPaths.initForTests(temp);
        assertTrue(WorldLotteryPaths.ready());
        assertFalse(PlayerLotteryStore.get().isTakeoverActive());
    }

    @Test
    void startupScrubsLegacyBalancesFromPrimaryAndRecoveryFiles() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID primaryId = UUID.randomUUID();
        UUID backupOnlyId = UUID.randomUUID();
        Path primary = WorldLotteryPaths.playerFile(primaryId);
        Path primaryBak = AtomicJsonFiles.bakPath(primary);
        Path backupOnly = WorldLotteryPaths.playerFile(backupOnlyId);
        Path backupOnlyBak = AtomicJsonFiles.bakPath(backupOnly);

        Files.writeString(primary,
                "{\"version\":\"invalid\",\"lootChance\":9,\"coinNum\":42,\"greenApples\":-3}",
                StandardCharsets.UTF_8);
        Files.writeString(primaryBak,
                "{\"version\":1,\"lootChance\":7,\"coinNum\":1}",
                StandardCharsets.UTF_8);
        Files.writeString(backupOnlyBak,
                "{\"version\":0,\"lootChance\":2,\"coinNum\":5}",
                StandardCharsets.UTF_8);

        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.onServerStarted(null);

        assertTrue(store.isTakeoverActive());
        for (Path file : new Path[]{primary, primaryBak, backupOnlyBak}) {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(json.contains("lootChance"), file.toString());
            assertFalse(json.contains("coinNum"), file.toString());
            assertTrue(json.contains("\"version\":2"), file.toString());
        }
        assertTrue(Files.readString(primary, StandardCharsets.UTF_8).contains("\"greenApples\":0"));
        assertFalse(Files.exists(backupOnly), "A backup-only account must not invent a primary file");
    }

    @Test
    void unreadableLegacyFilePreventsAccountTakeover() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        Path file = WorldLotteryPaths.playerFile(id);
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);

        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertThrows(IllegalStateException.class, () -> store.onServerStarted(null));
        assertFalse(store.isTakeoverActive());
    }

    @Test
    void nonRegularRecoveryFilePreventsAccountTakeover() throws Exception {
        WorldLotteryPaths.initForTests(temp);
        UUID id = UUID.randomUUID();
        Path file = WorldLotteryPaths.playerFile(id);
        Files.writeString(file, "{\"version\":2,\"greenApples\":4}", StandardCharsets.UTF_8);
        Files.createDirectory(AtomicJsonFiles.bakPath(file));

        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertThrows(IllegalStateException.class, () -> store.onServerStarted(null));
        assertFalse(store.isTakeoverActive());
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

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

class AtomicJsonFilesTornBackupBootTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void validPrimaryWithTornBakSelfHealsOnServerStarted() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path primary = WorldLotteryPaths.playerFile(uuid);
        Path bak = AtomicJsonFiles.bakPath(primary);
        Files.createDirectories(primary.getParent());

        Files.writeString(primary, "{\"version\":2,\"greenApples\":250,\"coinNum\":99}", StandardCharsets.UTF_8);
        Files.writeString(bak, "{\"version\":2,\"greenApples\":10", StandardCharsets.UTF_8);

        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.onServerStarted(null);

        assertFalse(store.isFlushDeferred(), "Torn .bak beside valid primary must self-heal instead of deferring flush");
        assertEquals(250, store.getGreenApples(uuid));
        assertTrue(Files.isRegularFile(primary));
        assertTrue(Files.isRegularFile(bak));

        String healedBak = Files.readString(bak, StandardCharsets.UTF_8);
        assertFalse(healedBak.contains("coinNum"));
        assertTrue(healedBak.contains("250"));

        try (var stream = Files.list(primary.getParent())) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString()
                    .startsWith(bak.getFileName().toString() + ".corrupt-")));
        }
    }

    @Test
    void missingPrimaryWithValidBakHealsBeforeListingPlayers() throws Exception {
        UUID uuid = UUID.randomUUID();
        Path primary = WorldLotteryPaths.playerFile(uuid);
        Path bak = AtomicJsonFiles.bakPath(primary);
        Files.createDirectories(primary.getParent());

        Files.writeString(bak, "{\"version\":2,\"greenApples\":88}", StandardCharsets.UTF_8);

        PlayerLotteryStore store = PlayerLotteryStore.get();
        store.onServerStarted(null);

        assertFalse(store.isFlushDeferred());
        assertTrue(Files.isRegularFile(primary), "A valid backup must heal the primary before offline scans");
        assertEquals(com.google.gson.JsonParser.parseString(Files.readString(bak, StandardCharsets.UTF_8)),
                com.google.gson.JsonParser.parseString(Files.readString(primary, StandardCharsets.UTF_8)));
        assertTrue(store.listPlayers(null).stream().anyMatch(r -> uuid.toString().equals(r.uuid)));
        assertEquals(88, store.getGreenApples(uuid));
    }

    @Test
    void boundedReceiptsAndOfflineCacheEvictionCapMemoryGrowth() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID target = UUID.randomUUID();

        assertTrue(store.applyToPlayerWithRollback(target, data -> {
            for (int i = 0; i < 600; i++) {
                data.rewardReceipts.add("r-" + i);
            }
            for (int i = 0; i < 1100; i++) {
                data.crateOpenHistory.add("o-" + i);
            }
        }));

        store.reset();
        PlayerLotteryData reloaded = store.getOrLoad(target);
        assertEquals(PlayerLotteryData.REWARD_RECEIPTS_CAP, reloaded.rewardReceipts.size());
        assertFalse(reloaded.rewardReceipts.contains("r-0"));
        assertTrue(reloaded.rewardReceipts.contains("r-599"));

        assertEquals(PlayerLotteryData.CRATE_OPEN_HISTORY_CAP, reloaded.crateOpenHistory.size());
        assertFalse(reloaded.crateOpenHistory.contains("o-0"));
        assertTrue(reloaded.crateOpenHistory.contains("o-1099"));

        for (int i = 0; i < 80; i++) {
            store.getOrLoad(UUID.randomUUID());
        }
        assertTrue(store.cachedCountForTest() <= 64,
                "Clean offline cache must stay bounded at <= 64 entries, got " + store.cachedCountForTest());
    }

    @Test
    void worldLotteryPathsRejectsTraversalSegments() {
        Path base = temp.resolve("sub");
        assertThrows(IllegalArgumentException.class, () -> WorldLotteryPaths.resolveUnder(base, "../escape"));
        assertThrows(IllegalArgumentException.class, () -> WorldLotteryPaths.resolveUnder(base, "..\\escape"));
        assertThrows(IllegalArgumentException.class, () -> WorldLotteryPaths.resolveChildFile(base, "../escape.json"));
        assertThrows(IllegalArgumentException.class, () -> WorldLotteryPaths.resolveChildFile(base, "nested/file.json"));
        assertThrows(IllegalArgumentException.class, () -> WorldLotteryPaths.resolveChildFile(base, "C:file.json"));
        assertNotNull(WorldLotteryPaths.resolveChildFile(base, "valid.json"));
    }

    @Test
    void backupCopyTreeSkipsTempFiles() throws Exception {
        Path src = temp.resolve("source_root");
        Files.createDirectories(src.resolve("players"));
        Files.writeString(src.resolve("players/a.json"), "{\"greenApples\":5}", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("players/.a.json.123.tmp"), "{half-written", StandardCharsets.UTF_8);

        Path dest = temp.resolve("backup_out").resolve("snap-1");
        LotteryBackupService.copyTree(src, dest);

        assertTrue(Files.isRegularFile(dest.resolve("players/a.json")));
        assertFalse(Files.exists(dest.resolve("players/.a.json.123.tmp")));
    }
}

package com.habitrain.lottery.network;

import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminBackupOnlyDiscoveryTest {
    @TempDir Path root;

    @Test void discoversBackupOnlyAccountsWithoutLoadingEveryAccountAndDeduplicatesCopies() throws Exception {
        WorldLotteryPaths.initForTests(root);
        var store = PlayerLotteryStore.get();
        store.reset();
        try {
            UUID player = UUID.randomUUID();
            Path primary = WorldLotteryPaths.playerFile(player);
            Files.writeString(AtomicJsonFiles.bakPath(primary), "{\"version\":2,\"greenApples\":37}");
            var stubs = LotteryAdminServerHandlers.collectPlayerStubs(null);
            assertEquals(1, stubs.size());
            assertEquals(player, stubs.getFirst().uuid());
            assertTrue(LotteryAdminServerHandlers.matchesQuery(stubs.getFirst(), player.toString()));
            assertFalse(Files.exists(primary), "Discovery must remain cheap; hydration restores the file");
            assertEquals(37, store.getGreenApples(player));
            assertTrue(Files.isRegularFile(primary));
            assertEquals(1, LotteryAdminServerHandlers.collectPlayerStubs(null).size());
        } finally {
            store.reset();
            WorldLotteryPaths.clear();
        }
    }
}

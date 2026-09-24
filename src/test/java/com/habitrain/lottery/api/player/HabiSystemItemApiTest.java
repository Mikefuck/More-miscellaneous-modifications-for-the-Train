package com.habitrain.lottery.api.player;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HabiSystemItemApiTest {
    @TempDir Path temp;
    private final ResourceLocation item = ResourceLocation.parse("events:ticket");
    @BeforeEach void setup() {
        PlayerLotteryStore.get().reset(); WorldLotteryPaths.clear(); WorldLotteryPaths.initForTests(temp);
        PlayerLotteryStore.get().onServerStarted(null);
    }
    @AfterEach void cleanup() { PlayerLotteryStore.get().reset(); WorldLotteryPaths.clear(); }
    @Test void grantPersistsAcrossReloadAndConsumeIsExact() {
        UUID player = UUID.randomUUID();
        assertTrue(HabiSystemItemApi.grant(player, item, 7).ok());
        assertTrue(Files.isRegularFile(WorldLotteryPaths.playerFile(player)));
        PlayerLotteryStore.get().reset(); PlayerLotteryStore.get().onServerStarted(null);
        assertEquals(7, HabiSystemItemApi.balances(player).get(item.toString()));
        assertFalse(HabiSystemItemApi.consume(player, item, 8).ok());
        assertEquals(7, HabiSystemItemApi.balances(player).get(item.toString()));
        assertTrue(HabiSystemItemApi.consume(player, item, 7).ok());
        assertTrue(HabiSystemItemApi.balances(player).isEmpty());
    }
    @Test void failedWriteRestoresTheOriginalBalance() throws Exception {
        UUID player = UUID.randomUUID();
        PlayerLotteryStore.get().getOrLoad(player).systemItems.put(item.toString(), 4);
        // A nonempty directory at the expected file path guarantees an atomic write failure.
        Path path = WorldLotteryPaths.playerFile(player);
        Files.createDirectories(path); Files.writeString(path.resolve("keep"), "fixture");
        assertEquals(HabiFailure.WRITE_FAILED, HabiSystemItemApi.grant(player, item, 2).failure());
        assertEquals(4, HabiSystemItemApi.balances(player).get(item.toString()));
    }
    @Test void noWritesBeforeReadyAndNoInvalidGrants() {
        UUID player = UUID.randomUUID();
        assertFalse(HabiSystemItemApi.grant(player, item, 0).ok());
        assertFalse(HabiSystemItemApi.consume(player, item, -1).ok());
        WorldLotteryPaths.clear();
        assertEquals(HabiFailure.NOT_READY, HabiSystemItemApi.grant(player, item, 1).failure());
    }
    @Test void deferredWritesCannotReportDurableSuccess() {
        UUID player = UUID.randomUUID();
        PlayerLotteryStore.get().beginDeferredFlush();
        try {
            assertEquals(HabiFailure.NOT_READY, HabiSystemItemApi.grant(player, item, 1).failure());
            assertTrue(HabiSystemItemApi.balances(player).isEmpty());
        } finally { PlayerLotteryStore.get().endDeferredFlush(); }
    }
}

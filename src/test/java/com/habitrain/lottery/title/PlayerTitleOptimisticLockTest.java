package com.habitrain.lottery.title;

import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.ClientLotteryState;
import com.habitrain.lottery.network.LotteryTitleServerHandlers;
import com.habitrain.lottery.network.TitlePlayerModifyC2S;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code I-10}: optimistic concurrency control ({@code expectedRevision}) on
 * per-player {@link PlayerTitleData} mutations via {@link LotteryTitleServerHandlers#modifyPlayerTitleOptimistically}
 * and {@link TitlePlayerModifyC2S}.
 */
class PlayerTitleOptimisticLockTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        WorldLotteryPaths.initForTest(tempDir);
        TitlePaths.ensureDirs();
        LocalTitleStore.get().reset();
        ClientLotteryState.resetAll();
    }

    @AfterEach
    void tearDown() {
        ClientLotteryState.resetAll();
        LocalTitleStore.get().reset();
        WorldLotteryPaths.reset();
    }

    @Test
    void legacyPlayerJsonDefaultsRevisionZeroAndMonotonicallyAdvancesAcrossMutations() throws Exception {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        String legacyJson = "{\"owned\":[\"§a列车乘客\"],\"current\":\"§a列车乘客\",\"updatedAt\":100}";
        Files.writeString(TitlePaths.playerFile(playerId), legacyJson, StandardCharsets.UTF_8);

        PlayerTitleData loaded = LocalTitleStore.get().loadPlayer(playerId);
        assertEquals(0L, loaded.revision, "Legacy player JSON without revision must default to 0");
        assertEquals(List.of("§a列车乘客"), loaded.owned);

        PlayerTitleData cloned = loaded.copy();
        cloned.revision = 99L;
        assertTrue(loaded.contentEquals(cloned),
                "contentEquals must ignore revision/updatedAt when checking semantic changes");
        assertEquals(99L, cloned.copy().revision, "copy() must preserve revision");

        // 1) Grant a new title at expectedRevision = 0 -> advances revision to 1
        var grant = LotteryTitleServerHandlers.modifyPlayerTitleOptimistically(
                playerId, null, "grant", "§6星穹领航员", 0L);
        assertTrue(grant.ok(), grant.message());
        assertFalse(grant.conflict());
        assertEquals(1L, grant.currentRevision());
        assertEquals(1L, LocalTitleStore.get().loadPlayer(playerId).revision);

        // 2) Stale concurrent admin tries to clear at expectedRevision = 0 -> CONFLICT!
        var staleClear = LotteryTitleServerHandlers.modifyPlayerTitleOptimistically(
                playerId, null, "clear", "", 0L);
        assertFalse(staleClear.ok());
        assertTrue(staleClear.conflict(), "Stale expectedRevision must be flagged as conflict");
        assertEquals(1L, staleClear.currentRevision());
        assertEquals("message.habitrain_lottery.admin.title.player_conflict", staleClear.translationKey());
        assertEquals(List.of("§a列车乘客", "§6星穹领航员"),
                LocalTitleStore.get().loadPlayer(playerId).owned,
                "Rejected stale mutation must leave player titles intact on disk");

        // 3) Equip at expectedRevision = 1 -> advances revision to 2
        var equip = LotteryTitleServerHandlers.modifyPlayerTitleOptimistically(
                playerId, null, "set_current", "§6星穹领航员", 1L);
        assertTrue(equip.ok(), equip.message());
        assertEquals(2L, equip.currentRevision());
        assertEquals("§6星穹领航员", LocalTitleStore.get().loadPlayer(playerId).current);

        // 4) Revoke at expectedRevision = 2 -> advances revision to 3
        var revoke = LotteryTitleServerHandlers.modifyPlayerTitleOptimistically(
                playerId, null, "revoke", "§a列车乘客", 2L);
        assertTrue(revoke.ok(), revoke.message());
        assertEquals(3L, revoke.currentRevision());
        assertEquals(List.of("§6星穹领航员"), LocalTitleStore.get().loadPlayer(playerId).owned);

        // 5) Clear at expectedRevision = 3 -> advances revision to 4
        var clear = LotteryTitleServerHandlers.modifyPlayerTitleOptimistically(
                playerId, null, "clear", "", 3L);
        assertTrue(clear.ok(), clear.message());
        assertEquals(4L, clear.currentRevision());
        assertTrue(LocalTitleStore.get().loadPlayer(playerId).owned.isEmpty());
    }

    @Test
    void corruptPlayerTitleFileBlocksOptimisticModifyWithoutOverwritingDisk() throws Exception {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        String corruptJson = "{\"owned\":[\"§d珍贵绝版称号\"";
        Path file = TitlePaths.playerFile(playerId);
        Files.writeString(file, corruptJson, StandardCharsets.UTF_8);

        var outcome = LotteryTitleServerHandlers.modifyPlayerTitleOptimistically(
                playerId, null, "grant", "§e新称号", 0L);

        assertFalse(outcome.ok());
        assertFalse(outcome.conflict());
        assertEquals("message.habitrain_lottery.admin.title.player_corrupt", outcome.translationKey());
        assertFalse(Files.exists(file),
                "Corrupt player title file must NEVER be replaced with a fresh empty-owned JSON file");
        try (var stream = Files.list(file.getParent())) {
            List<Path> quarantined = stream
                    .filter(p -> p.getFileName().toString().startsWith(playerId + ".json.corrupt-"))
                    .toList();
            assertEquals(1, quarantined.size(), "Corrupt player title file must be preserved in quarantine");
            assertEquals(corruptJson, Files.readString(quarantined.getFirst(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void modifyPayloadCodecAndClientSummaryCacheRoundTripExpectedRevision() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000303");
        TitlePlayerModifyC2S wire = new TitlePlayerModifyC2S("grant", playerId.toString(), "§b开拓者", 17L);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            TitlePlayerModifyC2S.CODEC.encode(buf, wire);
            TitlePlayerModifyC2S decoded = TitlePlayerModifyC2S.CODEC.decode(buf);
            assertEquals("grant", decoded.mode());
            assertEquals(playerId.toString(), decoded.targetUuid());
            assertEquals("§b开拓者", decoded.payload());
            assertEquals(17L, decoded.expectedRevision());
        } finally {
            buf.release();
        }

        assertEquals(OptionalLong.empty(), LotteryClientNetwork.cachedTitlePlayerRevision(playerId.toString()));
        ClientLotteryState.titlePlayersJson = "{\"" + playerId + "\":{\"name\":\"March7th\",\"online\":true,"
                + "\"owned\":[\"§b开拓者\"],\"current\":\"§b开拓者\",\"revision\":17}}";
        assertEquals(OptionalLong.of(17L), LotteryClientNetwork.cachedTitlePlayerRevision(playerId.toString()));
    }
}

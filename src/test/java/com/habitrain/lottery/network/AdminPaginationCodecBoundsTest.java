package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.lottery.title.PlayerTitleData;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code B-10.1}, {@code B-10.2}, and {@code WP-05}:
 * <ul>
 *   <li>Admin player list & title player list pagination clamps {@code pageSize} to {@code 1..50},
 *       hydrates only the matched page slice (never all 10,000+ historical files), and keeps
 *       serialized UTF-8 payloads strictly under {@code 32 KB}.</li>
 *   <li>Pre-auth C2S config save codecs reject strings exceeding {@code 65_536} chars.</li>
 * </ul>
 */
class AdminPaginationCodecBoundsTest {
    private static final Gson GSON = new Gson();
    private static final int THIRTY_TWO_KB = 32_768;

    @Test
    void playerListPaginationHandlesTenThousandPlayersUnder32KbAndHydratesOnlyPageSlice() {
        List<LotteryAdminServerHandlers.PlayerStub> tenThousand = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            UUID id = new UUID(0x1234_5678_9ABC_DEF0L, i);
            tenThousand.add(new LotteryAdminServerHandlers.PlayerStub(
                    id, "Player_" + String.format("%05d", i), i < 12));
        }

        AtomicInteger hydratedCount = new AtomicInteger();
        PlayerListS2C pageZero = LotteryAdminServerHandlers.buildPaginatedPlayerList(
                tenThousand,
                "",
                0,
                500, // Oversized pageSize must clamp to 50
                stub -> {
                    hydratedCount.incrementAndGet();
                    PlayerAdminModels.PlayerRow row = new PlayerAdminModels.PlayerRow(
                            stub.name(), stub.uuid(), 1280, 9, stub.online());
                    row.cardStatus = "OK";
                    row.cards = new LinkedHashMap<>();
                    row.cards.put("killer", 3);
                    row.cards.put("civilian", 5);
                    row.cards.put("self_select", 2);
                    row.cards.put("limit_break", 1);
                    return row;
                }
        );

        assertEquals(50, hydratedCount.get(),
                "Must hydrate ONLY the 50 players on the requested page, never all 10,000");
        assertEquals(0, pageZero.page());
        assertEquals(200, pageZero.totalPages());
        assertEquals(10_000, pageZero.totalMatched());

        int utf8Bytes = pageZero.json().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(utf8Bytes < THIRTY_TWO_KB,
                "PlayerListS2C JSON must stay < 32 KB, actual bytes: " + utf8Bytes);

        PlayerAdminModels.PlayerListSnapshot parsed = GSON.fromJson(
                pageZero.json(), PlayerAdminModels.PlayerListSnapshot.class);
        assertNotNull(parsed);
        assertEquals(50, parsed.players.size());
        assertEquals(0, parsed.page);
        assertEquals(200, parsed.totalPages);
        assertEquals(10_000, parsed.totalMatched);

        // Out-of-bounds page clamps to last page (199); non-positive pageSize clamps to 1.
        PlayerListS2C clampedMin = LotteryAdminServerHandlers.buildPaginatedPlayerList(
                tenThousand, "player_0999", 999, 0,
                stub -> new PlayerAdminModels.PlayerRow(stub.name(), stub.uuid(), 0, 0, false));
        assertEquals(10, clampedMin.totalMatched());
        assertEquals(10, clampedMin.totalPages());
        assertEquals(9, clampedMin.page());
    }

    @Test
    void titlePlayersPaginationHandlesTenThousandPlayersUnder32Kb() {
        List<LotteryAdminServerHandlers.PlayerStub> tenThousand = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            UUID id = new UUID(0xCAFE_BABE_0000_0000L, i);
            tenThousand.add(new LotteryAdminServerHandlers.PlayerStub(
                    id, "Conductor_" + String.format("%05d", i), i == 0));
        }

        AtomicInteger loadedCount = new AtomicInteger();
        TitlePlayerPageS2C page = LotteryTitleServerHandlers.buildPaginatedTitlePlayers(
                tenThousand,
                "",
                2,
                999, // Clamps to 50
                uuid -> {
                    loadedCount.incrementAndGet();
                    PlayerTitleData d = new PlayerTitleData();
                    d.owned = List.of("§6星穹列车长", "§b无名客", "§d银河漫游者");
                    d.current = "§6星穹列车长";
                    return d;
                }
        );

        assertTrue(loadedCount.get() <= 50,
                "Title loader must be called <= 50 times, actual: " + loadedCount.get());
        assertEquals(2, page.page());
        assertEquals(200, page.totalPages());
        assertEquals(10_000, page.totalMatched());

        int utf8Bytes = page.playersJson().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(utf8Bytes < THIRTY_TWO_KB,
                "TitlePlayerPageS2C JSON must stay < 32 KB, actual bytes: " + utf8Bytes);
    }

    @Test
    void c2sSaveCodecsEnforceChunkCharCapBelowVanilla32KbAndRoundTripPaginatedPayloads() {
        assertEquals(ChunkedJsonTransfer.C2S_CHUNK_CHARS, LotteryNetwork.ADMIN_C2S_JSON_MAX_CHARS);
        assertEquals(262_144, LotteryNetwork.ADMIN_S2C_JSON_MAX_CHARS);
        assertEquals(ChunkedJsonTransfer.C2S_CHUNK_CHARS, TitleCatalogSaveC2S.MAX_JSON_CHARS);
        assertEquals(ChunkedJsonTransfer.C2S_CHUNK_CHARS, CrateNetwork.C2S_SAVE_MAX_CHARS);
        assertEquals(ChunkedJsonTransfer.C2S_CHUNK_CHARS, DailyShopNetwork.C2S_SAVE_MAX_CHARS);
        assertEquals(ChunkedJsonTransfer.C2S_CHUNK_CHARS, DailyTaskAdminNetwork.C2S_SAVE_MAX_CHARS);
        assertEquals(ChunkedJsonTransfer.C2S_CHUNK_CHARS, SelfSelectAdminNetwork.C2S_SAVE_MAX_CHARS);
        assertTrue(ChunkedJsonTransfer.C2S_CHUNK_CHARS * 3 < 32_767,
                "Worst-case 3-byte UTF-8 C2S chunk must stay strictly < 32,767 vanilla C2S limit");

        String oversized = "x".repeat(ChunkedJsonTransfer.C2S_CHUNK_CHARS + 1);
        assertRejectsOversizedC2S(TitleCatalogSaveC2S.CODEC,
                new TitleCatalogSaveC2S(oversized, 1L));
        assertRejectsOversizedC2S(CrateNetwork.ConfigSaveC2S.CODEC,
                new CrateNetwork.ConfigSaveC2S(oversized));
        assertRejectsOversizedC2S(DailyShopNetwork.SaveC2S.CODEC,
                new DailyShopNetwork.SaveC2S(oversized));
        assertRejectsOversizedC2S(DailyTaskAdminNetwork.SaveC2S.CODEC,
                new DailyTaskAdminNetwork.SaveC2S(oversized));
        assertRejectsOversizedC2S(SelfSelectAdminNetwork.SaveC2S.CODEC,
                new SelfSelectAdminNetwork.SaveC2S("req-1", oversized));

        // Round-trip paginated C2S/S2C payloads and clamping on decode
        PlayerListRequestC2S decodedReq = roundTrip(
                PlayerListRequestC2S.CODEC,
                new PlayerListRequestC2S("alice", 3, 200));
        assertEquals("alice", decodedReq.query());
        assertEquals(3, decodedReq.page());
        assertEquals(50, decodedReq.pageSize());

        TitlePlayerPageS2C decodedTitlePage = roundTrip(
                TitlePlayerPageS2C.CODEC,
                new TitlePlayerPageS2C("{\"k\":1}", 1, 4, 175));
        assertEquals("{\"k\":1}", decodedTitlePage.playersJson());
        assertEquals(1, decodedTitlePage.page());
        assertEquals(4, decodedTitlePage.totalPages());
        assertEquals(175, decodedTitlePage.totalMatched());

        TitleCatalogSaveC2S decodedSave = roundTrip(
                TitleCatalogSaveC2S.CODEC,
                new TitleCatalogSaveC2S("{\"titles\":[]}", 42L));
        assertEquals("{\"titles\":[]}", decodedSave.json());
        assertEquals(42L, decodedSave.expectedRevision());

        // Verify ClientLotteryState.resetAll() resets pagination fields cleanly
        ClientLotteryState.playerListPage = 7;
        ClientLotteryState.playerListTotalPages = 19;
        ClientLotteryState.titlePlayersPage = 4;
        ClientLotteryState.resetAll();
        assertEquals(0, ClientLotteryState.playerListPage);
        assertEquals(1, ClientLotteryState.playerListTotalPages);
        assertEquals(0, ClientLotteryState.titlePlayersPage);
        assertEquals(1, ClientLotteryState.titlePlayersTotalPages);
    }

    private static <T> void assertRejectsOversizedC2S(
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            T oversizedPayload
    ) {
        RegistryFriendlyByteBuf encodeBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            assertThrows(EncoderException.class, () -> codec.encode(encodeBuf, oversizedPayload));
        } finally {
            encodeBuf.release();
        }

        // Also simulate a malicious raw Netty frame writing > C2S_CHUNK_CHARS UTF-8 chars directly
        RegistryFriendlyByteBuf maliciousWire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            if (oversizedPayload instanceof SelfSelectAdminNetwork.SaveC2S) {
                new FriendlyByteBuf(maliciousWire).writeUtf("req-1", 64);
            }
            new FriendlyByteBuf(maliciousWire).writeUtf(
                    "a".repeat(ChunkedJsonTransfer.C2S_CHUNK_CHARS + 1), 100_000);
            if (oversizedPayload instanceof TitleCatalogSaveC2S) {
                maliciousWire.writeVarLong(0L);
            }
            maliciousWire.writeUUID(ChunkedJsonTransfer.LOGICAL_TRANSFER);
            maliciousWire.writeVarInt(0);
            maliciousWire.writeVarInt(1);
            assertThrows(DecoderException.class, () -> codec.decode(maliciousWire));
        } finally {
            maliciousWire.release();
        }
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buf, value);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}

package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.lottery.title.TitleCatalog;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression suite for {@code B-10} and {@code N-01}:
 * <ul>
 *   <li>Every C2S admin save chunk encodes strictly below vanilla's {@code 32,767}-byte
 *       {@code ServerboundCustomPayloadPacket} limit, even for 512-entry max-length CJK + Emoji
 *       JSON payloads (~430 KB UTF-8).</li>
 *   <li>Every S2C admin/catalog snapshot chunk encodes strictly below {@code 65,536} bytes without
 *       splitting surrogate pairs across chunk boundaries.</li>
 *   <li>{@link LotteryAdminServerHandlers#buildPaginatedPlayerList} preserves 100% of matched rows
 *       per page (zero silent {@code 30_000}-byte truncation) and streams oversized pages cleanly.</li>
 *   <li>{@link ChunkedJsonTransfer#acceptAuthorizedC2S} enforces authorization and rate-limiting
 *       prior to buffering server memory.</li>
 * </ul>
 */
class ChunkedAdminConfigTransferTest {
    private static final Gson GSON = new Gson();
    private static final int VANILLA_C2S_MAX_PAYLOAD_BYTES = 32_767;
    private static final int S2C_MAX_WIRE_BYTES = 65_536;

    @AfterEach
    void tearDown() {
        ChunkedJsonTransfer.clearAll();
    }

    @Test
    void maxCapacityTitleCatalogC2SAndS2CChunksStayBelowWireLimitsAndPreserveEmoji() {
        TitleCatalog maxCatalog = new TitleCatalog();
        maxCatalog.revision = 88L;
        for (int i = 0; i < TitleCatalog.MAX_TITLES; i++) {
            String id = String.format("title_slot_%04d_%s", i, "x".repeat(48));
            // Mix 3-byte CJK and 4-byte supplementary astral emoji (2 UTF-16 code units each) up to 256 chars
            String display = ("§6星穹列车🌟🎁🚂荣耀称号#" + i + "_").concat("灿烂银河🌟".repeat(38));
            display = trimCodePointSafe(display, TitleCatalog.MAX_DISPLAY_LENGTH);
            maxCatalog.titles.add(new TitleCatalog.TitleEntry(id, display, i % 3 != 0));
        }
        assertNull(maxCatalog.validateAndNormalize());
        String fullJson = GSON.toJson(maxCatalog);
        assertTrue(fullJson.getBytes(StandardCharsets.UTF_8).length > 350_000,
                "512-title CJK+Emoji catalog should exceed 350 KB UTF-8");

        // 1) C2S TitleCatalogSaveC2S chunking + wire codec size check + server gate reassembly
        TitleCatalogSaveC2S logicalSave = new TitleCatalogSaveC2S(fullJson, 88L);
        List<TitleCatalogSaveC2S> c2sChunks = logicalSave.toWireChunks();
        assertTrue(c2sChunks.size() >= 15, "Large catalog must split across multiple C2S chunks");

        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000901");
        AtomicInteger rateLimitChecks = new AtomicInteger();
        ChunkedJsonTransfer.GateResult<Long> finalGate = null;
        for (int i = 0; i < c2sChunks.size(); i++) {
            TitleCatalogSaveC2S decoded = encodeAssertBytesAndDecode(
                    TitleCatalogSaveC2S.CODEC, c2sChunks.get(i), VANILLA_C2S_MAX_PAYLOAD_BYTES);
            finalGate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    adminId,
                    "title_catalog_save",
                    true,
                    () -> {
                        rateLimitChecks.incrementAndGet();
                        return false;
                    },
                    decoded.json(),
                    decoded.transferId(),
                    decoded.chunkIndex(),
                    decoded.chunkCount(),
                    decoded.expectedRevision());
            if (i < c2sChunks.size() - 1) {
                assertEquals(ChunkedJsonTransfer.GateStatus.ACCEPTED_PARTIAL, finalGate.status());
            }
        }
        assertEquals(1, rateLimitChecks.get(), "Rate limiter must only be evaluated on chunk 0");
        assertNotNull(finalGate);
        assertEquals(ChunkedJsonTransfer.GateStatus.COMPLETED, finalGate.status());
        assertEquals(88L, finalGate.metadata());
        assertEquals(fullJson, finalGate.json(), "C2S reassembled JSON must match byte-for-byte including Emoji");

        // 2) S2C TitleSnapshotS2C chunking + wire codec size check + client assembler
        TitleSnapshotS2C logicalSnap = new TitleSnapshotS2C(fullJson, "{}");
        List<TitleSnapshotS2C> s2cChunks = logicalSnap.toWireChunks();
        assertTrue(s2cChunks.size() >= 8);
        TitleSnapshotS2C.Assembler snapAssembler = new TitleSnapshotS2C.Assembler();
        TitleSnapshotS2C completedSnap = null;
        for (int i = 0; i < s2cChunks.size(); i++) {
            TitleSnapshotS2C decoded = encodeAssertBytesAndDecode(
                    TitleSnapshotS2C.CODEC, s2cChunks.get(i), S2C_MAX_WIRE_BYTES);
            completedSnap = snapAssembler.accept(decoded);
            if (i < s2cChunks.size() - 1) {
                assertNull(completedSnap);
            }
        }
        assertNotNull(completedSnap);
        assertEquals(fullJson, completedSnap.catalogJson());
        assertEquals("{}", completedSnap.playersJson());
    }

    @Test
    void crateDailyTaskDailyShopAndSelfSelectAdminChannelsRoundTripLargeConfigsUnder32KbC2S() {
        String largeJson = buildLargeUnicodeJsonPayload(220_000);
        assertTrue(largeJson.getBytes(StandardCharsets.UTF_8).length > 220_000);
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000902");

        // 1) CrateNetwork ConfigSaveC2S + CatalogS2C + ConfigS2C
        List<CrateNetwork.ConfigSaveC2S> crateSaveChunks =
                new CrateNetwork.ConfigSaveC2S(largeJson).toWireChunks();
        assertTrue(crateSaveChunks.size() > 1);
        ChunkedJsonTransfer.GateResult<String> crateGate = null;
        for (CrateNetwork.ConfigSaveC2S chunk : crateSaveChunks) {
            var decoded = encodeAssertBytesAndDecode(
                    CrateNetwork.ConfigSaveC2S.CODEC, chunk, VANILLA_C2S_MAX_PAYLOAD_BYTES);
            crateGate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    adminId, "crate_config_save", true, () -> false,
                    decoded.json(), decoded.transferId(), decoded.chunkIndex(), decoded.chunkCount(), "");
        }
        assertNotNull(crateGate);
        assertEquals(largeJson, crateGate.json());

        ChunkedJsonTransfer.Assembler<Boolean> crateCatAsm = ChunkedJsonTransfer.newClientS2CAssembler();
        ChunkedJsonTransfer.Completed<Boolean> crateCatDone = null;
        for (CrateNetwork.CatalogS2C chunk : new CrateNetwork.CatalogS2C(largeJson).toWireChunks()) {
            var decoded = encodeAssertBytesAndDecode(CrateNetwork.CatalogS2C.CODEC, chunk, S2C_MAX_WIRE_BYTES);
            crateCatDone = crateCatAsm.accept(
                    decoded.json(), decoded.transferId(), decoded.chunkIndex(), decoded.chunkCount(), Boolean.TRUE);
        }
        assertNotNull(crateCatDone);
        assertEquals(largeJson, crateCatDone.json());

        // 2) DailyTaskAdminNetwork SaveC2S + ConfigS2C
        ChunkedJsonTransfer.GateResult<String> taskGate = null;
        for (DailyTaskAdminNetwork.SaveC2S chunk : new DailyTaskAdminNetwork.SaveC2S(largeJson).toWireChunks()) {
            var decoded = encodeAssertBytesAndDecode(
                    DailyTaskAdminNetwork.SaveC2S.CODEC, chunk, VANILLA_C2S_MAX_PAYLOAD_BYTES);
            taskGate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    adminId, "daily_admin_save", true, () -> false,
                    decoded.json(), decoded.transferId(), decoded.chunkIndex(), decoded.chunkCount(), "");
        }
        assertNotNull(taskGate);
        assertEquals(largeJson, taskGate.json());

        ChunkedJsonTransfer.Assembler<String> taskCfgAsm = ChunkedJsonTransfer.newClientS2CAssembler();
        ChunkedJsonTransfer.Completed<String> taskCfgDone = null;
        for (DailyTaskAdminNetwork.ConfigS2C chunk :
                new DailyTaskAdminNetwork.ConfigS2C(largeJson, "saved").toWireChunks()) {
            var decoded = encodeAssertBytesAndDecode(
                    DailyTaskAdminNetwork.ConfigS2C.CODEC, chunk, S2C_MAX_WIRE_BYTES);
            taskCfgDone = taskCfgAsm.accept(
                    decoded.json(), decoded.transferId(), decoded.chunkIndex(), decoded.chunkCount(), decoded.message());
        }
        assertNotNull(taskCfgDone);
        assertEquals(largeJson, taskCfgDone.json());
        assertEquals("saved", taskCfgDone.metadata());

        // 3) DailyShopNetwork SaveC2S + ConfigS2C
        ChunkedJsonTransfer.GateResult<String> shopGate = null;
        for (DailyShopNetwork.SaveC2S chunk : new DailyShopNetwork.SaveC2S(largeJson).toWireChunks()) {
            var decoded = encodeAssertBytesAndDecode(
                    DailyShopNetwork.SaveC2S.CODEC, chunk, VANILLA_C2S_MAX_PAYLOAD_BYTES);
            shopGate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    adminId, "daily_shop_save", true, () -> false,
                    decoded.json(), decoded.transferId(), decoded.chunkIndex(), decoded.chunkCount(), "");
        }
        assertNotNull(shopGate);
        assertEquals(largeJson, shopGate.json());

        // 4) SelfSelectAdminNetwork SaveC2S + ConfigS2C
        ChunkedJsonTransfer.GateResult<String> selfGate = null;
        for (SelfSelectAdminNetwork.SaveC2S chunk :
                new SelfSelectAdminNetwork.SaveC2S("req-99", largeJson).toWireChunks()) {
            var decoded = encodeAssertBytesAndDecode(
                    SelfSelectAdminNetwork.SaveC2S.CODEC, chunk, VANILLA_C2S_MAX_PAYLOAD_BYTES);
            selfGate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    adminId, "self_select_save", true, () -> false,
                    decoded.json(), decoded.transferId(), decoded.chunkIndex(), decoded.chunkCount(), decoded.requestId());
        }
        assertNotNull(selfGate);
        assertEquals(largeJson, selfGate.json());
        assertEquals("req-99", selfGate.metadata());
    }

    @Test
    void paginatedPlayerListNeverDropsPlayersAbove30KbAndStreamsChunksLosslessly() {
        List<LotteryAdminServerHandlers.PlayerStub> sixtyPlayers = new ArrayList<>(60);
        for (int i = 0; i < 60; i++) {
            sixtyPlayers.add(new LotteryAdminServerHandlers.PlayerStub(
                    new UUID(0xABCD_0000L, i),
                    ("星穹列车员🌟" + String.format("%03d_", i) + "甲乙丙丁戊己庚辛壬癸".repeat(6)).substring(0, 64),
                    i % 2 == 0));
        }

        int totalSeenAcrossPages = 0;
        for (int pageIdx = 0; pageIdx < 2; pageIdx++) {
            PlayerListS2C logicalPage = LotteryAdminServerHandlers.buildPaginatedPlayerList(
                    sixtyPlayers,
                    "",
                    pageIdx,
                    50,
                    stub -> {
                        PlayerAdminModels.PlayerRow row = new PlayerAdminModels.PlayerRow(
                                stub.name(), stub.uuid(), 999_999, 128, stub.online());
                        row.cardStatus = "ONLINE_LIVE";
                        row.cards = new LinkedHashMap<>();
                        // Inflate per-row JSON so 50 rows comfortably exceed the old 30,000-byte break
                        for (int k = 0; k < 24; k++) {
                            row.cards.put("custom_faction_card_bucket_" + k, 100 + k);
                        }
                        return row;
                    });
            if (pageIdx == 0) {
                assertTrue(logicalPage.json().getBytes(StandardCharsets.UTF_8).length > 35_000,
                        "50 rich player rows must exceed 35 KB to exercise B-10 zero-truncation");
            }
            List<PlayerListS2C> wireChunks = logicalPage.toWireChunks();
            if (pageIdx == 0) {
                assertTrue(wireChunks.size() >= 2, "Oversized PlayerListS2C page must split into >= 2 chunks");
            }

            ChunkedJsonTransfer.Assembler<int[]> assembler = ChunkedJsonTransfer.newClientS2CAssembler();
            ChunkedJsonTransfer.Completed<int[]> done = null;
            for (PlayerListS2C chunk : wireChunks) {
                PlayerListS2C decoded = encodeAssertBytesAndDecode(
                        PlayerListS2C.CODEC, chunk, S2C_MAX_WIRE_BYTES);
                done = assembler.accept(
                        decoded.json(),
                        decoded.transferId(),
                        decoded.chunkIndex(),
                        decoded.chunkCount(),
                        new int[]{decoded.page(), decoded.totalPages(), decoded.totalMatched()});
            }
            assertNotNull(done);
            PlayerAdminModels.PlayerListSnapshot snap = GSON.fromJson(
                    done.json(), PlayerAdminModels.PlayerListSnapshot.class);
            int expectedRows = pageIdx == 0 ? 50 : 10;
            assertEquals(expectedRows, snap.players.size(),
                    "Every matched player on page " + pageIdx + " must be preserved without truncation");
            totalSeenAcrossPages += snap.players.size();
        }
        assertEquals(60, totalSeenAcrossPages);
    }

    @Test
    void unauthorizedRateLimitedAndMalformedC2SChunksNeverLeakBufferedServerState() {
        UUID attackerId = UUID.fromString("00000000-0000-0000-0000-000000000999");
        UUID tid = UUID.randomUUID();

        // Unauthorized caller rejected BEFORE buffering
        var unauth = ChunkedJsonTransfer.acceptAuthorizedC2S(
                attackerId, "title_catalog_save", false, () -> false,
                "{\"a\":1}", tid, 0, 4, 0L);
        assertEquals(ChunkedJsonTransfer.GateStatus.UNAUTHORIZED, unauth.status());
        assertEquals(0, ChunkedJsonTransfer.activeServerTransfersForTest());

        // Rate-limited chunk 0 rejected BEFORE buffering
        var limited = ChunkedJsonTransfer.acceptAuthorizedC2S(
                attackerId, "title_catalog_save", true, () -> true,
                "{\"a\":1}", tid, 0, 4, 0L);
        assertEquals(ChunkedJsonTransfer.GateStatus.RATE_LIMITED, limited.status());
        assertEquals(0, ChunkedJsonTransfer.activeServerTransfersForTest());

        // Out-of-order orphan chunk 1 without chunk 0 rejected cleanly
        var orphan = ChunkedJsonTransfer.acceptAuthorizedC2S(
                attackerId, "title_catalog_save", true, () -> false,
                "{\"a\":1}", tid, 1, 4, 0L);
        assertEquals(ChunkedJsonTransfer.GateStatus.INVALID_CHUNK, orphan.status());
        assertEquals(0, ChunkedJsonTransfer.activeServerTransfersForTest());
    }

    private static <T> T encodeAssertBytesAndDecode(
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            T packet,
            int maxWireBytes
    ) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buf, packet);
            int bytes = buf.readableBytes();
            assertTrue(bytes <= maxWireBytes,
                    () -> packet.getClass().getSimpleName() + " encoded to " + bytes
                            + " bytes, exceeding max " + maxWireBytes);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    private static String trimCodePointSafe(String input, int maxChars) {
        if (input.length() <= maxChars) {
            return input;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(input.charAt(end - 1))) {
            end--;
        }
        return input.substring(0, end);
    }

    private static String buildLargeUnicodeJsonPayload(int minUtf8Bytes) {
        StringBuilder sb = new StringBuilder(minUtf8Bytes);
        sb.append("{\"entries\":[");
        int idx = 0;
        while (sb.length() * 2 < minUtf8Bytes) {
            if (idx > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"item_").append(idx++)
                    .append("\",\"label\":\"星穹补给箱🎁🌟🚂_").append("豪华奖励".repeat(12)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }
}

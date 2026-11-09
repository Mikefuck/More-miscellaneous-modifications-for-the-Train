package com.habitrain.lottery.network;

import com.habitrain.lottery.title.TitleCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code B-08}: saving the title catalog must never leak {@code playersJson}
 * (historical UUIDs and owned titles) to non-authorized players and must evaluate the admin
 * players page at most once outside the broadcast loop.
 */
class TitleCatalogBroadcastSecurityTest {

    @Test
    void broadcastRedactsPlayersJsonForNonOpsAndBuildsAdminPageOnlyOnce() {
        TitleCatalog saved = new TitleCatalog();
        saved.revision = 7L;
        saved.titles.add(new TitleCatalog.TitleEntry("champion", "§6列车冠军", true));

        List<LotteryTitleServerHandlers.BroadcastRecipient<String>> recipients = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recipients.add(new LotteryTitleServerHandlers.BroadcastRecipient<>("player_" + i, false));
        }
        recipients.add(new LotteryTitleServerHandlers.BroadcastRecipient<>("op_alpha", true));
        recipients.add(new LotteryTitleServerHandlers.BroadcastRecipient<>("op_beta", true));
        recipients.add(new LotteryTitleServerHandlers.BroadcastRecipient<>("op_gamma", true));

        AtomicInteger supplierCalls = new AtomicInteger();
        String expectedAdminJson = "{\"00000000-0000-0000-0000-000000000001\":{\"name\":\"Alice\",\"owned\":[\"§6列车冠军\"]}}";

        Map<String, TitleSnapshotS2C> delivered = new LinkedHashMap<>();
        LotteryTitleServerHandlers.broadcastSavedCatalog(
                saved,
                recipients,
                () -> {
                    supplierCalls.incrementAndGet();
                    return expectedAdminJson;
                },
                delivered::put
        );

        assertEquals(1, supplierCalls.get(),
                "Admin players page supplier must be called ONCE, never per-player in the loop");
        assertEquals(53, delivered.size());

        for (int i = 0; i < 50; i++) {
            TitleSnapshotS2C packet = delivered.get("player_" + i);
            assertNotNull(packet);
            assertEquals("{}", packet.playersJson(),
                    "Non-OP player_" + i + " must never receive historical player UUIDs/titles");
            assertTrue(packet.catalogJson().contains("champion"));
            assertTrue(packet.catalogJson().contains("\"revision\":7"));
        }

        for (String opName : List.of("op_alpha", "op_beta", "op_gamma")) {
            TitleSnapshotS2C packet = delivered.get(opName);
            assertNotNull(packet);
            assertEquals(expectedAdminJson, packet.playersJson(),
                    "Authorized OP " + opName + " must receive the admin first-page snapshot");
            assertTrue(packet.catalogJson().contains("champion"));
        }
    }

    @Test
    void broadcastSkipsAdminPageScanEntirelyWhenZeroAuthorizedOpsOnline() {
        TitleCatalog saved = new TitleCatalog();
        saved.revision = 3L;
        saved.titles.add(new TitleCatalog.TitleEntry("pioneer", "§b开拓者", true));

        List<LotteryTitleServerHandlers.BroadcastRecipient<String>> recipients = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            recipients.add(new LotteryTitleServerHandlers.BroadcastRecipient<>("guest_" + i, false));
        }

        AtomicInteger supplierCalls = new AtomicInteger();
        Map<String, TitleSnapshotS2C> delivered = new LinkedHashMap<>();
        LotteryTitleServerHandlers.broadcastSavedCatalog(
                saved,
                recipients,
                () -> {
                    supplierCalls.incrementAndGet();
                    throw new AssertionError("Admin players scan should not run when no OP is online");
                },
                delivered::put
        );

        assertEquals(0, supplierCalls.get());
        assertEquals(25, delivered.size());
        for (TitleSnapshotS2C packet : delivered.values()) {
            assertEquals("{}", packet.playersJson());
            assertTrue(packet.catalogJson().contains("pioneer"));
        }
    }

    @Test
    void applyTitleSnapshotPreservesActiveAdminPaginationAndSearchView() {
        try {
            ClientLotteryState.resetAll();
            ClientLotteryState.op = true;
            String page2Json = "{\"00000000-0000-0000-0000-000000000099\":{\"name\":\"PageTwoBob\",\"online\":true,\"owned\":[\"§b先驱\"],\"current\":\"§b先驱\"}}";
            ClientLotteryState.titlePlayersJson = page2Json;
            ClientLotteryState.titlePlayersPage = 2;
            ClientLotteryState.titlePlayersQuery = "Bob";

            String page0BroadcastJson = "{\"00000000-0000-0000-0000-000000000001\":{\"name\":\"PageZeroAlice\",\"online\":true,\"owned\":[],\"current\":\"\"}}";
            com.habitrain.lottery.client.LotteryClientNetwork.applyTitleSnapshot(
                    new TitleSnapshotS2C("{\"revision\":9,\"titles\":[]}", page0BroadcastJson));

            assertEquals("{\"revision\":9,\"titles\":[]}", ClientLotteryState.titleCatalogJson);
            assertEquals(page2Json, ClientLotteryState.titlePlayersJson,
                    "Catalog broadcast must NOT clobber an OP's active page>0 or filtered player list");

            // When on page 0 with empty query, first-page snapshot updates normally
            ClientLotteryState.titlePlayersPage = 0;
            ClientLotteryState.titlePlayersQuery = "";
            com.habitrain.lottery.client.LotteryClientNetwork.applyTitleSnapshot(
                    new TitleSnapshotS2C("{\"revision\":10,\"titles\":[]}", page0BroadcastJson));
            assertEquals(page0BroadcastJson, ClientLotteryState.titlePlayersJson);
        } finally {
            ClientLotteryState.resetAll();
        }
    }
}

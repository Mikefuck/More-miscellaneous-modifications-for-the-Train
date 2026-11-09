package com.habitrain.lottery.backpack;

import com.google.gson.Gson;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackpackReceiptRetirementTest {
    @TempDir Path root;
    private final UUID player = UUID.randomUUID();
    private final Gson gson = new Gson();

    @AfterEach void cleanup() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test void keepsBackupOnlyReservationsAndEveryPendingReceiptBeyondThreshold() throws Exception {
        WorldLotteryPaths.initForTests(root);
        PlayerLotteryData data = new PlayerLotteryData();
        Set<String> receipts = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String activation = UUID.randomUUID().toString();
            data.pendingCardUses.put(activation, new PlayerLotteryData.CardUse("self_select", null, 0));
            receipts.add("card-debit:" + activation);
        }
        Path file = WorldLotteryPaths.playerFile(player);
        assertTrue(AtomicJsonFiles.writeJson(file, data, gson, true));
        assertTrue(AtomicJsonFiles.writeJson(file, new PlayerLotteryData(), gson, true));
        assertEquals(receipts, BackpackReceiptRetirement.retained(player, receipts),
                "A recoverable .bak can still request a refund for every debit");
        assertTrue(AtomicJsonFiles.writeJson(file, new PlayerLotteryData(), gson, true));
        assertTrue(BackpackReceiptRetirement.retained(player, receipts).isEmpty());
        Files.writeString(AtomicJsonFiles.bakPath(file), "broken backup");
        assertEquals(receipts, BackpackReceiptRetirement.retained(player, receipts));
    }

    @Test void ordinaryCompletedCardHistoryStopsGrowingButUnknownKeysRemainIdempotent() {
        WorldLotteryPaths.initForTests(root);
        var store = PlayerLotteryStore.get();
        assertTrue(store.applyToPlayerWithRollback(player, data -> data.greenApples = 1));
        for (int i = 0; i < 200; i++) {
            assertTrue(LocalBackpackStore.applyOnce(player, "card-debit:" + UUID.randomUUID(), data -> { }));
        }
        assertTrue(LocalBackpackStore.loadResult(player).rewardReceipts().size() < 64);
        assertTrue(LocalBackpackStore.applyOnce(player, "external:stable", data -> data.selfSelectCards++));
        assertTrue(LocalBackpackStore.applyOnce(player, "external:stable", data -> fail("Must retain unknown integration evidence")));
        assertEquals(1, LocalBackpackStore.selfSelectCards(player));
    }

    @Test void deliveryFilesAndUnclaimedMailBackupPinReceiptsUntilBothSourcesFinish() throws Exception {
        WorldLotteryPaths.initForTests(root);
        Path account = WorldLotteryPaths.playerFile(player);
        assertTrue(AtomicJsonFiles.writeJson(account, new PlayerLotteryData(), gson, true));
        String receipt = "mail:" + UUID.randomUUID();
        Set<String> receipts = padding(receipt);
        String id = receipt.substring(5);
        Path mailbox = com.habitrain.lottery.storage.MetaFeaturePaths.mailPlayer(player);
        assertTrue(AtomicJsonFiles.writeString(mailbox, "{\"mails\":[{\"id\":\"" + id + "\",\"claimed\":false}]}",
                java.nio.charset.StandardCharsets.UTF_8, true, false));
        assertTrue(AtomicJsonFiles.writeString(mailbox, "{\"mails\":[]}", java.nio.charset.StandardCharsets.UTF_8, true, true));
        assertTrue(BackpackReceiptRetirement.retained(player, receipts).contains(receipt));
        assertTrue(AtomicJsonFiles.writeString(mailbox, "{\"mails\":[]}", java.nio.charset.StandardCharsets.UTF_8, true, true));
        Path intent = WorldLotteryPaths.root().resolve("reward-deliveries").resolve(player.toString())
                .resolve(UUID.nameUUIDFromBytes(receipt.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
        Files.createDirectories(intent.getParent());
        Files.writeString(AtomicJsonFiles.bakPath(intent), "{\"complete\":false}");
        assertTrue(BackpackReceiptRetirement.retained(player, receipts).contains(receipt));
        Files.delete(AtomicJsonFiles.bakPath(intent));
        assertFalse(BackpackReceiptRetirement.retained(player, receipts).contains(receipt));
    }

    @Test void crateStateBackupPinsReceiptAndDailyClaimsMustBeDurableInBothCopies() throws Exception {
        WorldLotteryPaths.initForTests(root);
        String openId = UUID.randomUUID().toString();
        String crate = "crate:" + openId;
        String daily = "habitrain_lottery:daily_task:test:100";
        Set<String> receipts = padding(crate);
        receipts.add(daily);
        Path account = WorldLotteryPaths.playerFile(player);
        PlayerLotteryData data = new PlayerLotteryData();
        data.dailyTaskEpochDay = 100;
        assertTrue(AtomicJsonFiles.writeJson(account, data, gson, true));
        data.dailyTaskClaims.add("habitrain_lottery:test");
        assertTrue(AtomicJsonFiles.writeJson(account, data, gson, true));
        Path state = WorldLotteryPaths.configFile("crates.json");
        assertTrue(AtomicJsonFiles.writeString(state, "{\"pendingOpens\":{\"" + openId + "\":{}}}",
                java.nio.charset.StandardCharsets.UTF_8, true, false));
        assertTrue(AtomicJsonFiles.writeString(state, "{\"pendingOpens\":{}}", java.nio.charset.StandardCharsets.UTF_8, true, true));
        assertTrue(BackpackReceiptRetirement.retained(player, receipts).containsAll(Set.of(crate, daily)));
        assertTrue(AtomicJsonFiles.writeJson(account, data, gson, true));
        assertTrue(AtomicJsonFiles.writeString(state, "{\"pendingOpens\":{}}", java.nio.charset.StandardCharsets.UTF_8, true, true));
        Set<String> compacted = BackpackReceiptRetirement.retained(player, receipts);
        assertFalse(compacted.contains(crate));
        assertFalse(compacted.contains(daily));
    }

    private static Set<String> padding(String receipt) {
        Set<String> receipts = new HashSet<>();
        for (int i = 0; i < 64; i++) receipts.add("external:" + i);
        receipts.add(receipt);
        return receipts;
    }
}

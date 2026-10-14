package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactionCardRefundsTest {
    @TempDir Path root;
    private final UUID player = UUID.randomUUID();
    private final PlayerLotteryStore store = PlayerLotteryStore.get();

    @BeforeEach void setup() {
        store.reset();
        ActiveCardForces.clear();
        WorldLotteryPaths.initForTests(root);
        assertTrue(store.applyToPlayerWithRollback(player, data -> {
            data.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            data.factionCardUsesToday = 1;
        }));
    }

    @AfterEach void cleanup() {
        store.reset();
        ActiveCardForces.clear();
        WorldLotteryPaths.clear();
    }

    @Test void oldRefundCannotConsumeLaterDifferentCard() throws Exception {
        assertIndependentRefund(FactionCardType.KILLER);
    }

    @Test void oldRefundCannotConsumeLaterUseOfSameCard() throws Exception {
        assertIndependentRefund(FactionCardType.CIVILIAN);
    }

    private void assertIndependentRefund(FactionCardType nextType) throws Exception {
        Path blocker = blockBackup(WorldLotteryPaths.playerFile(player));
        ActiveCardForces.record(player, FactionCardType.CIVILIAN);
        UUID first = ActiveCardForces.refundId(player);
        assertFalse(CardUseService.refundForcedCard(player));
        assertEquals(first, ActiveCardForces.refundId(player), "Retry must retain the activation ID");
        assertEquals(1, cards(FactionCardType.CIVILIAN));
        restart();
        FactionCardRefunds.recover(); // Still cannot save the quota at startup.
        unblock(blocker);
        assertTrue(store.applyToPlayerWithRollback(player, data -> data.factionCardUsesToday++));

        ActiveCardForces.record(player, nextType);
        assertNotEquals(first, ActiveCardForces.refundId(player));
        assertTrue(CardUseService.refundForcedCard(player));
        assertNull(ActiveCardForces.get(player));
        assertEquals(nextType == FactionCardType.CIVILIAN ? 2 : 1, cards(nextType));
        assertEquals(1, store.getOrLoad(player).factionCardUsesToday, "Only the old quota refund is still pending");

        FactionCardRefunds.recover();
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
        assertEquals(nextType == FactionCardType.CIVILIAN ? 2 : 1, cards(nextType));
    }

    @Test void twoPendingUsesSurviveRestartAndSpentRewardsAreNotRestored() throws Exception {
        Path blocker = blockBackup(WorldLotteryPaths.playerFile(player));
        ActiveCardForces.record(player, FactionCardType.CIVILIAN);
        assertFalse(CardUseService.refundForcedCard(player));
        restart();
        FactionCardRefunds.recover();
        store.update(player, data -> data.factionCardUsesToday++);
        ActiveCardForces.record(player, FactionCardType.KILLER);
        assertFalse(CardUseService.refundForcedCard(player));
        assertEquals(1, cards(FactionCardType.KILLER));
        assertTrue(LocalBackpackStore.addCards(player, FactionCardType.CIVILIAN, -1));
        unblock(blocker);
        assertTrue(store.flush(player));
        restart();
        FactionCardRefunds.recover();
        FactionCardRefunds.recover();
        assertEquals(0, cards(FactionCardType.CIVILIAN));
        assertEquals(1, cards(FactionCardType.KILLER));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
    }

    @Test void legacyBackupIsRecoveredSeparatelyFromNewActivation() throws Exception {
        String receipt = "faction-refund:" + UUID.randomUUID();
        Path legacy = WorldLotteryPaths.root().resolve("faction-refunds").resolve(player + ".json");
        assertTrue(AtomicJsonFiles.writeJson(AtomicJsonFiles.bakPath(legacy), intent(receipt), new Gson(), true));
        assertTrue(LocalBackpackStore.applyOnce(player, receipt, data -> data.cards.put("civilian", 1)));
        assertTrue(store.applyToPlayerWithRollback(player, data -> data.factionCardUsesToday++));

        ActiveCardForces.record(player, FactionCardType.KILLER);
        assertTrue(CardUseService.refundForcedCard(player));
        assertEquals(1, cards(FactionCardType.KILLER));
        restart();
        FactionCardRefunds.recover();
        FactionCardRefunds.recover();
        assertEquals(1, cards(FactionCardType.CIVILIAN));
        assertEquals(1, cards(FactionCardType.KILLER));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
        assertTrue(new Gson().fromJson(Files.readString(legacy), JsonObject.class).get("complete").getAsBoolean());
    }

    @Test void finalIntentWriteFailureDoesNotRepeatRefundAfterRestart() throws Exception {
        ActiveCardForces.record(player, FactionCardType.CIVILIAN);
        UUID activation = ActiveCardForces.refundId(player);
        Path file = WorldLotteryPaths.root().resolve("faction-refunds").resolve(player.toString()).resolve(activation + ".json");
        assertTrue(AtomicJsonFiles.writeJson(file, intent("faction-refund:" + activation), new Gson(), true));
        Path blocker = blockBackup(file);
        assertFalse(CardUseService.refundForcedCard(player));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
        assertTrue(LocalBackpackStore.addCards(player, FactionCardType.CIVILIAN, -1));
        restart();
        unblock(blocker);
        FactionCardRefunds.recover();
        assertEquals(0, cards(FactionCardType.CIVILIAN));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
    }

    private JsonObject intent(String receipt) {
        JsonObject intent = new JsonObject();
        intent.addProperty("receipt", receipt);
        intent.addProperty("type", "civilian");
        intent.addProperty("day", LoginRewardService.todayEpochDayUtc());
        intent.addProperty("complete", false);
        return intent;
    }

    private int cards(FactionCardType type) { return LocalBackpackStore.load(player).get(type.questKey); }

    private void restart() {
        ActiveCardForces.clear();
        store.reset();
    }

    private static Path blockBackup(Path file) throws Exception {
        Path blocker = AtomicJsonFiles.bakPath(file);
        Files.deleteIfExists(blocker);
        Files.createDirectory(blocker);
        Files.writeString(blocker.resolve("blocked"), "simulate temporary I/O failure");
        return blocker;
    }

    private static void unblock(Path blocker) throws Exception {
        Files.delete(blocker.resolve("blocked"));
        Files.delete(blocker);
    }
}

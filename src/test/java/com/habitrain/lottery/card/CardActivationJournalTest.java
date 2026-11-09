package com.habitrain.lottery.card;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.FactionCardRoleTypes;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CardActivationJournalTest {
    @TempDir Path root;
    final UUID player = UUID.randomUUID();
    final PlayerLotteryStore store = PlayerLotteryStore.get();

    @BeforeEach void setup() {
        WorldLotteryPaths.initForTests(root);
        store.reset();
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(LocalBackpackStore.save(player, Map.of("killer", 2)));
        assertTrue(LocalBackpackStore.setSelfSelectCards(player, 2));
    }

    @AfterEach void cleanup() {
        ActiveCardForces.clear();
        SelfSelectForces.clear();
        store.reset();
        WorldLotteryPaths.clear();
        LotteryDayClock.resetAll();
    }

    @Test void startupRefundsFactionAndSelfSelectExactlyOnceAfterCommittedDebit() {
        for (String kind : new String[]{"killer", "self_select"}) {
            UUID id = CardActivationJournal.activate(player, kind, "sre:killer");
            assertNotNull(id);
            assertNull(CardActivationJournal.activate(player, kind, "sre:killer"));
            store.reset(); // abrupt stop: no force registry or shutdown callbacks
            CardActivationJournal.recoverAll();
            CardActivationJournal.recoverAll();
            assertFalse(CardActivationJournal.hasPending(player));
            assertEquals(2, LocalBackpackStore.selfSelectCards(player));
            assertEquals(2, LocalBackpackStore.loadResult(player).cards().get("killer"));
            assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
            assertEquals(0, store.getOrLoad(player).selfSelectUsesToday);
        }
    }

    @Test void crashAfterQuotaReservationBeforeDebitDoesNotCreateAFreeCard() {
        UUID id = UUID.randomUUID();
        assertTrue(store.applyToPlayerWithRollback(player, data -> {
            data.lastSelfSelectUseEpochDay = LotteryDayClock.todayEpochDay();
            data.selfSelectUsesToday = 1;
            data.pendingCardUses.put(id.toString(), new PlayerLotteryData.CardUse("self_select", "sre:killer", LotteryDayClock.todayEpochDay()));
        }));
        store.reset();
        CardActivationJournal.recoverAll();
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
        assertEquals(0, store.getOrLoad(player).selfSelectUsesToday);
        assertFalse(CardActivationJournal.hasPending(player));
    }

    @Test void failedAccountReservationCannotDebitTheCard() throws Exception {
        assertTrue(store.applyToPlayerWithRollback(player, data -> data.greenApples = 7));
        Path block = blockBackup(WorldLotteryPaths.playerFile(player));
        assertNull(CardActivationJournal.activate(player, "killer", null));
        assertEquals(2, LocalBackpackStore.loadResult(player).cards().get("killer"));
        assertFalse(CardActivationJournal.hasPending(player));
        unblock(block);
    }

    @Test void failedBackpackDebitRefundsOnlyTheQuota() throws Exception {
        Path block = blockBackup(com.habitrain.lottery.storage.MetaFeaturePaths.backpackPlayer(player));
        assertNull(CardActivationJournal.activate(player, "self_select", "sre:killer"));
        assertFalse(CardActivationJournal.hasPending(player));
        assertEquals(0, store.getOrLoad(player).selfSelectUsesToday);
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
        unblock(block);
    }

    @Test void quotaRefundWriteFailureNeverDuplicatesTheAlreadyRefundedCard() throws Exception {
        UUID id = CardActivationJournal.activate(player, "self_select", "sre:killer");
        assertNotNull(id);
        Path block = blockBackup(WorldLotteryPaths.playerFile(player));
        assertFalse(CardActivationJournal.refund(player, id));
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
        store.reset();
        CardActivationJournal.recoverAll();
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
        unblock(block);
        CardActivationJournal.recoverAll();
        assertFalse(CardActivationJournal.hasPending(player));
        assertEquals(0, store.getOrLoad(player).selfSelectUsesToday);
        assertEquals(2, LocalBackpackStore.selfSelectCards(player));
    }

    @Test void reconnectDoesNotRefundALiveReservationAndSuccessfulSettlementSurvivesRestart() {
        UUID id = CardActivationJournal.activate(player, "killer", null);
        assertNotNull(id);
        ActiveCardForces.restore(player, FactionCardRoleTypes.roleTypeId(FactionCardType.KILLER), id, LotteryDayClock.todayEpochDay());
        CardActivationJournal.recover(player);
        assertEquals(1, LocalBackpackStore.loadResult(player).cards().get("killer"));
        assertTrue(CardActivationJournal.hasPending(player));
        ActiveCardForces.remove(player);
        store.reset();
        CardActivationJournal.recoverAll();
        assertEquals(1, LocalBackpackStore.loadResult(player).cards().get("killer"));
        assertEquals(1, store.getOrLoad(player).factionCardUsesToday);
    }

    @Test void failedSettlementWriteAutomaticallyRefundsOnRestartAsRequested() throws Exception {
        UUID id = CardActivationJournal.activate(player, "killer", null);
        assertNotNull(id);
        Path block = blockBackup(WorldLotteryPaths.playerFile(player));
        CardActivationJournal.settle(player, id);
        assertTrue(CardActivationJournal.hasPending(player));
        unblock(block);
        store.reset();
        CardActivationJournal.recoverAll();
        assertEquals(2, LocalBackpackStore.loadResult(player).cards().get("killer"));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
    }

    private Path blockBackup(Path target) throws Exception {
        Path backup = AtomicJsonFiles.bakPath(target);
        Files.deleteIfExists(backup);
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("blocked"), "IO failure");
        return backup;
    }

    private void unblock(Path backup) throws Exception {
        Files.delete(backup.resolve("blocked"));
        Files.delete(backup);
    }
}

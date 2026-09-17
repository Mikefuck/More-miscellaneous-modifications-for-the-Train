package com.habitrain.lottery.card;

import com.habitrain.lottery.backpack.DailyFactionCardService;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CardUseServiceRefundTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        ActiveCardForces.clear();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach
    void tearDown() {
        ActiveCardForces.clear();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void refundSelfSelectUuidWritesLocalStoreWhenOffline() {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.save(id, LocalBackpackStore.defaultCards()));
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryStore.get().update(id, d -> {
            d.lastFactionCardUseEpochDay = today;
            d.factionCardUsesToday = 2;
            d.lastSelfSelectUseEpochDay = today;
            d.selfSelectUsesToday = 2;
        });

        CardUseService.refundSelfSelect(id, FactionCardType.KILLER);

        String key = FactionCardType.KILLER.questKey.toLowerCase();
        assertEquals(0, LocalBackpackStore.load(id).get(key));
        assertEquals(CardUseService.SELF_SELECT_COST, LocalBackpackStore.selfSelectCards(id));
        assertEquals(2, PlayerLotteryStore.get().getOrLoad(id).factionCardUsesToday);
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(id).selfSelectUsesToday);
    }

    @Test
    void refundForcedCardUuidAddsOneCardWhenOffline() {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.save(id, LocalBackpackStore.defaultCards()));
        ActiveCardForces.record(id, FactionCardType.CIVILIAN);
        assertTrue(CardUseService.refundForcedCard(id));
        String key = FactionCardType.CIVILIAN.questKey.toLowerCase();
        assertEquals(1, LocalBackpackStore.load(id).get(key));
    }

    @Test
    void upstreamRefundAndRepeatedSettlementReturnOnlyTheConsumedCardAndOneUse() {
        UUID id = UUID.randomUUID();
        ActiveCardForces.record(id, FactionCardType.NEUTRAL_FOR_KILLER);
        PlayerLotteryStore.get().update(id, d -> {
            d.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            d.factionCardUsesToday = 3;
        });
        assertTrue(CardUseService.refundForcedCard(id));
        assertFalse(CardUseService.refundForcedCard(id));
        assertEquals(1, LocalBackpackStore.load(id).get("neutral_for_killer"));
        assertEquals(0, LocalBackpackStore.load(id).get("neutral"));
        assertEquals(2, PlayerLotteryStore.get().getOrLoad(id).factionCardUsesToday);
        assertNull(ActiveCardForces.get(id));
    }

    @Test
    void streakForceWithoutConsumedCardCannotCreateARefund() {
        UUID id = UUID.randomUUID();
        assertFalse(CardUseService.refundForcedCard(id));
        assertEquals(LocalBackpackStore.defaultCards(), LocalBackpackStore.load(id));
    }

    @Test
    void laterActivationCanBeRefundedOnceAgain() {
        UUID id = UUID.randomUUID();
        ActiveCardForces.record(id, FactionCardType.CIVILIAN);
        assertTrue(CardUseService.refundForcedCard(id));
        ActiveCardForces.record(id, FactionCardType.KILLER);
        assertTrue(CardUseService.refundForcedCard(id));
        assertFalse(CardUseService.refundForcedCard(id));
        assertEquals(1, LocalBackpackStore.load(id).get("civilian"));
        assertEquals(1, LocalBackpackStore.load(id).get("killer"));
    }
}

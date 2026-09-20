package com.habitrain.lottery.grant;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.storage.MetaFeaturePaths;
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

class FactionCardSettlementTest {
    @TempDir Path temp;

    @BeforeEach void setUp() {
        ActiveCardForces.clear();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach void tearDown() {
        ActiveCardForces.clear();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test void matchingFactionConsumesCardWithoutRefund() {
        UUID id = UUID.randomUUID();
        ActiveCardForces.record(id, FactionCardType.NEUTRAL_FOR_KILLER);
        CardForceGuaranteeHook.settleCard(id, 3);
        CardForceGuaranteeHook.refundPendingCards();
        assertFalse(CardUseService.refundForcedCard(id));
        assertEquals(0, LocalBackpackStore.load(id).get("neutral_for_killer"));
        assertTrue(ActiveCardForces.isEmpty());
    }

    @Test void wrongFactionAndMissingAssignmentRefundIndependently() {
        UUID wrong = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        ActiveCardForces.record(wrong, FactionCardType.NEUTRAL_FOR_KILLER);
        ActiveCardForces.record(absent, FactionCardType.KILLER);
        CardForceGuaranteeHook.settleCard(wrong, 2);
        CardForceGuaranteeHook.settleCard(absent, null);
        CardForceGuaranteeHook.settleCard(wrong, 2);
        CardForceGuaranteeHook.refundPendingCards();
        assertEquals(1, LocalBackpackStore.load(wrong).get("neutral_for_killer"));
        assertEquals(0, LocalBackpackStore.load(wrong).get("neutral"));
        assertEquals(1, LocalBackpackStore.load(absent).get("killer"));
    }

    @Test void upstreamRefundThenFinalMismatchDoesNotRefundTwice() {
        UUID id = UUID.randomUUID();
        ActiveCardForces.record(id, FactionCardType.KILLER);
        assertTrue(CardUseService.refundForcedCard(id));
        CardForceGuaranteeHook.settleCard(id, 1);
        CardForceGuaranteeHook.refundPendingCards();
        assertEquals(1, LocalBackpackStore.load(id).get("killer"));
    }

    @Test void failedOfflineRefundRemainsPendingEvenIfNextAssignmentMatches() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(LocalBackpackStore.save(id, LocalBackpackStore.defaultCards()));
        ActiveCardForces.record(id, FactionCardType.NEUTRAL_FOR_KILLER);
        Path file = MetaFeaturePaths.backpackPlayer(id);
        // 审核 S-01：临时文件名现在是唯一的（<name>.<jvm>-<seq>.tmp），
        // 过去阻塞 <file>.tmp 的做法已不能强制写失败；改为阻塞 .bak
        //（backup=true 且目标已存在时，.bak 复制失败会拒绝替换主文件）。
        Path blocked = com.habitrain.lottery.storage.AtomicJsonFiles.bakPath(file);
        Files.createDirectory(blocked);
        Path blocker = Files.writeString(blocked.resolve("blocker"), "prevent atomic write");
        CardForceGuaranteeHook.settleCard(id, 2);
        assertTrue(ActiveCardForces.refundPending(id));
        Files.delete(blocker);
        Files.delete(blocked);
        CardForceGuaranteeHook.settleCard(id, 3);
        assertEquals(1, LocalBackpackStore.load(id).get("neutral_for_killer"));
        assertTrue(ActiveCardForces.isEmpty());
    }
}

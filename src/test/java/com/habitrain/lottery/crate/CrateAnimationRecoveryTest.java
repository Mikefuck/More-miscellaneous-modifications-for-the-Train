package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CrateAnimationRecoveryTest {
    @TempDir Path temp;
    private final UUID uuid = UUID.randomUUID();
    private final String openId = UUID.randomUUID().toString();
    private String crate, key;

    @BeforeEach void setUp() {
        ServerMatchGate.clearTestOverride();
        LotteryDayClock.resetAll();
        PlayerLotteryStore.get().reset();
        HabiSystemItemApi.clearForTest();
        WorldLotteryPaths.initForTests(temp);
        String previous = HabiSkinApi.beginProvider("animation-recovery-test");
        try { HabiSkinApi.register(SkinDefinition.builder("knife", "animation_skin", SkinQuality.WHITE).build()); }
        finally { HabiSkinApi.endProvider(previous); }
        CrateService.onServerStarted();
        CrateService.CratePool pool = new CrateService.CratePool();
        pool.customPool = true;
        pool.rewardMode = "unified_pool";
        pool.rollCount = 1;
        pool.name = "Woodland";
        pool.keyName = "Woodland Key";
        pool.tier = "white";
        pool.icon = "minecraft:oak_planks";
        pool.keyIcon = "minecraft:tripwire_hook";
        pool.appearancePreset = "woodland";
        pool.badge = "star";
        pool.skinWeights.put("knife/animation_skin", 100);
        CrateService.configurePoolForTest("woodland", pool);
        crate = CrateService.crateItemId("woodland");
        key = CrateService.keyItemId("woodland");
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> {
            data.systemItems.put(crate, 1);
            data.systemItems.put(key, 1);
        }));
    }

    @AfterEach void tearDown() {
        CrateService.onServerStopping();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        CrateCatalog.reset();
        HabiSkinApi.removeProvider("animation-recovery-test");
        HabiSystemItemApi.clearForTest();
        ServerMatchGate.clearTestOverride();
        LotteryDayClock.resetAll();
    }

    private PlayerLotteryData account() { return PlayerLotteryStore.get().getOrLoad(uuid); }
    private CrateService.OpenResult begin() {
        return CrateService.beginAnimationForAccount(uuid, null, "woodland", key, openId);
    }
    private CrateService.OpenResult resolve(boolean complete) {
        return CrateService.resolveAnimationForAccount(uuid, null, openId, complete);
    }
    private void balances(int expected) {
        assertEquals(expected, account().systemItems.getOrDefault(crate, 0));
        assertEquals(expected, account().systemItems.getOrDefault(key, 0));
    }
    private void noReward() {
        assertFalse(PlayerLotteryStore.get().isSkinUnlocked(uuid, "knife", "animation_skin"));
        assertTrue(account().crateOpenHistory.isEmpty());
    }
    private CrateOutputQuota.Progress quota() throws Exception {
        var state = new Gson().fromJson(Files.readString(WorldLotteryPaths.configFile("crates.json")), CrateService.State.class);
        return CrateOutputQuota.progress(state, "woodland", SkinQuality.WHITE,
                CrateOutputQuota.limit(state.crates.get("woodland"), SkinQuality.WHITE));
    }

    @Test void earlyInterruptionRefundsOnceAndCannotReplayCancelledRequest() throws Exception {
        assertTrue(begin().success());
        balances(0);
        noReward();
        assertTrue(account().pendingCrateDebits.contains(openId));
        assertEquals(1, quota().reserved());
        assertEquals(0, quota().produced());
        assertTrue(begin().success());
        balances(0);
        assertEquals("crates.cancelled", resolve(false).message());
        balances(1);
        noReward();
        assertTrue(account().pendingCrateDebits.isEmpty());
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
        assertEquals(0, quota().reserved());
        assertEquals(0, quota().produced());
        assertEquals("crates.cancelled", resolve(false).message());
        assertEquals("crates.cancelled", resolve(true).message());
        assertEquals("crates.cancelled", begin().message());
        balances(1);
    }

    @Test void successBoundaryOrSkipCommitsOnceAndLaterPageChangeCannotRefund() throws Exception {
        assertTrue(begin().success());
        assertTrue(resolve(true).success());
        long revision = account().inventoryRevision;
        assertTrue(resolve(false).success());
        assertTrue(resolve(true).success());
        balances(0);
        assertEquals(revision, account().inventoryRevision);
        assertEquals(1, account().ownedSkinCounts.get("knife/animation_skin"));
        assertTrue(account().pendingCrateDebits.isEmpty());
        assertEquals(1, quota().produced());
        assertEquals(0, quota().reserved());
    }

    @Test void interruptedPreviewDoesNotGrantCurrencyCardsOrDailyProgress() {
        assertTrue(begin().success());
        var pending = CrateService.pendingOpensForTest().get(openId);
        pending.apples = 80;
        pending.rewards = List.of(new CrateService.Reward("green_apples", "green_apples", 80),
                new CrateService.Reward("card", "self_select", 1));
        pending.dailyTaskTargets.put("habitrain_lottery:open_test", 1);
        CrateService.putPendingOpenForTest(pending);
        assertEquals("crates.cancelled", resolve(false).message());
        balances(1);
        assertEquals(0, account().greenApples);
        assertEquals(0, LocalBackpackStore.selfSelectCards(uuid));
        assertTrue(account().dailyTaskProgress.isEmpty());
    }

    @Test void disconnectBeforeBoundaryRefundsWithoutGivingPreviewReward() {
        assertTrue(begin().success());
        CrateService.cancelAnimations(uuid);
        balances(1);
        noReward();
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
    }

    @Test void restartRefundsUnfinishedAnimationInsteadOfCompletingItsRewards() {
        assertTrue(begin().success());
        PlayerLotteryStore.get().reset();
        CrateService.onServerStarted();
        balances(1);
        noReward();
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
        CrateService.onServerStarted();
        balances(1);
    }

    @Test void abandonedAnimationTimeoutReturnsItemsAndReleasesOutputReservation() {
        assertTrue(begin().success());
        var pending = CrateService.pendingOpensForTest().get(openId);
        CrateService.recoverAnimations(pending.animationExpiresAt, null);
        balances(1);
        noReward();
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
        String freshId = UUID.randomUUID().toString();
        assertTrue(CrateService.beginAnimationForAccount(uuid, null, "woodland", key, freshId).success());
    }

    @Test void refundWriteFailureSurvivesRestartAndNeverTurnsIntoReward() throws Exception {
        assertTrue(begin().success());
        Path blocker = blockBackup(WorldLotteryPaths.playerFile(uuid));
        assertEquals("crates.pending", resolve(false).message());
        balances(0);
        noReward();
        assertTrue(CrateService.pendingOpensForTest().get(openId).cancelRequested);
        unblock(blocker);
        PlayerLotteryStore.get().reset();
        CrateService.onServerStarted();
        balances(1);
        noReward();
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
    }

    @Test void refundThenStateCleanupFailureDoesNotRefundAgainAfterRestart() throws Exception {
        assertTrue(begin().success());
        // Persist the cancel decision first, then block only the state cleanup write.
        var pending = CrateService.pendingOpensForTest().get(openId);
        pending.cancelRequested = true;
        CrateService.putPendingOpenForTest(pending);
        Path blocker = blockBackup(WorldLotteryPaths.configFile("crates.json"));
        assertEquals("crates.pending", resolve(false).message());
        balances(1);
        assertTrue(account().cancelledCrateOpens.contains(openId));
        assertEquals("crates.pending", resolve(false).message());
        balances(1);
        unblock(blocker);
        PlayerLotteryStore.get().reset();
        CrateService.onServerStarted();
        balances(1);
        noReward();
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
    }

    @Test void committedCardWriteFailureStaysSuccessIntentAndCannotBeRefunded() throws Exception {
        assertTrue(begin().success());
        var pending = CrateService.pendingOpensForTest().get(openId);
        pending.rewards = List.of(new CrateService.Reward("card", "self_select", 1));
        pending.skinDelta.clear();
        CrateService.putPendingOpenForTest(pending);
        Path cardFile = com.habitrain.lottery.storage.MetaFeaturePaths.backpackPlayer(uuid);
        Files.createDirectories(cardFile);
        assertEquals("crates.pending", resolve(true).message());
        assertFalse(CrateService.pendingOpensForTest().get(openId).awaitingAnimation);
        assertEquals("crates.pending", resolve(false).message());
        balances(0);
        Files.delete(cardFile);
        PlayerLotteryStore.get().reset();
        CrateService.onServerStarted();
        assertEquals(1, LocalBackpackStore.selfSelectCards(uuid));
        balances(0);
        assertTrue(resolve(false).success());
        assertEquals(1, LocalBackpackStore.selfSelectCards(uuid));
    }

    @Test void anotherAccountCannotCompleteOrCancelTheOpening() {
        assertTrue(begin().success());
        var denied = CrateService.resolveAnimationForAccount(UUID.randomUUID(), null, openId, false);
        assertEquals("crates.invalid_request", denied.message());
        balances(0);
        assertTrue(CrateService.pendingOpensForTest().get(openId).awaitingAnimation);
    }

    private static Path blockBackup(Path file) throws Exception {
        Path path = AtomicJsonFiles.bakPath(file);
        Files.deleteIfExists(path);
        Files.createDirectories(path);
        Files.writeString(path.resolve("blocked"), "block replacement");
        return path;
    }
    private static void unblock(Path path) throws Exception {
        Files.delete(path.resolve("blocked"));
        Files.delete(path);
    }
}

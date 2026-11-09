package com.habitrain.lottery.crate;

import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import com.habitrain.lottery.warehouse.SystemItemBalances;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CratePreDebitRecoveryTest {
    private static final String TEST_PROVIDER = "test_crate_predebit_provider";

    @TempDir
    Path temp;

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        LotteryDayClock.resetAll();
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        HabiSystemItemApi.clearForTest();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(temp);

        String prev = HabiSkinApi.beginProvider(TEST_PROVIDER);
        try {
            HabiSkinApi.register(SkinDefinition.builder("knife", "predebit_skin", SkinQuality.WHITE).build());
        } finally {
            HabiSkinApi.endProvider(prev);
        }

        CrateService.onServerStarted();
        CrateService.CratePool pool = new CrateService.CratePool();
        pool.enabled = true;
        pool.customPool = true;
        pool.rewardMode = "unified_pool";
        pool.rollCount = 1;
        pool.minimumSkinCount = 0;
        pool.name = "Woodland";
        pool.keyName = "Woodland Key";
        pool.tier = "white";
        pool.icon = "minecraft:oak_planks";
        pool.keyIcon = "minecraft:tripwire_hook";
        pool.appearancePreset = "woodland";
        pool.badge = "star";
        pool.skinWeights.put("knife/predebit_skin", 100);
        CrateService.configurePoolForTest("woodland", pool);
    }

    @AfterEach
    void tearDown() {
        CrateService.onServerStopping();
        HabiSkinApi.removeProvider(TEST_PROVIDER);
        HabiSystemItemApi.clearForTest();
        LotteryDayClock.resetAll();
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        CrateCatalog.reset();
    }

    @Test
    void interruptedCardDeliveryKeepsChargeAndRecoveryPlanUntilRetry() throws Exception {
        String crateItem = CrateService.crateItemId("woodland");
        String keyItem = CrateService.keyItemId("woodland");
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, d -> {
            SystemItemBalances.change(d.systemItems, crateItem, 1);
            SystemItemBalances.change(d.systemItems, keyItem, 1);
        }));
        CrateService.PendingOpen pending = new CrateService.PendingOpen();
        pending.rewardProtocol = 1;
        pending.openId = UUID.randomUUID().toString();
        pending.playerId = player.toString();
        pending.crateId = "woodland";
        pending.keyId = keyItem;
        pending.primaryQualityId = "white";
        pending.rewards = List.of(new CrateService.Reward("card", "killer", 1));
        CrateService.putPendingOpenForTest(pending);

        // A directory at the target file deterministically fails the card reward write.
        Path cardFile = com.habitrain.lottery.storage.MetaFeaturePaths.backpackPlayer(player);
        java.nio.file.Files.createDirectories(cardFile);
        assertFalse(CrateService.finishPending(pending, null).success());
        var charged = PlayerLotteryStore.get().getOrLoad(player);
        assertEquals(0, charged.systemItems.getOrDefault(crateItem, 0));
        assertEquals(0, charged.systemItems.getOrDefault(keyItem, 0));
        assertTrue(charged.pendingCrateDebits.contains(pending.openId));
        assertFalse(CrateService.pendingOpensForTest().isEmpty());
        java.nio.file.Files.delete(cardFile);

        // Restart from the on-disk plan saved with debited=false. Its account token
        // must prevent a second charge and permit exactly one reward.
        PlayerLotteryStore.get().reset();
        CrateService.onServerStarted();
        assertTrue(CrateService.pendingOpensForTest().isEmpty());
        assertEquals(1, com.habitrain.lottery.backpack.LocalBackpackStore.loadResult(player)
                .cards().getOrDefault("killer", 0));
        assertEquals(0, PlayerLotteryStore.get().getOrLoad(player).systemItems.getOrDefault(keyItem, 0));
    }

    @Test
    void preDebitedPendingOpenCompletesOnRestartEvenWithZeroRemainingItems() {
        String openId = UUID.randomUUID().toString();
        String crateItem = CrateService.crateItemId("woodland");
        String keyItem = CrateService.keyItemId("woodland");

        // Simulate crash right after pre-debit + PendingOpen save (remaining crate & key = 0)
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, data -> {
            data.pendingCrateDebits.add(openId);
            data.systemItems = new LinkedHashMap<>();
        }));

        CrateService.PendingOpen pending = new CrateService.PendingOpen();
        pending.rewardProtocol = 1;
        pending.debited = true;
        pending.openId = openId;
        pending.playerId = player.toString();
        pending.crateId = "woodland";
        pending.keyId = keyItem;
        pending.weekKey = CrateOutputQuota.periodKey(LotteryDayClock.today(), "weekly");
        pending.monthKey = CrateOutputQuota.periodKey(LotteryDayClock.today(), "monthly");
        pending.primaryQualityId = "white";
        pending.rewards = List.of(new CrateService.Reward("skin", "knife/predebit_skin", 1));
        pending.skinDelta = Map.of("knife/predebit_skin", 1);
        pending.outputDelta = Map.of("white", 1L);
        pending.taskEpochDay = LotteryDayClock.todayEpochDay();
        CrateService.putPendingOpenForTest(pending);

        // Simulate server restart recovery
        CrateService.onServerStopping();
        PlayerLotteryStore.get().reset();
        CrateService.onServerStarted();

        assertTrue(CrateService.pendingOpensForTest().isEmpty(), "Recovered pre-debited open must be cleared");
        assertTrue(PlayerLotteryStore.get().isSkinUnlocked(player, "knife", "predebit_skin"),
                "Pre-debited pending open must grant its skin reward on recovery");
        var data = PlayerLotteryStore.get().getOrLoad(player);
        assertFalse(data.pendingCrateDebits.contains(openId));
        assertEquals(0, data.systemItems.getOrDefault(crateItem, 0));
        assertEquals(0, data.systemItems.getOrDefault(keyItem, 0));
    }

    @Test
    void legacyUndebitedPendingOpenWithMissingKeyReleasesQuotaAndUnblocksPlayer() {
        // Set strict weekly output quota = 1 on white quality
        CrateService.CratePool pool = new CrateService.CratePool();
        pool.enabled = true;
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
        pool.skinWeights.put("knife/predebit_skin", 100);
        pool.outputLimits.get("white").limit = 1;
        CrateService.configurePoolForTest("woodland", pool);

        String legacyOpenId = UUID.randomUUID().toString();
        String crateItem = CrateService.crateItemId("woodland");
        String keyItem = CrateService.keyItemId("woodland");

        // Legacy PendingOpen (debited == false) while player has 0 crates/keys
        CrateService.PendingOpen legacy = new CrateService.PendingOpen();
        legacy.rewardProtocol = 1;
        legacy.debited = false;
        legacy.openId = legacyOpenId;
        legacy.playerId = player.toString();
        legacy.crateId = "woodland";
        legacy.keyId = keyItem;
        legacy.weekKey = CrateOutputQuota.periodKey(LotteryDayClock.today(), "weekly");
        legacy.monthKey = CrateOutputQuota.periodKey(LotteryDayClock.today(), "monthly");
        legacy.primaryQualityId = "white";
        legacy.rewards = List.of(new CrateService.Reward("skin", "knife/predebit_skin", 1));
        legacy.skinDelta = Map.of("knife/predebit_skin", 1);
        legacy.outputDelta = Map.of("white", 1L);
        CrateService.putPendingOpenForTest(legacy);

        // Give player 1 fresh crate + 1 key and open a new crate:
        // legacy unfulfillable open was dropped on recovery or first open, quota = 1 is available!
        CrateService.onServerStopping();
        CrateService.onServerStarted();
        assertTrue(CrateService.pendingOpensForTest().isEmpty(),
                "Unfulfillable legacy !debited pending open must be dropped on startup");
        assertFalse(PlayerLotteryStore.get().isSkinUnlocked(player, "knife", "predebit_skin"));

        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, data -> {
            SystemItemBalances.change(data.systemItems, crateItem, 1);
            SystemItemBalances.change(data.systemItems, keyItem, 1);
        }));

        var res = CrateService.openForAccount(player, null, "woodland", keyItem, UUID.randomUUID().toString());
        assertTrue(res.success(), "Player must not be permanently bricked or quota-starved: " + res.message());
        assertTrue(PlayerLotteryStore.get().isSkinUnlocked(player, "knife", "predebit_skin"));
    }

    @Test
    void activeMatchRejectsOpenAndClientCatalogSyncDoesNotClobberServerCatalog() {
        String crateItem = CrateService.crateItemId("woodland");
        String keyItem = CrateService.keyItemId("woodland");
        assertTrue(PlayerLotteryStore.get().applyToPlayerWithRollback(player, data -> {
            SystemItemBalances.change(data.systemItems, crateItem, 1);
            SystemItemBalances.change(data.systemItems, keyItem, 1);
        }));

        ServerMatchGate.setTestOverride(true);
        var blocked = CrateService.openForAccount(player, null, "woodland", keyItem, UUID.randomUUID().toString());
        assertFalse(blocked.success());
        assertEquals("crates.in_match", blocked.message());
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(player).systemItems.getOrDefault(crateItem, 0));
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(player).systemItems.getOrDefault(keyItem, 0));

        // Client S2C sync / DISCONNECT while WorldLotteryPaths.ready() == true must NOT wipe server CrateCatalog
        CrateService.acceptCatalogJson("[]");
        CrateCatalog.reset();
        assertNotNull(CrateCatalog.find("woodland"), "Server CrateCatalog must survive client S2C/DISCONNECT resets");
    }
}

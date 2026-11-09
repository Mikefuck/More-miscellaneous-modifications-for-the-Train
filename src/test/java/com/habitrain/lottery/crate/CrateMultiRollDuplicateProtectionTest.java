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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CrateMultiRollDuplicateProtectionTest {
    private static final String TEST_PROVIDER = "test_crate_multiroll_provider";

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        LotteryDayClock.resetAll();
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        HabiSystemItemApi.clearForTest();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(temp);

        String prev = HabiSkinApi.beginProvider(TEST_PROVIDER);
        try {
            for (int i = 1; i <= 5; i++) {
                HabiSkinApi.register(SkinDefinition.builder("knife", "dup_skin_" + i, SkinQuality.WHITE).build());
            }
        } finally {
            HabiSkinApi.endProvider(prev);
        }

        CrateService.onServerStarted();
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
    void fiveRollOpenTreatsStagedSkinsAsOwnedForDuplicateProtection() {
        CrateService.CratePool pool = new CrateService.CratePool();
        pool.enabled = true;
        pool.customPool = true;
        pool.duplicateProtection = true;
        pool.allowSameSkinInOneOpen = true;
        pool.rewardMode = "unified_pool";
        pool.rollCount = 5;
        pool.minimumSkinCount = 0;
        pool.name = "Woodland";
        pool.keyName = "Woodland Key";
        pool.tier = "white";
        pool.icon = "minecraft:oak_planks";
        pool.keyIcon = "minecraft:tripwire_hook";
        pool.appearancePreset = "woodland";
        pool.badge = "star";
        // Owned skins have huge weight; missing skins have tiny weight 1 so once all missing skins are
        // staged in this open, remaining rolls fall back to the full weighted pool instead of repeating
        // the sole missing skin 5 times!
        pool.skinWeights.put("knife/dup_skin_1", 1_000_000);
        pool.skinWeights.put("knife/dup_skin_2", 1_000_000);
        pool.skinWeights.put("knife/dup_skin_3", 1_000_000);
        pool.skinWeights.put("knife/dup_skin_4", 1);
        pool.skinWeights.put("knife/dup_skin_5", 1);
        CrateService.configurePoolForTest("woodland", pool);

        String crateItem = CrateService.crateItemId("woodland");
        String keyItem = CrateService.keyItemId("woodland");

        // Case 1: Player owns skins 1..3, missing BOTH 4 and 5 -> 5-roll open MUST unlock BOTH 4 and 5!
        UUID twoMissingPlayer = UUID.randomUUID();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertTrue(store.awardSkin(twoMissingPlayer, "knife", "dup_skin_1", 1));
        assertTrue(store.awardSkin(twoMissingPlayer, "knife", "dup_skin_2", 1));
        assertTrue(store.awardSkin(twoMissingPlayer, "knife", "dup_skin_3", 1));
        assertTrue(store.applyToPlayerWithRollback(twoMissingPlayer, data -> {
            SystemItemBalances.change(data.systemItems, crateItem, 1);
            SystemItemBalances.change(data.systemItems, keyItem, 1);
        }));

        var res1 = CrateService.openForAccount(twoMissingPlayer, null, "woodland", keyItem, UUID.randomUUID().toString());
        assertTrue(res1.success(), res1.message());
        assertEquals(5, res1.rewards().size());
        assertTrue(store.isSkinUnlocked(twoMissingPlayer, "knife", "dup_skin_4"));
        assertTrue(store.isSkinUnlocked(twoMissingPlayer, "knife", "dup_skin_5"));

        // Case 2: Player owns skins 1..4, missing ONLY skin 5 -> 5-roll open MUST unlock skin 5 on roll 1,
        // and MUST NOT deal 5 identical copies of skin 5!
        UUID oneMissingPlayer = UUID.randomUUID();
        assertTrue(store.awardSkin(oneMissingPlayer, "knife", "dup_skin_1", 1));
        assertTrue(store.awardSkin(oneMissingPlayer, "knife", "dup_skin_2", 1));
        assertTrue(store.awardSkin(oneMissingPlayer, "knife", "dup_skin_3", 1));
        assertTrue(store.awardSkin(oneMissingPlayer, "knife", "dup_skin_4", 1));
        assertTrue(store.applyToPlayerWithRollback(oneMissingPlayer, data -> {
            SystemItemBalances.change(data.systemItems, crateItem, 1);
            SystemItemBalances.change(data.systemItems, keyItem, 1);
        }));

        var res2 = CrateService.openForAccount(oneMissingPlayer, null, "woodland", keyItem, UUID.randomUUID().toString());
        assertTrue(res2.success(), res2.message());
        assertEquals(5, res2.rewards().size());
        assertTrue(store.isSkinUnlocked(oneMissingPlayer, "knife", "dup_skin_5"));

        long copiesOfSkin5 = res2.rewards().stream()
                .filter(r -> "knife/dup_skin_5".equals(r.id()))
                .count();
        assertEquals(1L, copiesOfSkin5,
                "Missing 1 skin in a 5-roll open must grant that missing skin once, not 5 identical copies");
        Set<String> distinctRewards = res2.rewards().stream().map(CrateService.Reward::id).collect(Collectors.toSet());
        assertTrue(distinctRewards.size() >= 2);
    }
}

package com.habitrain.lottery.daily.shop;

import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.api.player.HabiTitleApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.RewardDelivery;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.TitlePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DailyShopCrashAtomicityTest {
    private static final String TEST_PROVIDER = "test_daily_shop_provider";

    @TempDir
    Path temp;

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        LotteryDayClock.resetAll();
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.initForTests(temp);
        LocalTitleStore.get().reset();

        String prev = HabiSkinApi.beginProvider(TEST_PROVIDER);
        try {
            HabiSkinApi.register(SkinDefinition.builder("knife", "shop_blade", SkinQuality.GOLD).build());
        } finally {
            HabiSkinApi.endProvider(prev);
        }
    }

    @AfterEach
    void tearDown() {
        DailyShopService.onServerStopping();
        HabiSkinApi.removeProvider(TEST_PROVIDER);
        LotteryDayClock.resetAll();
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
        LocalTitleStore.get().reset();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void unpaidArmedShopPlanGrantsNothingAndIsCleanedUpOnRecovery() {
        String unpaidKey = "shop:" + UUID.randomUUID();
        RewardDelivery.Plan plan = new RewardDelivery.Plan(
                List.of(MailReward.skinEntry("knife/shop_blade")),
                List.of("§6[白嫖检测]"),
                null);
        assertTrue(RewardDelivery.armShopPlan(player, unpaidKey, plan));

        // Simulate crash BEFORE PlayerLotteryStore debit committed paidShopPurchases
        assertFalse(RewardDelivery.deliver(player, unpaidKey, () -> null));
        RewardDelivery.recoverPending(player);

        assertFalse(PlayerLotteryStore.get().isSkinUnlocked(player, "knife", "shop_blade"));
        assertFalse(HabiTitleApi.owned(player).contains("§6[白嫖检测]"));

        Path deliveryDir = temp.resolve("reward-deliveries").resolve(player.toString());
        if (Files.isDirectory(deliveryDir)) {
            try (var files = Files.list(deliveryDir)) {
                assertEquals(0L, files.count(), "Unpaid armed shop plan must be deleted during recovery");
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    @Test
    void postDebitPartialFailureRecoversGoodsAndTitlesWithoutDoubleCharging() throws Exception {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        assertTrue(store.applyToPlayerWithRollback(player, d -> d.greenApples = 500));

        // Seed existing title so title .bak replacement is exercised and can be blocked to simulate mid-delivery crash
        assertTrue(LocalTitleStore.get().grant(player, "§7[初始称号]"));
        Path titleBakBlocker = blockBackup(TitlePaths.playerFile(player));

        DailyShopConfig cfg = new DailyShopConfig();
        DailyShopItem bundle = new DailyShopItem();
        bundle.id = "bundle_1";
        bundle.title = "金刀称号礼包";
        bundle.price = 300;
        bundle.enabled = true;
        bundle.limitEnabled = true;
        bundle.limitCount = 1;
        bundle.limitDays = 7;
        bundle.rewards.add(new DailyRewardEntry(DailyRewardEntry.SKIN, "knife/shop_blade", 1));
        bundle.rewards.add(new DailyRewardEntry(DailyRewardEntry.TITLE, "§6[金刀之主]", 1));
        cfg.items.add(bundle);
        DailyShopService.setConfigForTest(cfg);

        // Purchase debits 300 apples + records limit counter + commits skin, while title fails on blocked .bak
        assertEquals("ok", DailyShopService.buyForAccount(player, null, "bundle_1", 300));
        assertEquals(200, store.getGreenApples(player));
        assertTrue(store.isSkinUnlocked(player, "knife", "shop_blade"));
        assertFalse(HabiTitleApi.owned(player).contains("§6[金刀之主]"));
        assertEquals(1, store.getOrLoad(player).paidShopPurchases.size(),
                "paidShopPurchases token must stay persisted while downstream title step is incomplete");

        // Even if recovery runs again while title .bak is still blocked, token must NOT be retired prematurely!
        RewardDelivery.recoverPending(player);
        assertEquals(1, store.getOrLoad(player).paidShopPurchases.size(),
                "paidShopPurchases token must survive failed retry attempts");

        // Limit counter is already persisted: trying to buy again is sold_out
        assertEquals("sold_out", DailyShopService.buyForAccount(player, null, "bundle_1", 300));
        assertEquals(200, store.getGreenApples(player));

        // Simulate server restart & unblock title .bak -> login recovery grants the missing title without charging again
        store.reset();
        LocalTitleStore.get().reset();
        unblock(titleBakBlocker);

        RewardDelivery.recoverPending(player);

        assertEquals(200, store.getGreenApples(player));
        assertTrue(store.isSkinUnlocked(player, "knife", "shop_blade"));
        assertTrue(HabiTitleApi.owned(player).contains("§6[金刀之主]"));
        assertTrue(store.getOrLoad(player).paidShopPurchases.isEmpty(),
                "paidShopPurchases token must be retired after full delivery completion");
    }

    @Test
    void zeroPriceLimitedItemPersistsCounterAcrossRestart() {
        DailyShopConfig cfg = new DailyShopConfig();
        DailyShopItem freeGift = new DailyShopItem();
        freeGift.id = "free_self_select";
        freeGift.title = "免费自选卡";
        freeGift.price = 0;
        freeGift.enabled = true;
        freeGift.limitEnabled = true;
        freeGift.limitCount = 1;
        freeGift.limitDays = 1;
        freeGift.rewards.add(new DailyRewardEntry(DailyRewardEntry.SELF_SELECT, "", 1));
        cfg.items.add(freeGift);
        DailyShopService.setConfigForTest(cfg);

        assertEquals("ok", DailyShopService.buyForAccount(player, null, "free_self_select", 0));
        assertEquals(1, LocalBackpackStore.selfSelectCards(player));

        // Evict cache and reload from disk to prove 0-price limit counter was durably flushed
        PlayerLotteryStore.get().reset();
        assertEquals("sold_out", DailyShopService.buyForAccount(player, null, "free_self_select", 0));
        assertEquals(1, LocalBackpackStore.selfSelectCards(player));
    }

    @Test
    void activeMatchBlocksCardShopItemsWithoutCharging() {
        PlayerLotteryStore.get().applyToPlayerWithRollback(player, d -> d.greenApples = 400);

        DailyShopConfig cfg = new DailyShopConfig();
        DailyShopItem cardItem = new DailyShopItem();
        cardItem.id = "card_offer";
        cardItem.title = "自选卡礼包";
        cardItem.price = 200;
        cardItem.enabled = true;
        cardItem.rewards.add(new DailyRewardEntry(DailyRewardEntry.SELF_SELECT, "", 1));
        cfg.items.add(cardItem);
        DailyShopService.setConfigForTest(cfg);

        ServerMatchGate.setTestOverride(true);
        assertEquals("in_match", DailyShopService.buyForAccount(player, null, "card_offer", 200));
        assertEquals(400, PlayerLotteryStore.get().getGreenApples(player));
        assertEquals(0, LocalBackpackStore.selfSelectCards(player));
    }

    @Test
    void soldOutAndInsufficientRollbacksCleanUpArmedShopPlanFiles() throws Exception {
        PlayerLotteryStore.get().applyToPlayerWithRollback(player, d -> d.greenApples = 100);

        DailyShopConfig cfg = new DailyShopConfig();
        DailyShopItem expensive = new DailyShopItem();
        expensive.id = "expensive_skin";
        expensive.title = "高价金刀";
        expensive.price = 250;
        expensive.enabled = true;
        expensive.rewards.add(new DailyRewardEntry(DailyRewardEntry.SKIN, "knife/shop_blade", 1));
        cfg.items.add(expensive);
        DailyShopService.setConfigForTest(cfg);

        assertEquals("insufficient", DailyShopService.buyForAccount(player, null, "expensive_skin", 250));
        assertEquals(100, PlayerLotteryStore.get().getGreenApples(player));

        Path deliveryDir = temp.resolve("reward-deliveries").resolve(player.toString());
        if (Files.isDirectory(deliveryDir)) {
            try (var files = Files.list(deliveryDir)) {
                assertEquals(0L, files.count(), "Insufficient rollback must clean up armed shop plan file");
            }
        }
    }

    private static Path blockBackup(Path file) throws Exception {
        Path dir = AtomicJsonFiles.bakPath(file);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("blocked"), "block");
        return dir;
    }

    private static void unblock(Path dir) throws Exception {
        Files.deleteIfExists(dir.resolve("blocked"));
        Files.deleteIfExists(dir);
    }
}

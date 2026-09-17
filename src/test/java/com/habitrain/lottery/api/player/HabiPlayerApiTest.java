package com.habitrain.lottery.api.player;

import com.habitrain.lottery.mail.LocalMailboxStore;
import com.habitrain.lottery.mail.MailCommandsCodec;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import io.wifi.starrailexpress.util.ItemSkinManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HabiPlayerApiTest {
    private static final String TEST_SKIN = "habitrain_api_player_test_blade";

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTests(temp);
        if (!ItemSkinManager.getSkins("knife").containsKey(TEST_SKIN)) {
            ItemSkinManager.registerACustomSkin("knife", TEST_SKIN, 0xFF00FF00);
        }
    }

    @AfterEach
    void tearDown() {
        LocalTitleStore.get().reset();
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
    }

    @Test
    void currencySetAddAndPersist() {
        UUID id = UUID.randomUUID();
        assertEquals(0, HabiLotteryApi.getCoins(id));
        assertTrue(HabiLotteryApi.setCoins(id, 100).ok());
        assertEquals(100, HabiLotteryApi.getCoins(id));
        assertEquals(0, HabiLotteryApi.addCoins(id, -250).newValue());
        assertTrue(HabiLotteryApi.addDraws(id, 5).ok());
        assertEquals(5, HabiLotteryApi.getDraws(id));
        assertTrue(HabiLotteryApi.setDraws(id, 2).ok());
        assertEquals(2, HabiLotteryApi.getDraws(id));
        assertTrue(HabiLotteryApi.isReady());
    }

    @Test
    void invalidCurrencyValueRejected() {
        UUID id = UUID.randomUUID();
        HabiAssetResult result = HabiLotteryApi.setCoins(id, -1);
        assertFalse(result.ok());
        assertEquals(HabiFailure.INVALID_VALUE, result.failure());
    }

    @Test
    void mutationsFailWhenNotReady() {
        WorldLotteryPaths.clear();
        assertEquals(HabiFailure.NOT_READY, HabiLotteryApi.addCoins(UUID.randomUUID(), 1).failure());
        assertEquals(HabiFailure.NOT_READY,
                HabiCardApi.add(UUID.randomUUID(), HabiCardKind.KILLER, 1).failure());
        assertEquals(HabiFailure.NOT_READY,
                HabiSkinPlayerApi.unlock(UUID.randomUUID(), "knife", TEST_SKIN).failure());
        assertEquals(HabiFailure.NOT_READY,
                HabiTitleApi.grant(UUID.randomUUID(), "t").failure());
    }

    @Test
    void grantDedupesByReason() {
        UUID id = UUID.randomUUID();
        assertTrue(HabiLotteryApi.grantDraws(id, 3, "test:key", true).ok());
        HabiAssetResult second = HabiLotteryApi.grantDraws(id, 3, "test:key", true);
        assertEquals(HabiFailure.DUPLICATE_GRANT, second.failure());
        assertEquals(3, HabiLotteryApi.getDraws(id));
        assertTrue(HabiLotteryApi.grantDraws(id, 3, "test:key", false).ok());
        assertEquals(6, HabiLotteryApi.getDraws(id));
    }

    @Test
    void cardBalancesRoundTrip() {
        UUID id = UUID.randomUUID();
        assertTrue(HabiCardApi.set(id, HabiCardKind.KILLER, 5).ok());
        assertEquals(6, HabiCardApi.all(id).size());
        assertEquals(5, HabiCardApi.all(id).get("killer"));
        assertTrue(HabiCardApi.add(id, HabiCardKind.KILLER, -2).ok());
        assertEquals(3, HabiCardApi.get(id, HabiCardKind.KILLER));
        assertTrue(HabiCardApi.set(id, HabiCardKind.SELF_SELECT, 4).ok());
        assertEquals(4, HabiCardApi.get(id, HabiCardKind.SELF_SELECT));
        assertTrue(HabiCardApi.add(id, HabiCardKind.LIMIT_BREAK, 2).ok());
        assertEquals(2, HabiCardApi.get(id, HabiCardKind.LIMIT_BREAK));
    }

    @Test
    void cardDeltaOutOfRangeRejected() {
        UUID id = UUID.randomUUID();
        assertEquals(HabiFailure.INVALID_VALUE,
                HabiCardApi.add(id, HabiCardKind.KILLER, 1001).failure());
        assertEquals(HabiFailure.INVALID_VALUE,
                HabiCardApi.set(id, HabiCardKind.KILLER, -1).failure());
    }

    @Test
    void dailyQuotasStartFullAndReset() {
        UUID id = UUID.randomUUID();
        assertEquals(HabiCardApi.DAILY_FACTION_CARD_LIMIT, HabiCardApi.dailyFactionCardRemaining(id));
        assertEquals(HabiCardApi.DAILY_SELF_SELECT_LIMIT, HabiCardApi.dailySelfSelectRemaining(id));
        assertTrue(HabiCardApi.resetDailyFactionCardUsage(id).ok());
        assertTrue(HabiCardApi.resetDailySelfSelectUsage(id).ok());
        assertEquals(HabiCardApi.DAILY_FACTION_CARD_LIMIT, HabiCardApi.dailyFactionCardRemaining(id));
    }

    @Test
    void skinsUnlockEquipLock() {
        UUID id = UUID.randomUUID();
        assertFalse(HabiSkinPlayerApi.isUnlocked(id, "knife", TEST_SKIN));
        assertTrue(HabiSkinPlayerApi.unlock(id, "knife", TEST_SKIN).ok());
        assertTrue(HabiSkinPlayerApi.isUnlocked(id, "knife", TEST_SKIN));
        assertTrue(HabiSkinPlayerApi.unlocked(id, "knife").contains(TEST_SKIN));
        assertTrue(HabiSkinPlayerApi.equip(id, "knife", TEST_SKIN).ok());
        assertEquals(TEST_SKIN, HabiSkinPlayerApi.equipped(id, "knife"));
        assertTrue(HabiSkinPlayerApi.lock(id, "knife", TEST_SKIN).ok());
        assertFalse(HabiSkinPlayerApi.isUnlocked(id, "knife", TEST_SKIN));
        assertEquals("default", HabiSkinPlayerApi.equipped(id, "knife"));
    }

    @Test
    void skinValidationRejectsUnknownInputs() {
        UUID id = UUID.randomUUID();
        assertEquals(HabiFailure.SKIN_NOT_UNLOCKED,
                HabiSkinPlayerApi.equip(id, "knife", TEST_SKIN).failure());
        assertEquals(HabiFailure.UNKNOWN_SKIN,
                HabiSkinPlayerApi.unlock(id, "knife", "no_such_skin").failure());
        assertEquals(HabiFailure.UNKNOWN_SKIN_TYPE,
                HabiSkinPlayerApi.unlock(id, "wrench", TEST_SKIN).failure());
        assertEquals(HabiFailure.INVALID_VALUE,
                HabiSkinPlayerApi.unlock(id, "knife", "default").failure());
        assertTrue(HabiSkinPlayerApi.isRegistered("knife", TEST_SKIN));
        assertFalse(HabiSkinPlayerApi.isRegistered("knife", "no_such_skin"));
    }

    @Test
    void titlesGrantEquipRevoke() {
        UUID id = UUID.randomUUID();
        assertTrue(HabiTitleApi.grant(id, "称号A").ok());
        assertTrue(HabiTitleApi.owned(id).contains("称号A"));
        assertTrue(HabiTitleApi.grant(id, "称号A").ok());
        assertEquals(HabiFailure.TITLE_NOT_OWNED, HabiTitleApi.setCurrent(id, "称号B").failure());
        assertTrue(HabiTitleApi.setCurrent(id, "称号A").ok());
        assertEquals("称号A", HabiTitleApi.current(id));
        assertTrue(HabiTitleApi.revoke(id, "称号A").ok());
        assertFalse(HabiTitleApi.owned(id).contains("称号A"));
        assertEquals("", HabiTitleApi.current(id));
        assertTrue(HabiTitleApi.revoke(id, "称号A").ok());
    }

    @Test
    void offlineMailStoresEveryRewardKind() {
        UUID id = UUID.randomUUID();
        MailDraft draft = HabiMailApi.draft("测试", "补偿", "感谢游玩",
                List.of(HabiMailApi.coins(500), HabiMailApi.draws(2),
                        HabiMailApi.factionCard(HabiCardKind.KILLER, 1),
                        HabiMailApi.selfSelectCard(1)));
        assertTrue(HabiMailApi.send(id, "tester", draft));
        var mails = LocalMailboxStore.load(id);
        assertEquals(1, mails.size());
        assertEquals("补偿", mails.get(0).title);
        assertEquals(4, MailCommandsCodec.decode(mails.get(0).commands).size());
    }

    @Test
    void snapshotAggregatesEveryAsset() {
        UUID id = UUID.randomUUID();
        assertTrue(HabiLotteryApi.setCoins(id, 42).ok());
        assertTrue(HabiLotteryApi.setDraws(id, 7).ok());
        assertTrue(HabiCardApi.set(id, HabiCardKind.CIVILIAN, 3).ok());
        assertTrue(HabiSkinPlayerApi.unlock(id, "knife", TEST_SKIN).ok());
        assertTrue(HabiSkinPlayerApi.equip(id, "knife", TEST_SKIN).ok());
        assertTrue(HabiTitleApi.grant(id, "称号A").ok());
        assertTrue(HabiTitleApi.setCurrent(id, "称号A").ok());

        HabiPlayerAssets assets = HabiLotteryApi.snapshot(id);
        assertEquals(42, assets.coins());
        assertEquals(7, assets.draws());
        assertEquals(3, assets.card(HabiCardKind.CIVILIAN));
        assertEquals(TEST_SKIN, assets.equipped("knife"));
        assertTrue(assets.skins("knife").contains(TEST_SKIN));
        assertTrue(assets.titles().contains("称号A"));
        assertEquals("称号A", assets.currentTitle());
        assertFalse(assets.online());
    }
}

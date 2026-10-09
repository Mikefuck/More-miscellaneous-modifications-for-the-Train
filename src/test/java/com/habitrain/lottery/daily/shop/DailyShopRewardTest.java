package com.habitrain.lottery.daily.shop;

import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.mail.MailReward;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DailyShopRewardTest {
    private static final long DAY = 20_370L;

    @AfterEach
    void restoreCatalog() {
        CrateCatalog.reset();
    }

    @Test
    void repeatedPurchasesDrawAgainForBothCratesAndKeysOnTheSameDay() {
        CrateCatalog.replaceEntries(List.of(entry("first", true, false), entry("second", true, false)));
        for (String kind : List.of(DailyRewardEntry.CRATE, DailyRewardEntry.KEY)) {
            DailyRewardEntry reward = new DailyRewardEntry(kind, DailyRewardEntry.RANDOM, 3);
            Random random = sequence(0, 1, 1);
            MailReward.Kind expectedKind = DailyRewardEntry.CRATE.equals(kind) ? MailReward.Kind.CRATE : MailReward.Kind.KEY;
            assertEquals(new MailReward(expectedKind, 3, "first"), purchase(reward, random));
            assertEquals(new MailReward(expectedKind, 3, "second"), purchase(reward, random));
            assertEquals(new MailReward(expectedKind, 3, "second"), purchase(reward, random),
                    "independent draws can still repeat the same crate");
            assertEquals(DailyRewardEntry.RANDOM, reward.id, "purchasing must not pin the listing to one crate");
        }
    }

    @Test
    void randomRewardsPreferEnabledCratesAndExcludeArchivedCrates() {
        CrateCatalog.replaceEntries(List.of(entry("disabled", false, false), entry("archived", true, true),
                entry("first", true, false), entry("second", true, false)));
        DailyRewardEntry reward = new DailyRewardEntry(DailyRewardEntry.CRATE, DailyRewardEntry.RANDOM, 1);
        Random random = sequence(0, 1);
        assertEquals(MailReward.crate("first", 1), purchase(reward, random));
        assertEquals(MailReward.crate("second", 1), purchase(reward, random));
    }

    @Test
    void randomRewardsFallBackToPublishedCratesWhenNoneAreEnabled() {
        CrateCatalog.replaceEntries(List.of(entry("archived", false, true), entry("first", false, false),
                entry("second", false, false)));
        DailyRewardEntry reward = new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 2);
        Random random = sequence(0, 1);
        assertEquals(MailReward.key("first", 2), purchase(reward, random));
        assertEquals(MailReward.key("second", 2), purchase(reward, random));
    }

    @Test
    void anEmptyPublishedCatalogCannotProduceRandomGoods() {
        CrateCatalog.replaceEntries(List.of(entry("archived", true, true)));
        for (String kind : List.of(DailyRewardEntry.CRATE, DailyRewardEntry.KEY)) {
            assertNull(purchase(new DailyRewardEntry(kind, DailyRewardEntry.RANDOM, 1), sequence()));
        }
    }

    @Test
    void fixedCratesKeysAndOtherGoodsDoNotDrawRandomly() {
        CrateCatalog.replaceEntries(List.of(entry("first", false, false), entry("archived", true, true)));
        Random noDraw = sequence();
        assertEquals(MailReward.crate("first", 4), purchase(new DailyRewardEntry(DailyRewardEntry.CRATE, "first", 4), noDraw));
        assertEquals(MailReward.key("first", 2), purchase(new DailyRewardEntry(DailyRewardEntry.KEY, "first", 2), noDraw));
        assertEquals(MailReward.greenApples(80), purchase(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 80), noDraw));
        assertEquals(MailReward.factionCard("civilian", 1),
                purchase(new DailyRewardEntry(DailyRewardEntry.CARD, "civilian", 1), noDraw));
        assertNull(purchase(new DailyRewardEntry(DailyRewardEntry.CRATE, "archived", 1), noDraw));
    }

    private static MailReward purchase(DailyRewardEntry reward, Random random) {
        return DailyShopService.purchaseReward(null, "crate", reward, DAY, random);
    }

    private static Random sequence(int... indexes) {
        return new Random(0L) {
            private int position;

            @Override
            public int nextInt(int bound) {
                assertTrue(position < indexes.length, "unexpected extra draw");
                int index = indexes[position++];
                assertTrue(index >= 0 && index < bound, "unexpected candidate count");
                return index;
            }
        };
    }

    private static CrateCatalog.Entry entry(String id, boolean enabled, boolean archived) {
        return new CrateCatalog.Entry(id, "crate." + id, "key_" + id, "minecraft:chest", 0,
                SkinQuality.WHITE, false, "", "key." + id, "minecraft:tripwire_hook", "white", enabled, archived,
                "skin_plus_bonus", 1, 1, 0, List.of(), id, "star");
    }
}

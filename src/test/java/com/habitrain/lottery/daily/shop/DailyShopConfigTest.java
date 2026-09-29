package com.habitrain.lottery.daily.shop;

import com.google.gson.Gson;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskConfig;
import com.habitrain.lottery.storage.PlayerLotteryData;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DailyShopConfigTest {

    private static DailyShopItem item(String id) {
        DailyShopItem item = new DailyShopItem();
        item.id = id;
        item.title = "商品";
        item.rewards.add(new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
        return item;
    }

    private static String code(DailyShopConfig config) {
        try {
            config.normalize(DailyTaskConfig.Checks.LENIENT);
            return "ok";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Test
    void defaultsAreValidAndNothingIsOnSale() {
        DailyShopConfig config = DailyShopConfig.defaults();
        assertEquals("ok", code(config));
        assertTrue(config.enabled);
        assertFalse(config.items.isEmpty());
        assertTrue(config.items.stream().noneMatch(i -> i.enabled), "an update must not change the economy");
        assertTrue(config.items.stream().allMatch(i -> !i.rewardLabel.isEmpty()));
    }

    @Test
    void rejectsBadListings() {
        DailyShopConfig c = new DailyShopConfig();
        c.items.add(item("a"));
        c.items.add(item("a"));
        assertEquals("duplicate_id:a", code(c));

        c = new DailyShopConfig();
        DailyShopItem price = item("p");
        price.price = -1;
        c.items.add(price);
        assertEquals("bad_price:p", code(c));

        c = new DailyShopConfig();
        DailyShopItem empty = item("e");
        empty.rewards.clear();
        c.items.add(empty);
        assertEquals("no_rewards:e", code(c));

        c = new DailyShopConfig();
        DailyShopItem limit = item("l");
        limit.limitEnabled = true;
        limit.limitCount = 0;
        c.items.add(limit);
        assertEquals("bad_limit_count:l", code(c));

        c = new DailyShopConfig();
        DailyShopItem days = item("d");
        days.limitEnabled = true;
        days.limitDays = DailyShopConfig.MAX_LIMIT_DAYS + 1;
        c.items.add(days);
        assertEquals("bad_limit_days:d", code(c));

        c = new DailyShopConfig();
        DailyShopItem card = item("c");
        card.rewards.set(0, new DailyRewardEntry(DailyRewardEntry.CARD, "nope", 1));
        c.items.add(card);
        assertEquals("bad_reward:c", code(c));
    }

    @Test
    void disabledLimitKeepsSaneNumbersAndFreeItemsAreAllowed() {
        DailyShopConfig c = new DailyShopConfig();
        DailyShopItem item = item("free");
        item.price = 0;
        item.limitCount = -5;
        item.limitDays = 9999;
        c.items.add(item);
        assertEquals("ok", code(c));
        assertEquals(1, item.limitCount);
        assertEquals(DailyShopConfig.MAX_LIMIT_DAYS, item.limitDays);
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        DailyShopConfig c = DailyShopConfig.defaults();
        c.normalize(DailyTaskConfig.Checks.LENIENT);
        Gson gson = new Gson();
        assertEquals(gson.toJson(c), gson.toJson(gson.fromJson(gson.toJson(c), DailyShopConfig.class)));
        assertEquals(gson.toJson(c), gson.toJson(c.copy()));
    }

    @Test
    void weeklyWindowsStartOnMonday() {
        for (int d = 0; d < 60; d++) {
            long day = LocalDate.of(2026, 9, 1).toEpochDay() + d;
            long reset = DailyShopLimits.nextResetDay(day, 7);
            assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(reset).getDayOfWeek());
            assertTrue(reset > day && reset - day <= 7);
            assertEquals(DailyShopLimits.period(day, 7) + 1, DailyShopLimits.period(reset, 7));
        }
        long any = LocalDate.of(2026, 9, 29).toEpochDay();
        assertEquals(any + 1, DailyShopLimits.nextResetDay(any, 1));
        assertEquals(-1, DailyShopLimits.nextResetDay(any, 0));
        assertEquals(DailyShopLimits.period(any, 0), DailyShopLimits.period(any + 10_000, 0));
    }

    @Test
    void countersResetWithTheWindowOrWhenTheWindowLengthChanges() {
        DailyShopItem item = item("k");
        item.limitEnabled = true;
        item.limitCount = 2;
        item.limitDays = 7;
        long monday = LocalDate.of(2026, 9, 28).toEpochDay();

        PlayerLotteryData.ShopCounter c = DailyShopLimits.plusOne(null, item, monday);
        assertEquals(1, DailyShopLimits.used(c, item, monday + 6));
        c = DailyShopLimits.plusOne(c, item, monday + 3);
        assertEquals(0, DailyShopLimits.remaining(c, item, monday + 6));
        assertEquals(2, DailyShopLimits.remaining(c, item, monday + 7), "next Monday starts a new window");

        item.limitDays = 1;
        assertEquals(0, DailyShopLimits.used(c, item, monday + 3), "a new window length restarts the count");

        item.limitEnabled = false;
        assertEquals(Integer.MAX_VALUE, DailyShopLimits.remaining(c, item, monday));
    }

    @Test
    void playerDataCopyIsDeep() {
        PlayerLotteryData data = new PlayerLotteryData();
        data.shopPurchases.put("x", new PlayerLotteryData.ShopCounter(7, 3, 1));
        PlayerLotteryData copy = data.copy();
        copy.shopPurchases.get("x").count = 5;
        assertEquals(1, data.shopPurchases.get("x").count);
    }
}

package com.habitrain.lottery.daily.shop;

import com.habitrain.lottery.storage.PlayerLotteryData;

/**
 * Purchase-limit windows. A window of {@code n} days is a fixed UTC block; blocks are aligned so a
 * 7-day window runs Monday to Sunday and a 1-day window is the UTC day the daily tasks use.
 * {@code n = 0} is a single window that never ends (a lifetime limit).
 */
public final class DailyShopLimits {
    /** Epoch day 0 (1970-01-01) was a Thursday; shifting by 3 puts every Monday on a block start. */
    private static final int MONDAY_SHIFT = 3;

    private DailyShopLimits() { }

    public static long period(long epochDay, int days) {
        return days <= 0 ? 0L : Math.floorDiv(epochDay + MONDAY_SHIFT, days);
    }

    /** First UTC epoch day of the next window, or -1 for a lifetime limit. */
    public static long nextResetDay(long epochDay, int days) {
        return days <= 0 ? -1L : (period(epochDay, days) + 1) * days - MONDAY_SHIFT;
    }

    /** Purchases already counted against {@code item} in today's window. */
    public static int used(PlayerLotteryData.ShopCounter counter, DailyShopItem item, long epochDay) {
        if (counter == null || item == null || counter.days != item.limitDays
                || counter.period != period(epochDay, item.limitDays)) return 0;
        return Math.max(0, counter.count);
    }

    /** Purchases still allowed today; {@link Integer#MAX_VALUE} when the item has no limit. */
    public static int remaining(PlayerLotteryData.ShopCounter counter, DailyShopItem item, long epochDay) {
        if (item == null) return 0;
        if (!item.limitEnabled) return Integer.MAX_VALUE;
        return Math.max(0, item.limitCount - used(counter, item, epochDay));
    }

    /** The counter after one more purchase today (a stale window restarts at 1). */
    public static PlayerLotteryData.ShopCounter plusOne(PlayerLotteryData.ShopCounter counter, DailyShopItem item,
                                                        long epochDay) {
        return new PlayerLotteryData.ShopCounter(item.limitDays, period(epochDay, item.limitDays),
                used(counter, item, epochDay) + 1);
    }
}

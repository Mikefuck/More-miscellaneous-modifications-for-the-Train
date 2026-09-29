package com.habitrain.lottery.daily.shop;

import com.habitrain.lottery.daily.config.DailyRewardEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * One listing of the daily-terminal shop: what the player receives, its green-apple price and an
 * optional purchase limit per window of {@link #limitDays} days.
 */
public final class DailyShopItem {
    public String id = "";
    public String title = "";
    public String description = "";
    public boolean enabled = true;
    /** Green apples per purchase; 0 makes a free (usually limited) gift. */
    public int price = 100;
    /** Goods handed out per purchase; the same reward lines a daily task pays. */
    public List<DailyRewardEntry> rewards = new ArrayList<>();
    public boolean limitEnabled;
    /** Purchases allowed per window while {@link #limitEnabled}. */
    public int limitCount = 1;
    /** Window length in days, UTC and Monday-aligned; 0 = the limit never resets. */
    public int limitDays = 1;
    public String rewardLabel = "";
    public boolean autoRewardLabel = true;

    public DailyShopItem copy() {
        DailyShopItem c = new DailyShopItem();
        c.id = id;
        c.title = title;
        c.description = description;
        c.enabled = enabled;
        c.price = price;
        if (rewards != null) for (DailyRewardEntry reward : rewards) if (reward != null) c.rewards.add(reward.copy());
        c.limitEnabled = limitEnabled;
        c.limitCount = limitCount;
        c.limitDays = limitDays;
        c.rewardLabel = rewardLabel;
        c.autoRewardLabel = autoRewardLabel;
        return c;
    }
}

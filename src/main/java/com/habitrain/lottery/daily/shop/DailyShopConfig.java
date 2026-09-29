package com.habitrain.lottery.daily.shop;

import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * World-level shop of the daily terminal ({@code config/daily_shop.json}).
 *
 * <p>{@link #normalize} is the single validation authority, run on load and on every save from the
 * Mod Menu editor, exactly like {@link DailyTaskConfig#normalize}.</p>
 */
public final class DailyShopConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ITEMS = 48;
    public static final int MAX_PRICE = 1_000_000;
    public static final int MAX_LIMIT_COUNT = 9_999;
    public static final int MAX_LIMIT_DAYS = 365;

    public int schemaVersion = SCHEMA_VERSION;
    /** Bumped by every accepted save; a stale editor draft is rejected instead of overwriting. */
    public long revision;
    /** Master switch: off hides every listing and refuses purchases. */
    public boolean enabled = true;
    public List<DailyShopItem> items = new ArrayList<>();

    public DailyShopConfig copy() {
        DailyShopConfig c = new DailyShopConfig();
        c.schemaVersion = schemaVersion;
        c.revision = revision;
        c.enabled = enabled;
        if (items != null) for (DailyShopItem item : items) if (item != null) c.items.add(item.copy());
        return c;
    }

    public DailyShopItem find(String id) {
        if (items == null || id == null) return null;
        for (DailyShopItem item : items) if (item != null && id.equals(item.id)) return item;
        return null;
    }

    /**
     * Validates and canonicalises the whole config in place.
     *
     * @throws IllegalArgumentException with a {@code code[:detail]} message on the first defect
     */
    public void normalize(DailyTaskConfig.Checks checks) {
        if (checks == null) checks = DailyTaskConfig.Checks.LENIENT;
        if (items == null) items = new ArrayList<>();
        items.removeIf(java.util.Objects::isNull);
        if (items.size() > MAX_ITEMS) throw invalid("too_many_items", String.valueOf(items.size()));
        Set<String> ids = new HashSet<>();
        for (DailyShopItem item : items) {
            normalizeItem(item, checks);
            if (!ids.add(item.id)) throw invalid("duplicate_id", item.id);
        }
    }

    private static void normalizeItem(DailyShopItem item, DailyTaskConfig.Checks checks) {
        item.id = trim(item.id).toLowerCase(Locale.ROOT);
        if (!item.id.matches("[a-z0-9_]{1,40}")) throw invalid("bad_id", item.id);
        item.title = trim(item.title);
        if (item.title.isEmpty() || item.title.length() > 32) throw invalid("bad_title", item.id);
        item.description = trim(item.description);
        if (item.description.length() > 120) throw invalid("description_too_long", item.id);
        if (item.price < 0 || item.price > MAX_PRICE) throw invalid("bad_price", item.id);
        if (item.limitEnabled) {
            if (item.limitCount < 1 || item.limitCount > MAX_LIMIT_COUNT) throw invalid("bad_limit_count", item.id);
            if (item.limitDays < 0 || item.limitDays > MAX_LIMIT_DAYS) throw invalid("bad_limit_days", item.id);
        } else {
            // Keep the (unused) numbers editable but sane, so toggling the limit back on is harmless.
            item.limitCount = Math.max(1, Math.min(MAX_LIMIT_COUNT, item.limitCount));
            item.limitDays = Math.max(0, Math.min(MAX_LIMIT_DAYS, item.limitDays));
        }
        if (item.rewards == null) item.rewards = new ArrayList<>();
        item.rewards.removeIf(java.util.Objects::isNull);
        if (item.rewards.isEmpty()) throw invalid("no_rewards", item.id);
        if (item.rewards.size() > DailyTaskConfig.MAX_REWARDS) throw invalid("too_many_rewards", item.id);
        for (DailyRewardEntry reward : item.rewards) DailyTaskConfig.normalizeReward(item.id, reward, checks);
        item.rewardLabel = trim(item.rewardLabel);
        if (item.rewardLabel.length() > 64) throw invalid("reward_label_too_long", item.id);
        if (item.rewardLabel.isEmpty()) item.rewardLabel = DailyTaskConfig.fallbackRewardLabel(item.rewards);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String code, String detail) {
        return new IllegalArgumentException(code + (detail == null || detail.isEmpty() ? "" : ":" + detail));
    }

    /**
     * First-run shop: switched on but every listing is a disabled template, so an update never
     * changes the economy until an OP publishes something.
     */
    public static DailyShopConfig defaults() {
        DailyShopConfig config = new DailyShopConfig();
        DailyShopItem key = item("crate_key", "箱子钥匙", "随机一种已上架箱子的钥匙", 300,
                new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
        key.limitEnabled = true;
        key.limitCount = 3;
        key.limitDays = 7;
        config.items.add(key);
        DailyShopItem crate = item("crate", "神秘箱子", "随机一种已上架的箱子", 200,
                new DailyRewardEntry(DailyRewardEntry.CRATE, DailyRewardEntry.RANDOM, 1));
        config.items.add(crate);
        DailyShopItem card = item("civilian_card", "乘客阵营卡", "随机获得一名乘客阵营职业", 150,
                new DailyRewardEntry(DailyRewardEntry.CARD, "civilian", 1));
        card.limitEnabled = true;
        card.limitCount = 1;
        card.limitDays = 1;
        config.items.add(card);
        DailyShopItem self = item("self_select", "自选卡", "自行指定本局职业", 800,
                new DailyRewardEntry(DailyRewardEntry.SELF_SELECT, "", 1));
        self.limitEnabled = true;
        self.limitCount = 1;
        self.limitDays = 7;
        config.items.add(self);
        return config;
    }

    private static DailyShopItem item(String id, String title, String description, int price, DailyRewardEntry reward) {
        DailyShopItem item = new DailyShopItem();
        item.id = id;
        item.title = title;
        item.description = description;
        item.price = price;
        item.enabled = false;
        item.rewards.add(reward);
        return item;
    }
}

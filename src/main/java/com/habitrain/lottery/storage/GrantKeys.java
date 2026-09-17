package com.habitrain.lottery.storage;

import java.util.Set;

/**
 * Disk-backed grant-key consume. Relies on {@link PlayerLotteryData.RecentGrants}
 * insertion-order cap so JSON cannot grow without bound.
 */
public final class GrantKeys {
    public static final int MAX_RECENT = PlayerLotteryData.RECENT_GRANTS_CAP;

    private GrantKeys() {
    }

    public static Set<String> normalize(PlayerLotteryData data) {
        if (data == null) {
            return new PlayerLotteryData.RecentGrants();
        }
        data.recentGrants = PlayerLotteryData.asRecentGrants(data.recentGrants);
        return data.recentGrants;
    }

    public static boolean contains(PlayerLotteryData data, String key) {
        if (data == null || key == null || key.isBlank()) {
            return false;
        }
        return normalize(data).contains(key);
    }

    /**
     * @return true if {@code key} was newly recorded
     */
    public static boolean tryConsume(PlayerLotteryData data, String key) {
        if (data == null || key == null || key.isBlank()) {
            return false;
        }
        Set<String> grants = normalize(data);
        if (grants.contains(key)) {
            return false;
        }
        grants.add(key);
        return true;
    }

    public static void trim(PlayerLotteryData data) {
        normalize(data);
    }
}

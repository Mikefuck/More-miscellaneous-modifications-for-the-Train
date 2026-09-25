package com.habitrain.lottery.storage;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PlayerLotteryData {
    public static final int RECENT_GRANTS_CAP = 64;

    public int version = 2;
    public long updatedAt;
    /** New currency. Legacy coin and draw balances are discarded, never converted. */
    public int greenApples;
    /** Virtual system rewards, separate from the physical player inventory. */
    public Map<String, Integer> systemItems = new HashMap<>();
    public Map<String, Map<String, Boolean>> unlocked = new HashMap<>();
    public Map<String, String> equipped = new HashMap<>();
    /** Durable grant keys (insertion-order LRU, cap {@link #RECENT_GRANTS_CAP}). */
    public Set<String> recentGrants = new RecentGrants();

    /** UTC epoch day of last login settlement; -1 = never. */
    public long lastLoginEpochDay = -1L;
    public int consecutiveLoginDays;
    /** "yyyy-MM" for loginDaysThisMonth, UTC. */
    public String loginDaysMonthKey = "";
    public Set<Integer> loginDaysThisMonth = new HashSet<>();
    /** UTC day and successful faction-card uses for the two-uses-per-day rule. */
    public long lastFactionCardUseEpochDay = -1L;
    public int factionCardUsesToday;
    /** Extra faction-card uses redeemed from limit break cards on the current UTC day. */
    public int factionCardBonusUsesToday;
    public long lastSelfSelectUseEpochDay = -1L;
    public int selfSelectUsesToday;
    /**
     * 已退役：旧「每日自动 4 张阵营卡」的发放日标记（1.1.29 起不再写入）。
     * 字段保留仅为兼容旧存档 JSON，避免读取旧文件时丢字段。
     */
    public long lastFactionCardGrantEpochDay = -1L;
    /** Daily-task progress is reset lazily on the next UTC day. */
    public long dailyTaskEpochDay = -1L;
    public Map<String, Integer> dailyTaskProgress = new HashMap<>();
    public Set<String> dailyTaskClaims = new HashSet<>();
    /** Persisted before an external reward callback; retryable after a crash. */
    public Set<String> dailyTaskPending = new HashSet<>();

    public PlayerLotteryData copy() {
        PlayerLotteryData c = new PlayerLotteryData();
        c.version = version;
        c.updatedAt = updatedAt;
        c.greenApples = greenApples;
        if (systemItems != null) c.systemItems.putAll(systemItems);
        unlocked.forEach((k, v) -> c.unlocked.put(k, new HashMap<>(v)));
        c.equipped.putAll(equipped);
        c.recentGrants = new RecentGrants(recentGrants);
        c.lastLoginEpochDay = lastLoginEpochDay;
        c.consecutiveLoginDays = consecutiveLoginDays;
        c.loginDaysMonthKey = loginDaysMonthKey;
        if (loginDaysThisMonth != null) {
            c.loginDaysThisMonth = new HashSet<>(loginDaysThisMonth);
        }
        c.lastFactionCardUseEpochDay = lastFactionCardUseEpochDay;
        c.factionCardUsesToday = factionCardUsesToday;
        c.factionCardBonusUsesToday = factionCardBonusUsesToday;
        c.lastSelfSelectUseEpochDay = lastSelfSelectUseEpochDay;
        c.selfSelectUsesToday = selfSelectUsesToday;
        c.lastFactionCardGrantEpochDay = lastFactionCardGrantEpochDay;
        c.dailyTaskEpochDay = dailyTaskEpochDay;
        if (dailyTaskProgress != null) c.dailyTaskProgress.putAll(dailyTaskProgress);
        if (dailyTaskClaims != null) c.dailyTaskClaims.addAll(dailyTaskClaims);
        if (dailyTaskPending != null) c.dailyTaskPending.addAll(dailyTaskPending);
        return c;
    }

    /** Wrap {@code src} as a capped LRU set; copies if {@code src} is not already one. */
    public static Set<String> asRecentGrants(Set<String> src) {
        if (src instanceof RecentGrants) {
            return src;
        }
        return new RecentGrants(src);
    }

    /** Insertion-order set that drops the oldest key once {@link #RECENT_GRANTS_CAP} is exceeded. */
    public static final class RecentGrants extends LinkedHashSet<String> {
        public RecentGrants() {
            super(RECENT_GRANTS_CAP + 4);
        }

        public RecentGrants(Collection<? extends String> c) {
            super(RECENT_GRANTS_CAP + 4);
            if (c != null) {
                addAll(c);
            }
        }

        @Override
        public boolean add(String s) {
            boolean added = super.add(s);
            while (size() > RECENT_GRANTS_CAP) {
                var it = iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
            return added;
        }
    }

}

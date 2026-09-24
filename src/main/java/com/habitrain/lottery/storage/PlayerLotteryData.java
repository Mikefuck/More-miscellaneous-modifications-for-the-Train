package com.habitrain.lottery.storage;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PlayerLotteryData {
    public static final int RECENT_GRANTS_CAP = 64;
    /**
     * 审核 B-09：对局级「已结算」标记的容量上限。
     *
     * <p>有界是安全的：{@code matchKey} 是 {@code 维度|开局世界刻|开局毫秒}
     * （{@code GrantEventHooks.formatRoundFingerprint}），每局唯一。
     * 需要这个集合兜住的只有「同一局的 ROUND_ENDED 被重放」——重放发生在同一次运行内
     * （事件重发）或紧跟其后的重启，跨度最多几局；32 局窗口远大于任何真实重放距离。</p>
     */
    public static final int RECENT_SETTLED_MATCHES_CAP = 32;

    public int version = 1;
    public long updatedAt;
    public int lootChance;
    public int coinNum;
    public boolean migratedFromSre;
    public Map<String, Map<String, Boolean>> unlocked = new HashMap<>();
    public Map<String, String> equipped = new HashMap<>();
    /** Durable grant keys (insertion-order LRU, cap {@link #RECENT_GRANTS_CAP}). */
    public Set<String> recentGrants = new RecentGrants();
    /**
     * 审核 B-09：durable「本局已发过奖」标记（按<b>结算范围键</b>，insertion-order LRU，
     * cap {@link #RECENT_SETTLED_MATCHES_CAP}）。
     *
     * <p>键有两种形态，与发奖时的 grant key 前缀一致：
     * {@code matchKey}（参与奖）与 {@code matchKey + ":win"}（胜利奖）。
     * 与 {@link #recentGrants} 分开存放的原因：grant key 是「事件×对局」维度的，
     * 一局可能产生多条（参与 + 胜利），长时间运行会把 64 条 LRU 挤爆，
     * 于是同一局的 ROUND_ENDED 被重放时旧 key 已经不在、可能重复发奖。
     * 这里只放<b>对局</b>维度的键，32 条就足够覆盖任何真实重放距离。</p>
     *
     * <p>为什么放在玩家档案里而不是单独一个全局文件：这是「玩家 X 是否已经拿过本局奖励」的
     * 事实，必须与同一玩家的 {@code lootChance} 在<b>同一次原子 JSON 写入</b>里落盘，
     * 否则就会出现「标记已落盘、奖励没落盘」的烧号（审核 B-10）。
     * 由 {@link PlayerLotteryStore} 的既有 deferred-flush 机制统一写盘。</p>
     */
    public Set<String> recentSettledMatches = new SettledMatches();

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
    /** UTC day of the last automatic login card grant. */
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
        c.lootChance = lootChance;
        c.coinNum = coinNum;
        c.migratedFromSre = migratedFromSre;
        unlocked.forEach((k, v) -> c.unlocked.put(k, new HashMap<>(v)));
        c.equipped.putAll(equipped);
        c.recentGrants = new RecentGrants(recentGrants);
        c.recentSettledMatches = new SettledMatches(recentSettledMatches);
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

    /**
     * 审核 B-09：把 {@code src} 包成带上限的 LRU；不是 {@link SettledMatches} 时复制一份。
     * Gson 反序列化只会产出普通 {@code LinkedHashSet}（不保留上限语义），
     * 所以读写这个字段前都要过这里。
     */
    public static Set<String> asSettledMatches(Set<String> src) {
        if (src instanceof SettledMatches) {
            return src;
        }
        return new SettledMatches(src);
    }

    /** @return 该玩家是否已经结算过这个对局范围键（{@code matchKey} / {@code matchKey:win}） */
    public static boolean hasSettledMatch(PlayerLotteryData data, String settleKey) {
        if (data == null || settleKey == null || settleKey.isBlank()) {
            return false;
        }
        return normalizeSettled(data).contains(settleKey);
    }

    /**
     * 记录「该玩家这个对局范围键已发奖」。
     *
     * @return {@code true} 表示本次是首次记录；{@code false} 表示入参无效或早已记录
     */
    public static boolean markSettledMatch(PlayerLotteryData data, String settleKey) {
        if (data == null || settleKey == null || settleKey.isBlank()) {
            return false;
        }
        return normalizeSettled(data).add(settleKey);
    }

    private static Set<String> normalizeSettled(PlayerLotteryData data) {
        data.recentSettledMatches = asSettledMatches(data.recentSettledMatches);
        return data.recentSettledMatches;
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

    /**
     * 审核 B-09：插入序「已结算对局」集合，超过 {@link #RECENT_SETTLED_MATCHES_CAP} 丢最旧的。
     *
     * <p>与 {@link RecentGrants} 同构，但容量更小、只装对局维度键，
     * 因此不会被同一局的参与/胜利两条 grant key 挤掉。</p>
     */
    public static final class SettledMatches extends LinkedHashSet<String> {
        public SettledMatches() {
            super(RECENT_SETTLED_MATCHES_CAP + 4);
        }

        public SettledMatches(Collection<? extends String> c) {
            super(RECENT_SETTLED_MATCHES_CAP + 4);
            if (c != null) {
                addAll(c);
            }
        }

        @Override
        public boolean add(String s) {
            boolean added = super.add(s);
            while (size() > RECENT_SETTLED_MATCHES_CAP) {
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

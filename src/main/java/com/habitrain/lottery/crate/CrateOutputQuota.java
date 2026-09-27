package com.habitrain.lottery.crate;

import com.habitrain.lottery.api.skin.SkinQuality;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-crate material quantities. Both windows are recorded even when only one is enforced. */
public final class CrateOutputQuota {
    public static final int MAX_LIMIT = 1_000_000_000;

    private CrateOutputQuota() {}

    public static final class Limit {
        public String period = "weekly";
        /** -1 means unlimited; zero pauses this quality's output. */
        public int limit = -1;

        public Limit() {}
        public Limit(String period, int limit) { this.period = period; this.limit = limit; }
    }

    public record Progress(long produced, long reserved, int limit) {
        public long used() { return add(produced, reserved); }
        public long remaining() { return limit < 0 ? Long.MAX_VALUE : Math.max(0L, limit - used()); }
        public boolean allows(long staged, int amount) {
            return amount > 0 && (limit < 0 || add(Math.max(0, staged), amount) <= remaining());
        }
        public int percent() {
            if (limit < 0) return 0;
            return limit == 0 || used() >= limit ? 100 : (int) (used() * 100 / limit);
        }
    }

    public static Map<String, Limit> defaults() {
        Map<String, Limit> result = new LinkedHashMap<>();
        for (SkinQuality quality : SkinQuality.values()) result.put(quality.id(), new Limit());
        return result;
    }

    public static Map<String, Limit> copyLimits(Map<String, Limit> source) {
        if (source == null) return null;
        Map<String, Limit> result = new LinkedHashMap<>();
        source.forEach((id, limit) -> result.put(id, limit == null ? null : new Limit(limit.period, limit.limit)));
        return result;
    }

    public static Limit limit(CrateService.CratePool pool, SkinQuality quality) {
        if (pool == null || pool.outputLimits == null) return new Limit();
        Limit result = pool.outputLimits.get(quality.id());
        return result == null ? new Limit() : result;
    }

    static void validate(Map<String, Limit> limits) {
        if (limits == null || limits.size() != SkinQuality.values().length)
            throw new IllegalArgumentException("missing output limits");
        for (SkinQuality quality : SkinQuality.values()) {
            Limit cap = limits.get(quality.id());
            if (cap == null || (!"weekly".equals(cap.period) && !"monthly".equals(cap.period))
                    || cap.limit < -1 || cap.limit > MAX_LIMIT)
                throw new IllegalArgumentException("invalid output limit");
        }
    }

    /** Old global quality caps seed independent weekly caps; opening counts are never material counts. */
    static void migrate(CrateService.CratePool pool, Map<String, Integer> legacyWeekly) {
        pool.outputLimits = defaults();
        for (SkinQuality quality : SkinQuality.values()) {
            Integer previous = legacyWeekly == null ? null : legacyWeekly.get(quality.id());
            if (previous != null && previous >= -1 && previous <= MAX_LIMIT)
                pool.outputLimits.get(quality.id()).limit = previous;
        }
    }

    public static String periodKey(LocalDate date, String period) {
        return ("monthly".equals(period) ? date.withDayOfMonth(1)
                : date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).toString();
    }

    static void resetWindows(CrateService.State state, LocalDate date) {
        String week = periodKey(date, "weekly"), month = periodKey(date, "monthly");
        if (!week.equals(state.weekKey)) {
            state.weekKey = week;
            state.weeklyUsed = new LinkedHashMap<>();
            state.crateWeeklyUsed = new LinkedHashMap<>();
            state.outputWeeklyUsed = new LinkedHashMap<>();
        }
        if (!month.equals(state.monthKey)) {
            state.monthKey = month;
            state.monthlyUsed = new LinkedHashMap<>();
            state.crateMonthlyUsed = new LinkedHashMap<>();
            state.outputMonthlyUsed = new LinkedHashMap<>();
        }
        if (state.outputWeeklyUsed == null) state.outputWeeklyUsed = new LinkedHashMap<>();
        if (state.outputMonthlyUsed == null) state.outputMonthlyUsed = new LinkedHashMap<>();
    }

    public static Progress progress(CrateService.State state, String crate, SkinQuality quality, Limit cap) {
        if (state == null) return new Progress(0, 0, cap.limit);
        boolean monthly = "monthly".equals(cap.period);
        Map<String, Map<String, Long>> counters = monthly ? state.outputMonthlyUsed : state.outputWeeklyUsed;
        Map<String, Long> quantities = counters == null ? null : counters.get(crate);
        long used = count(quantities, quality.id()), reserved = 0;
        String window = monthly ? state.monthKey : state.weekKey;
        if (state.pendingOpens != null) for (CrateService.PendingOpen pending : state.pendingOpens.values()) {
            if (pending != null && crate.equals(pending.crateId)
                    && window != null && window.equals(monthly ? pending.monthKey : pending.weekKey))
                reserved = add(reserved, count(outputDelta(pending), quality.id()));
        }
        return new Progress(used, reserved, cap.limit);
    }

    /** Legacy intents already record skin quality, even if its provider has since been removed. */
    static Map<String, Long> outputDelta(CrateService.PendingOpen pending) {
        if (pending.outputDelta != null && !pending.outputDelta.isEmpty()) return pending.outputDelta;
        Map<String, Long> recovered = new LinkedHashMap<>();
        if (pending.weeklyDelta != null) pending.weeklyDelta.forEach((quality, amount) -> {
            if (amount != null && amount > 0) recovered.put(quality, amount.longValue());
        });
        if (pending.rewards != null) for (CrateService.Reward reward : pending.rewards) {
            if (reward != null && !"skin".equals(reward.kind()) && reward.amount() > 0)
                recovered.merge(SkinQuality.WHITE.id(), (long) reward.amount(), CrateOutputQuota::add);
        }
        return recovered;
    }

    static void commit(CrateService.State state, CrateService.PendingOpen pending) {
        Map<String, Long> delta = outputDelta(pending);
        if (state.weekKey.equals(pending.weekKey)) merge(state.outputWeeklyUsed, pending.crateId, delta);
        if (state.monthKey.equals(pending.monthKey)) merge(state.outputMonthlyUsed, pending.crateId, delta);
    }

    private static void merge(Map<String, Map<String, Long>> target, String crate, Map<String, Long> delta) {
        Map<String, Long> counts = target.computeIfAbsent(crate, ignored -> new LinkedHashMap<>());
        delta.forEach((quality, amount) -> {
            if (amount != null && amount > 0) counts.put(quality, add(count(counts, quality), amount));
        });
    }

    static Map<String, Map<String, Long>> copyCounters(Map<String, Map<String, Long>> source) {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        if (source != null) source.forEach((crate, counts) -> result.put(crate,
                counts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(counts)));
        return result;
    }

    private static long count(Map<String, Long> counts, String quality) {
        Long value = counts == null ? null : counts.get(quality);
        return value == null ? 0 : Math.max(0, value);
    }

    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}

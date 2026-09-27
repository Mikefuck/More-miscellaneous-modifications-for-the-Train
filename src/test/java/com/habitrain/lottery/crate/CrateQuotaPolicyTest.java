package com.habitrain.lottery.crate;

import com.google.gson.Gson;
import com.habitrain.lottery.api.skin.SkinQuality;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrateQuotaPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);

    private CrateService.State state() {
        var state = new CrateService.State();
        CrateOutputQuota.resetWindows(state, TODAY);
        return state;
    }

    private CrateService.PendingOpen pending(CrateService.State state, String crate, Map<String, Long> delta) {
        var pending = new CrateService.PendingOpen();
        pending.crateId = crate; pending.weekKey = state.weekKey; pending.monthKey = state.monthKey;
        pending.outputDelta.putAll(delta);
        return pending;
    }

    @Test void everyQualityHasAnIndependentUnlimitedDefault() {
        var a = new CrateService.CratePool(); var b = new CrateService.CratePool();
        assertEquals(5, a.outputLimits.size());
        a.outputLimits.get("gold").limit = 7;
        assertEquals(-1, a.outputLimits.get("red").limit);
        assertEquals(-1, b.outputLimits.get("gold").limit);
    }

    @Test void countsMaterialUnitsRatherThanOpeningTransactions() {
        var state = state();
        var open = pending(state, "gilded", Map.of("gold", 3L, "white", 80L));
        CrateOutputQuota.commit(state, open);
        assertEquals(3, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, new CrateOutputQuota.Limit("weekly", 9)).produced());
        assertEquals(80, CrateOutputQuota.progress(state, "gilded", SkinQuality.WHITE, new CrateOutputQuota.Limit("weekly", 90)).produced());
        assertEquals(80L, state.outputMonthlyUsed.get("gilded").get("white"));
    }

    @Test void cratesAndQualitiesNeverConsumeEachOthersCaps() {
        var state = state();
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("gold", 8L)));
        var cap = new CrateOutputQuota.Limit("weekly", 8);
        assertEquals(0, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, cap).remaining());
        assertEquals(8, CrateOutputQuota.progress(state, "woodland", SkinQuality.GOLD, cap).remaining());
        assertEquals(8, CrateOutputQuota.progress(state, "gilded", SkinQuality.BLUE, cap).remaining());
    }

    @Test void pendingAndSameOpenOutputReserveActualAmounts() {
        var state = state();
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("white", 30L)));
        state.pendingOpens.put("one", pending(state, "gilded", Map.of("white", 40L)));
        state.pendingOpens.put("other-crate", pending(state, "woodland", Map.of("white", 8000L)));
        var progress = CrateOutputQuota.progress(state, "gilded", SkinQuality.WHITE, new CrateOutputQuota.Limit("weekly", 100));
        assertEquals(30, progress.produced()); assertEquals(40, progress.reserved()); assertEquals(70, progress.percent());
        assertTrue(progress.allows(20, 10));
        assertFalse(progress.allows(20, 11));
        assertFalse(progress.allows(0, 80));
    }

    @Test void weeklyAndMonthlySelectionIsIndependentAndDoesNotMultiplyLimits() {
        var state = state();
        state.outputWeeklyUsed.put("gilded", new LinkedHashMap<>(Map.of("gold", 2L)));
        state.outputMonthlyUsed.put("gilded", new LinkedHashMap<>(Map.of("gold", 7L)));
        assertEquals(7, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, new CrateOutputQuota.Limit("weekly", 9)).remaining());
        assertEquals(2, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, new CrateOutputQuota.Limit("monthly", 9)).remaining());
        assertEquals(1, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, new CrateOutputQuota.Limit("monthly", 8)).remaining());
    }

    @Test void naturalWeekResetsOnMondayAndMonthResetsOnFirst() {
        var state = state();
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("red", 3L)));
        CrateOutputQuota.resetWindows(state, LocalDate.of(2026, 9, 28));
        assertTrue(state.outputWeeklyUsed.isEmpty());
        assertEquals(3L, state.outputMonthlyUsed.get("gilded").get("red"));
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("red", 2L)));
        CrateOutputQuota.resetWindows(state, LocalDate.of(2026, 10, 1));
        assertEquals(2L, state.outputWeeklyUsed.get("gilded").get("red"));
        assertTrue(state.outputMonthlyUsed.isEmpty());
        assertEquals("2024-02-01", CrateOutputQuota.periodKey(LocalDate.of(2024, 2, 29), "monthly"));
        assertEquals("2025-12-29", CrateOutputQuota.periodKey(LocalDate.of(2026, 1, 1), "weekly"));
    }

    @Test void oldPendingDoesNotReserveOrCommitToANewWindow() {
        var state = state();
        var open = pending(state, "gilded", Map.of("gold", 8L));
        state.pendingOpens.put("old", open);
        CrateOutputQuota.resetWindows(state, LocalDate.of(2026, 9, 28));
        assertEquals(10, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, new CrateOutputQuota.Limit("weekly", 10)).remaining());
        assertEquals(2, CrateOutputQuota.progress(state, "gilded", SkinQuality.GOLD, new CrateOutputQuota.Limit("monthly", 10)).remaining());
        CrateOutputQuota.commit(state, open);
        assertTrue(state.outputWeeklyUsed.isEmpty());
        assertEquals(8L, state.outputMonthlyUsed.get("gilded").get("gold"));
    }

    @Test void unlimitedStillRecordsUsageAndZeroPausesOutput() {
        var state = state();
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("red", 10L)));
        var unlimited = CrateOutputQuota.progress(state, "gilded", SkinQuality.RED, new CrateOutputQuota.Limit("weekly", -1));
        assertEquals(10, unlimited.produced()); assertTrue(unlimited.allows(Long.MAX_VALUE, 100_000));
        assertEquals(0, unlimited.percent());
        var paused = CrateOutputQuota.progress(state, "gilded", SkinQuality.RED, new CrateOutputQuota.Limit("weekly", 0));
        assertFalse(paused.allows(0, 1)); assertEquals(100, paused.percent());
    }

    @Test void lowerLimitDoesNotEraseUsageAndLargeCountsNeverWrap() {
        var state = state();
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("white", Long.MAX_VALUE - 3)));
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("white", 80L)));
        var progress = CrateOutputQuota.progress(state, "gilded", SkinQuality.WHITE, new CrateOutputQuota.Limit("weekly", 5));
        assertEquals(Long.MAX_VALUE, progress.produced()); assertEquals(0, progress.remaining());
        assertEquals(100, progress.percent()); assertFalse(progress.allows(0, 1));
    }

    @Test void legacyPendingUsesSavedSkinQualityAndCountsApplesAndCards() {
        var pending = new CrateService.PendingOpen();
        pending.weeklyDelta.put("red", 2);
        pending.rewards = List.of(new CrateService.Reward("skin", "knife/removed-provider", 2),
                new CrateService.Reward("green_apples", "green_apples", 80), new CrateService.Reward("card", "civilian", 3));
        assertEquals(Map.of("red", 2L, "white", 83L), CrateOutputQuota.outputDelta(pending));
        pending.outputDelta.put("blue", 4L);
        assertEquals(Map.of("blue", 4L), CrateOutputQuota.outputDelta(pending));
    }

    @Test void migrationSeedsQualityCapsWithoutReusingOpeningCounts() {
        var legacy = new Gson().fromJson("{\"quotaLimit\":3,\"quotaPeriod\":\"monthly\"}", CrateService.CratePool.class);
        CrateOutputQuota.migrate(legacy, Map.of("red", 7, "white", -1));
        assertEquals(7, legacy.outputLimits.get("red").limit);
        assertEquals("weekly", legacy.outputLimits.get("red").period);
        assertEquals(-1, legacy.outputLimits.get("white").limit);
        assertEquals(-1, legacy.outputLimits.get("gold").limit);
    }

    @Test void countersAndLimitsRoundTripAndCopiesAreIndependent() {
        var state = state(); state.schemaVersion = 5;
        var pool = new CrateService.CratePool(); pool.outputLimits.get("red").period = "monthly";
        pool.outputLimits.get("red").limit = 12; state.crates.put("gilded", pool);
        CrateOutputQuota.commit(state, pending(state, "gilded", Map.of("red", 3L)));
        var gson = new Gson(); var copy = gson.fromJson(gson.toJson(state), CrateService.State.class);
        assertEquals(9, CrateOutputQuota.progress(copy, "gilded", SkinQuality.RED, copy.crates.get("gilded").outputLimits.get("red")).remaining());
        var limits = CrateOutputQuota.copyLimits(pool.outputLimits); limits.get("red").limit = 5;
        assertEquals(12, pool.outputLimits.get("red").limit);
        var counts = CrateOutputQuota.copyCounters(state.outputWeeklyUsed); counts.get("gilded").put("red", 42L);
        assertEquals(3L, state.outputWeeklyUsed.get("gilded").get("red"));
    }

    @Test void invalidPeriodsLimitsAndMissingQualitiesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CrateOutputQuota.validate(null));
        var limits = CrateOutputQuota.defaults();
        limits.get("red").limit = -2;
        assertThrows(IllegalArgumentException.class, () -> CrateOutputQuota.validate(limits));
        limits.get("red").limit = Integer.MAX_VALUE;
        assertThrows(IllegalArgumentException.class, () -> CrateOutputQuota.validate(limits));
        limits.get("red").limit = 0; limits.get("red").period = "daily";
        assertThrows(IllegalArgumentException.class, () -> CrateOutputQuota.validate(limits));
        limits.get("red").period = "monthly"; assertDoesNotThrow(() -> CrateOutputQuota.validate(limits));
        limits.remove("gold"); assertThrows(IllegalArgumentException.class, () -> CrateOutputQuota.validate(limits));
    }
}

package com.habitrain.lottery.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for {@code N-03}: configurable {@link LotteryDayClock}, non-UTC
 * {@link LotteryDayClock#nextResetEpochSecond(long)}, and westward timezone shift
 * monotonic guard {@link LotteryDayClock#monotonicEpochDay(long)}.
 */
class LotteryDayClockTest {

    @TempDir
    Path temp;

    @AfterEach
    void tearDown() {
        LotteryDayClock.resetAll();
    }

    @Test
    void loadWorldConfigParsesConfiguredZoneAndFallsBackToUtcOnInvalidOrMissing() throws Exception {
        LotteryDayClock.loadWorldConfig(temp.resolve("missing_clock.json"));
        assertEquals(ZoneOffset.UTC, LotteryDayClock.zone());

        Path clockFile = temp.resolve("clock.json");
        Files.writeString(clockFile, "{\"dailyResetZone\": \"Asia/Shanghai\"}", StandardCharsets.UTF_8);
        LotteryDayClock.loadWorldConfig(clockFile);
        assertEquals(ZoneId.of("Asia/Shanghai"), LotteryDayClock.zone());

        Files.writeString(clockFile, "{\"dailyResetZone\": \"Not/A_Real_Zone\"}", StandardCharsets.UTF_8);
        LotteryDayClock.loadWorldConfig(clockFile);
        assertEquals(ZoneOffset.UTC, LotteryDayClock.zone());
    }

    @Test
    void nextResetEpochSecondUsesConfiguredLocalMidnightInsteadOfUtcMidnight() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        // 2026-06-15T18:00:00Z == 2026-06-16T02:00:00+08:00 in Shanghai!
        Instant instant = Instant.parse("2026-06-15T18:00:00Z");
        LotteryDayClock.setTestClock(Clock.fixed(instant, shanghai));

        long shanghaiDay = LotteryDayClock.todayEpochDay();
        assertEquals(LocalDate.of(2026, 6, 16).toEpochDay(), shanghaiDay);
        // Next Shanghai midnight is 2026-06-17T00:00:00+08:00 == 2026-06-16T16:00:00Z
        long expectedReset = Instant.parse("2026-06-16T16:00:00Z").getEpochSecond();
        assertEquals(expectedReset, LotteryDayClock.nextResetEpochSecond(shanghaiDay));
    }

    @Test
    void monotonicEpochDayPreventsDoubleResetOnWestwardTimezoneShift() {
        // Player claimed/reset at 2026-06-16T02:00+08:00 (epochDay = 2026-06-16)
        long settledShanghaiDay = LocalDate.of(2026, 6, 16).toEpochDay();

        // Operator shifts dailyResetZone westward from Asia/Shanghai (+08) to UTC (+00) at 2026-06-15T19:00Z:
        // raw UTC epochDay is 2026-06-15 (settledShanghaiDay - 1), which MUST clamp to settledShanghaiDay!
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-06-15T19:00:00Z"), ZoneOffset.UTC));
        assertEquals(settledShanghaiDay, LotteryDayClock.monotonicEpochDay(settledShanghaiDay));

        // Once UTC advances to 2026-06-17, monotonicEpochDay advances normally to 2026-06-17
        long nextDay = LocalDate.of(2026, 6, 17).toEpochDay();
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-06-17T01:00:00Z"), ZoneOffset.UTC));
        assertEquals(nextDay, LotteryDayClock.monotonicEpochDay(settledShanghaiDay));
    }
}

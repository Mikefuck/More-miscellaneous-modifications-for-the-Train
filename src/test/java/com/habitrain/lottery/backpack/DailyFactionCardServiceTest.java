package com.habitrain.lottery.backpack;

import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyFactionCardServiceTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTest(temp);
    }

    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.clear();
        LotteryDayClock.resetTestClock();
    }

    @Test
    void refundSuccessfulUseWorksWithoutPlayer() {
        UUID id = UUID.randomUUID();
        long today = LoginRewardService.todayEpochDayUtc();
        PlayerLotteryStore.get().update(id, d -> {
            d.lastFactionCardUseEpochDay = today;
            d.factionCardUsesToday = 2;
        });
        DailyFactionCardService.refundSuccessfulUse(id);
        assertEquals(1, PlayerLotteryStore.get().getOrLoad(id).factionCardUsesToday);
    }
}

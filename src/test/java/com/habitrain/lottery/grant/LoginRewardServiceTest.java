package com.habitrain.lottery.grant;

import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LoginRewardServiceTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        PlayerLotteryStore.get().reset();
        LoginRewardService.resetObservedDayUtc();
        WorldLotteryPaths.clear();
        WorldLotteryPaths.initForTest(temp);
    }

    @AfterEach
    void tearDown() {
        PlayerLotteryStore.get().reset();
        LoginRewardService.resetObservedDayUtc();
        WorldLotteryPaths.clear();
    }

    @Test
    void alreadySettledTodayDoesNotMutate() {
        PlayerLotteryData d = new PlayerLotteryData();
        d.lastLoginEpochDay = 20000L;
        d.loginDaysMonthKey = "2024-10";
        d.loginDaysThisMonth = new HashSet<>();
        d.loginDaysThisMonth.add(15);
        d.consecutiveLoginDays = 5;
        d.lootChance = 3;

        assertTrue(LoginRewardService.alreadySettledToday(d, 20000L, "2024-10", 15));
        LoginRewardService.SettleOutcome out = LoginRewardService.applySettle(d, 20000L, "2024-10", 15, 7);
        assertFalse(out.mutated());
        assertEquals(0, out.granted());
        assertEquals(3, d.lootChance);
        assertEquals(5, d.consecutiveLoginDays);
        assertEquals(1, d.loginDaysThisMonth.size());
    }

    @Test
    void settleAlreadyLoggedInTodayDoesNotMarkDirty() {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = UUID.randomUUID();
        long today = 20000L;
        String monthKey = "2024-10";
        int day = 15;
        store.update(id, d -> {
            d.lastLoginEpochDay = today;
            d.loginDaysMonthKey = monthKey;
            d.loginDaysThisMonth = new HashSet<>();
            d.loginDaysThisMonth.add(day);
            d.lootChance = 4;
        });
        store.flush(id);
        assertFalse(store.isDirty(id));

        PlayerLotteryData loaded = store.getOrLoad(id);
        if (!LoginRewardService.alreadySettledToday(loaded, today, monthKey, day)) {
            store.update(id, d -> LoginRewardService.applySettle(d, today, monthKey, day, 7));
        }
        assertFalse(store.isDirty(id));
        assertEquals(4, store.getOrLoad(id).lootChance);
    }

    @Test
    void newUtcDayGrantsAndMutates() {
        PlayerLotteryData d = new PlayerLotteryData();
        d.lastLoginEpochDay = 19999L;
        d.consecutiveLoginDays = 2;
        d.lootChance = 1;
        LoginRewardService.SettleOutcome out = LoginRewardService.applySettle(d, 20000L, "2024-10", 15, 7);
        assertTrue(out.mutated());
        assertEquals(3, out.granted());
        assertEquals(20000L, d.lastLoginEpochDay);
        assertEquals(3, d.consecutiveLoginDays);
        assertEquals(4, d.lootChance);
        assertTrue(d.loginDaysThisMonth.contains(15));
    }

    @Test
    void dayRolledWhileOnlineIsDetected() {
        assertFalse(LoginRewardService.utcDayRolled(LoginRewardService.UNOBSERVED_DAY, 20000L));
        assertFalse(LoginRewardService.utcDayRolled(20000L, 20000L));
        assertTrue(LoginRewardService.utcDayRolled(19999L, 20000L));
        PlayerLotteryData d = new PlayerLotteryData();
        d.lastLoginEpochDay = 19999L;
        d.consecutiveLoginDays = 4;
        d.lootChance = 0;
        LoginRewardService.SettleOutcome out = LoginRewardService.applySettle(d, 20000L, "2024-10", 16, 7);
        assertTrue(out.mutated());
        assertEquals(5, out.granted());
        assertEquals(20000L, d.lastLoginEpochDay);
    }
}

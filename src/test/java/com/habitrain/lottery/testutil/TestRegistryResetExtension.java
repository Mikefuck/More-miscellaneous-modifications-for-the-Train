package com.habitrain.lottery.testutil;

import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.player.ApiGuards;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.card.SelfSelectForces;
import com.habitrain.lottery.grant.SelfSelectRoleHook;
import com.habitrain.lottery.time.LotteryDayClock;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Resets in-memory static registries and test thread overrides before and after each test
 * so registration state never leaks across test methods or classes (Audit I-25).
 */
public final class TestRegistryResetExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        resetAll();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        resetAll();
    }

    public static void resetAll() {
        HabiSkinApi.clearForTest();
        HabiSystemItemApi.clearForTest();
        HabiDailyTaskApi.clearForTest();
        ApiGuards.setServerThreadForTest(null);
        LotteryDayClock.resetAll();
        ActiveCardForces.clear();
        SelfSelectForces.clear();
        SelfSelectRoleHook.clearPrePityForces();
    }
}

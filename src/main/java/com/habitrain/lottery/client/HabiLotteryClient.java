package com.habitrain.lottery.client;

import com.habitrain.lottery.client.render.LoginCalendarWorldRender;
import com.habitrain.lottery.client.theme.LootThemeHooks;
import net.fabricmc.api.ClientModInitializer;

public final class HabiLotteryClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LotteryClientNetwork.registerClient();
        LootThemeHooks.initClient();
        LoginCalendarWorldRender.register();
        // Keep common network code free of client-only class refs (dedicated server safe).
        LotteryClientNetwork.setClientSnapshotApplier((pools, theme) -> {
            LootThemeHooks.applyTheme(theme);
            // SRE keeps a separate client pool cache that only fills missing IDs.
            // Force-replace it from the authoritative server snapshot so Mod Menu
            // enable/disable/edit shows up on the gacha page immediately.
            SreClientPoolSync.applyFromConfig(pools);
        });
    }
}

package com.habitrain.lottery.client;

import com.habitrain.lottery.client.render.LoginCalendarWorldRender;
import com.habitrain.lottery.client.theme.LootThemeHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.concurrent.atomic.AtomicBoolean;

public final class HabiLotteryClient implements ClientModInitializer {

    /** One-shot guard so the tick re-assert runs exactly once per client. */
    private static final AtomicBoolean FIRST_TICK_DONE = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        SkinClient.register();
        // Skin effects API v1, client half: flight trails for skinned projectiles.
        SkinTrailTicker.register();
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

        // Receiver registration is deliberately NOT done here. Fabric Loader does not
        // order client entrypoints by the dependency graph, so this entrypoint can run
        // before SRE's: SRE would then register its empty loot stub receivers on top of
        // the lottery's real ones (last write wins) and the gacha result screens would
        // never open. CLIENT_STARTED fires after every mod's client entrypoint has run,
        // so registering there always wins; LotteryClientNetwork.ensureRegistered() is
        // idempotent and re-asserts the loot receivers on repeat calls.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> LotteryClientNetwork.ensureRegistered());

        // Belt and braces: a late-initialising SRE cannot win after the first client
        // tick either. One-shot, and ensureRegistered() never throws.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (FIRST_TICK_DONE.compareAndSet(false, true)) {
                LotteryClientNetwork.ensureRegistered();
            }
        });
    }
}

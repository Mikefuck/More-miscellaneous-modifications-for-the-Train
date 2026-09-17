package com.habitrain.lottery;

import com.habitrain.lottery.block.ModBlocks;
import com.habitrain.lottery.bridge.LotteryManagerBridge;
import com.habitrain.lottery.bridge.SkinStateCoordinator;
import com.habitrain.lottery.command.LotteryCommands;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.grant.CardForceGuaranteeHook;
import com.habitrain.lottery.grant.GrantEventHooks;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.grant.LotteryGrantService;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.skin.SkinContentBootstrap;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.BackpackJoinService;
import com.habitrain.lottery.card.SelfSelectForces;
import com.habitrain.lottery.record.LocalMatchRecordStore;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.TitlePaths;
import com.habitrain.lottery.title.TitleService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class HabiLotteryMod implements ModInitializer {
    public static final String MOD_ID = "habitrain_lottery";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MinecraftServer server;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing 哈比列车抽奖补齐");

        ModBlocks.register();
        SkinContentBootstrap.registerAll();
        LotteryNetwork.registerServer();
        SkinStateCoordinator.registerLifecycle();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            try {
                WorldLotteryPaths.init(s);
                try {
                    MetaFeaturePaths.ensureDirs();
                    TitlePaths.ensureDirs();
                } catch (Exception e) {
                    LOGGER.warn("Failed creating meta feature dirs: {}", e.toString());
                }
                LotteryConfigService.get().loadOrSeed(s);
                if (LotteryConfigService.get().isLoadFailed()) {
                    LOGGER.error("Lottery world config failed to load; refusing pool apply and saveAll until fixed");
                    abortEconomyTakeover();
                    return;
                }
                if (!LotteryManagerBridge.applyWorldPools(s)) {
                    LOGGER.error("Failed applying in-memory lottery pools to SRE shadow file");
                }
                com.habitrain.lottery.bridge.SreSkinConfigBridge.ensureServerFlags();
                PlayerLotteryStore.get().onServerStarted(s);
                LOGGER.info("World lottery root: {}", WorldLotteryPaths.root());
            } catch (Throwable e) {
                LOGGER.error("Lottery SERVER_STARTED init failed; refusing economy takeover", e);
                abortEconomyTakeover();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            SkinStateCoordinator.clearPending(s);
            // Reservations live in memory; return unused self-select cards before clearing stores.
            com.habitrain.lottery.grant.SelfSelectRoleHook.finishSelections();
            CardForceGuaranteeHook.refundPendingCards();
            if (!SelfSelectForces.isEmpty()) {
                LOGGER.error("Unfinished self-select refunds at shutdown: {}", SelfSelectForces.snapshot());
            }
            PlayerLotteryStore.get().onServerStopping();
            if (!LocalTitleStore.get().flushAll()) {
                LOGGER.error("Title flushAll reported failures during server stopping");
            }
            LocalMatchRecordStore.shutdownAndAwait();
            if (!LotteryConfigService.get().saveAll(s)) {
                LOGGER.error("Lottery config saveAll reported failure during server stopping");
            }
            LocalTitleStore.get().reset();
            ActiveCardForces.clear();
            SelfSelectForces.clear();
            com.habitrain.lottery.card.CardUseService.clearRefundState();
            LoginRewardService.resetObservedDayUtc();
            WorldLotteryPaths.reset();
            server = null;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            PlayerLotteryStore.get().onPlayerJoin(handler.player);
            LoginRewardService.onPlayerJoin(handler.player);
            TitleService.onPlayerJoin(handler.player);
            UUID id = handler.player.getUUID();
            s.execute(() -> {
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) {
                    BackpackJoinService.apply(p);
                }
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            PlayerLotteryStore.get().onPlayerQuit(handler.player);
        });

        ServerTickEvents.END_SERVER_TICK.register(LoginRewardService::onEndServerTick);

        CommandRegistrationCallback.EVENT.register(LotteryCommands::register);
        GrantEventHooks.register();
        com.habitrain.lottery.grant.SelfSelectRoleHook.register();
        CardForceGuaranteeHook.register();
        LotteryGrantService.init();
    }

    public static MinecraftServer getServer() {
        return server;
    }

    /** Clears takeover and world paths so JOIN / MySQL mixins cannot run half-initialized. */
    private static void abortEconomyTakeover() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.reset();
    }
}

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
        // 审核 N-03：SRE 的 SkinsNetworkSyncInitializer 在<b>它自己的</b> SERVER_STARTED
        // 里就读并缓存 itemSkinSyncServerEnabled（静态 isEnabled），而本模组的
        // SERVER_STARTED 监听器注册得更晚、因此执行更晚——首启那次写入对它无效。
        // 模组初始化阶段一定早于任何 SERVER_STARTED 分发，所以在这里补一次；
        // 两个入口都是幂等的，SERVER_STARTED 里那次保留作为兜底。
        com.habitrain.lottery.bridge.SreSkinConfigBridge.ensureServerFlags();
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

        // 审核 S-02：SERVER_STOPPING 处理器必须自身吞掉异常。
        // Fabric 按注册顺序分发，异常穿出会跳过<b>其后的</b>监听器——
        // 那可能是核心或另一个模组的存档/退款收尾，直接导致整档丢失。
        // 因此：先在最外层包 try/catch，再把每个步骤单独包一层，
        // 使「一个步骤失败」不会连带跳过同一次停服里的其它收尾步骤。
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            try {
                runQuietly("SkinStateCoordinator.clearPending", () -> SkinStateCoordinator.clearPending(s));
                // Reservations live in memory; return unused self-select cards before clearing stores.
                runQuietly("SelfSelectRoleHook.finishSelections",
                        com.habitrain.lottery.grant.SelfSelectRoleHook::finishSelections);
                runQuietly("CardForceGuaranteeHook.refundPendingCards",
                        CardForceGuaranteeHook::refundPendingCards);
                if (!SelfSelectForces.isEmpty()) {
                    LOGGER.error("Unfinished self-select refunds at shutdown: {}", SelfSelectForces.snapshot());
                }
                runQuietly("PlayerLotteryStore.onServerStopping", () -> PlayerLotteryStore.get().onServerStopping());
                if (!runQuietly("LocalTitleStore.flushAll", () -> LocalTitleStore.get().flushAll())) {
                    LOGGER.error("Title flushAll reported failures during server stopping");
                }
                // 注意：这里刻意用 lambda 而不是方法引用——本类同时有 Runnable 与
                // BooleanSupplier 两个重载，void 方法的引用会变得含糊（编译期歧义）。
                runQuietly("LocalMatchRecordStore.shutdownAndAwait",
                        () -> LocalMatchRecordStore.shutdownAndAwait());
                if (!runQuietly("LotteryConfigService.saveAll", () -> LotteryConfigService.get().saveAll(s))) {
                    LOGGER.error("Lottery config saveAll reported failure during server stopping");
                }
            } catch (Throwable t) {
                LOGGER.error("Lottery SERVER_STOPPING cleanup failed (other mods' stop listeners are unaffected)", t);
            } finally {
                // 清理动作本身也要保证执行完，且不影响其它模组的停服收尾。
                runQuietly("shutdown reset", () -> {
                    LocalTitleStore.get().reset();
                    ActiveCardForces.clear();
                    SelfSelectForces.clear();
                    com.habitrain.lottery.card.CardUseService.clearRefundState();
                    LoginRewardService.resetObservedDayUtc();
                    WorldLotteryPaths.reset();
                    server = null;
                });
            }
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
            // 审核 B-05：限流器按 玩家×频道 记账，旧实现在本模组里从不 clear，
            // 内存随时间无限增长。核心的对应实现是在 DISCONNECT 里清理的。
            if (handler.player != null) {
                LotteryNetwork.clearRateLimits(handler.player.getUUID());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(LoginRewardService::onEndServerTick);
        // 审核 B-03 / B-04 / B-19 / B-20 的收尾保证：这些路径在 flush 失败时会，
        // <b>正确地</b>回滚内存并告知玩家失败——但内存里的旧值此时仍未落盘
        // （磁盘上可能还是更早的、甚至"领先"的状态）。定期重试 flushAll 让
        // 「已经决定保留的改动」最终一定被写下去，而不是等下一次触发才偶然写成功。
        // flushAll 只在真的有 dirty 条目时才写盘，因此这里的开销是 O(dirty)。
        ServerTickEvents.END_SERVER_TICK.register(HabiLotteryMod::retryPendingFlush);

        CommandRegistrationCallback.EVENT.register(LotteryCommands::register);
        GrantEventHooks.register();
        com.habitrain.lottery.grant.SelfSelectRoleHook.register();
        CardForceGuaranteeHook.register();
        LotteryGrantService.init();
    }

    public static MinecraftServer getServer() {
        return server;
    }

    /** 每 {@value #FLUSH_RETRY_INTERVAL_TICKS} tick 重试一次未落盘的玩家存档（审核 B-03/B-04）。 */
    private static final int FLUSH_RETRY_INTERVAL_TICKS = 100;

    private static int flushRetryCountdown = FLUSH_RETRY_INTERVAL_TICKS;
    /** 避免写盘持续失败时每 5 秒刷一条 ERROR 日志。 */
    private static boolean flushRetryFailureLogged = false;

    private static void retryPendingFlush(MinecraftServer s) {
        if (s == null || flushRetryCountdown-- > 0) {
            return;
        }
        flushRetryCountdown = FLUSH_RETRY_INTERVAL_TICKS;
        try {
            // flushAll 只写 dirty 条目；全部干净时它是一次空遍历。
            if (!PlayerLotteryStore.get().flushAll()) {
                if (!flushRetryFailureLogged) {
                    flushRetryFailureLogged = true;
                    LOGGER.error("Player lottery flush retry failed; will keep retrying every {} ticks "
                            + "(writes are held in memory and reported as failures to players)",
                            FLUSH_RETRY_INTERVAL_TICKS);
                }
            } else {
                flushRetryFailureLogged = false;
            }
        } catch (Throwable t) {
            LOGGER.error("Player lottery flush retry threw; continuing", t);
        }
    }

    /**
     * 审核 S-02：执行一个停服收尾步骤，失败只记日志、绝不外抛；
     * 返回该步骤的布尔结果（{@code false} 表示步骤报告失败或抛异常）。
     *
     * @return {@code true} 当且仅当 {@code step} 正常返回 {@code true}
     */
    private static boolean runQuietly(String label, java.util.function.BooleanSupplier step) {
        try {
            return step.getAsBoolean();
        } catch (Throwable t) {
            LOGGER.error("SERVER_STOPPING step '{}' failed; continuing with the remaining steps", label, t);
            return false;
        }
    }

    /** {@link #runQuietly(String, java.util.function.BooleanSupplier)} 的 void 版本。 */
    private static void runQuietly(String label, Runnable step) {
        runQuietly(label, () -> {
            step.run();
            return true;
        });
    }

    /** Clears takeover and world paths so JOIN / MySQL mixins cannot run half-initialized. */
    private static void abortEconomyTakeover() {
        PlayerLotteryStore.get().reset();
        WorldLotteryPaths.reset();
    }
}

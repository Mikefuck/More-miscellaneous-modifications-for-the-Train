package com.habitrain.lottery.client;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.client.gui.MailComposeScreen;
import com.habitrain.lottery.client.gui.MailboxScreen;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
import com.habitrain.lottery.config.LotteryConfigService;
import com.habitrain.lottery.config.PoolConfigModels;
import com.habitrain.lottery.config.ThemeConfig;
import com.habitrain.lottery.network.CardUseMenuS2C;
import com.habitrain.lottery.network.DailyTaskBoardS2C;
import com.habitrain.lottery.network.DailyTaskClaimC2S;
import com.habitrain.lottery.network.DailyTaskRequestC2S;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.network.MailboxClaimC2S;
import com.habitrain.lottery.network.MailboxListS2C;
import com.habitrain.lottery.network.MailboxRequestC2S;
import com.habitrain.lottery.network.OpenMailComposeS2C;
import com.habitrain.lottery.network.OpenMailboxS2C;
import com.habitrain.lottery.network.PlayerAdminModels;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.agmas.noellesroles.client.screen.LootInfoScreen;
import org.agmas.noellesroles.packet.Loot.LootDataRefreshS2CPacket;
import org.agmas.noellesroles.packet.Loot.LootMultiResultS2CPacket;
import org.agmas.noellesroles.packet.Loot.LootResultS2CPacket;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Client-only networking. Kept out of {@link LotteryNetwork} so dedicated servers never
 * resolve client classes (Screen / ClientPlayNetworking) during main entrypoint init.
 */
@Environment(EnvType.CLIENT)
public final class LotteryClientNetwork {
    private static final Gson GSON = new Gson();

    private static BiConsumer<PoolConfigModels.Root, ThemeConfig> clientSnapshotApplier;

    /**
     * Guards the one-time registration of the lottery's own receivers.
     * {@link #ensureRegistered()} still re-asserts the SRE loot receivers on every
     * call, because those are the ones another mod can overwrite.
     */
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private LotteryClientNetwork() {
    }

    public static void setClientSnapshotApplier(BiConsumer<PoolConfigModels.Root, ThemeConfig> applier) {
        clientSnapshotApplier = applier;
    }

    /**
     * Idempotent client receiver registration. Safe to call any number of times,
     * from any thread, and it never throws.
     *
     * <p>Fabric Loader does not order client entrypoints by the dependency graph, so
     * this mod's client entrypoint can run <em>before</em>
     * {@code noellesroles}/{@code starrailexpress} registers its empty loot stub
     * receivers. In that case the one-shot registration below would be overwritten
     * by SRE afterwards (last write wins) and the gacha result screens would never
     * open. The first call therefore registers everything once (guarded by
     * {@link #REGISTERED}), and <b>every</b> call re-asserts the SRE loot receivers
     * with unregister + register so the lottery always wins the last-write race —
     * including when this is re-invoked from {@code CLIENT_STARTED} and from the
     * first client tick by {@code HabiLotteryClient}.
     */
    public static void ensureRegistered() {
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                registerCommonReceivers();
                HabiLotteryMod.LOGGER.debug("habitrain_lottery client receivers registered");
            } catch (Throwable t) {
                // Allow a later call to finish registration instead of leaving the
                // client permanently half-wired.
                REGISTERED.set(false);
                HabiLotteryMod.LOGGER.warn("Client receiver registration failed, will retry: {}", t.toString());
            }
        }
        try {
            registerLootClient();
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Failed re-asserting SRE loot receivers: {}", t.toString());
        }
    }

    /**
     * Backwards-compatible alias for {@link #ensureRegistered()}. Kept so callers
     * that used to register unconditionally cannot double-register the lottery's own
     * receivers.
     */
    public static void registerClient() {
        ensureRegistered();
    }

    private static void registerCommonReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(DailyTaskBoardS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        DailyTaskSnapshot board = GSON.fromJson(payload.json(), DailyTaskSnapshot.class);
                        if (board == null) return;
                        Minecraft mc = context.client();
                        if (mc.screen instanceof DailyTaskScreen screen) {
                            screen.applySnapshot(board);
                        } else if (payload.open()) {
                            mc.setScreen(new DailyTaskScreen(mc.screen, board));
                        }
                    } catch (RuntimeException error) {
                        HabiLotteryMod.LOGGER.warn("Failed applying daily task board", error);
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(com.habitrain.lottery.network.OpenCoinExchangeS2C.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    Minecraft mc = context.client();
                    if (!(mc.screen instanceof com.habitrain.lottery.client.gui.CoinExchangeScreen)) {
                        mc.setScreen(new com.habitrain.lottery.client.gui.CoinExchangeScreen(mc.screen, payload));
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.ConfigSnapshotS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    // Integrated host shares LotteryConfigService with the server in one JVM.
                    // Skip importAllJson / SRE pool rebuild so the render thread does not
                    // replace live server objects. Still mark snapshot applied so Mod Menu
                    // rebuilds from the already-authoritative singleton. LAN guests and
                    // dedicated clients do not have a local integrated server, so they apply.
                    if (mc != null && mc.hasSingleplayerServer()) {
                        LotteryNetwork.ClientLotteryState.op = LotteryNetwork.ClientLotteryState.pendingOp;
                        LotteryNetwork.ClientLotteryState.hasSnapshot = true;
                        LotteryNetwork.ClientLotteryState.configVersion++;
                        return;
                    }
                    LotteryConfigService.get().importAllJson(payload.json());
                    if (clientSnapshotApplier != null) {
                        clientSnapshotApplier.accept(
                                GSON.fromJson(com.google.gson.JsonParser.parseString(payload.json()).getAsJsonObject()
                                        .get("runtimePools"), PoolConfigModels.Root.class),
                                LotteryConfigService.get().getTheme());
                    }
                    LotteryNetwork.ClientLotteryState.op = LotteryNetwork.ClientLotteryState.pendingOp;
                    LotteryNetwork.ClientLotteryState.hasSnapshot = true;
                    LotteryNetwork.ClientLotteryState.configVersion++;
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Client failed applying config snapshot: {}", t.toString());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.OpStatusS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.op = payload.op();
                LotteryNetwork.ClientLotteryState.pendingOp = payload.op();
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.OpenLootUiS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.lastCoinNumber = payload.coinNumber();
                LotteryNetwork.ClientLotteryState.lastLotteryChance = payload.lotteryChance();
                LootUiOpener.openFromServerPacket(payload.coinNumber(), payload.lotteryChance());
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.PlayerListS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                try {
                    PlayerAdminModels.PlayerListSnapshot snap =
                            GSON.fromJson(payload.json(), PlayerAdminModels.PlayerListSnapshot.class);
                    if (snap != null && snap.players != null) {
                        LotteryNetwork.ClientLotteryState.playerList = snap;
                    }
                    LotteryNetwork.ClientLotteryState.playerListVersion++;
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Failed parsing player list: {}", t.toString());
                }
            });
        });
        // Payload type kept registered for old-server compat; client no longer applies JSON.
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.BackupDataS2C.TYPE, (payload, context) -> {
            HabiLotteryMod.LOGGER.debug("Ignoring legacy BackupDataS2C ({} chars)",
                    payload.json() == null ? 0 : payload.json().length());
        });
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.AdminActionResultS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.lastAdminMessage = payload.message();
                if (payload.refreshPlayers()) {
                    clientRequestPlayerList();
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.LoginStateS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLoginState.epochDay = payload.epochDay();
                LotteryNetwork.ClientLoginState.year = payload.year();
                LotteryNetwork.ClientLoginState.month = payload.month();
                LotteryNetwork.ClientLoginState.dayOfMonth = payload.dayOfMonth();
                LotteryNetwork.ClientLoginState.streak = payload.streak();
                LotteryNetwork.ClientLoginState.rewardToday = payload.rewardToday();
                LotteryNetwork.ClientLoginState.loginDaysMask = payload.loginDaysMask();
                LotteryNetwork.ClientLoginState.hasData = true;
                LotteryNetwork.ClientLoginState.version++;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(OpenMailComposeS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                mc.setScreen(new MailComposeScreen(mc.screen));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(OpenMailboxS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                mc.setScreen(new MailboxScreen(mc.screen));
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(MailboxListS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.mailboxJson =
                        payload.json() == null ? "" : payload.json();
                LotteryNetwork.ClientLotteryState.mailboxVersion++;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.TitleSnapshotS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.titleCatalogJson =
                        payload.catalogJson() == null ? "" : payload.catalogJson();
                LotteryNetwork.ClientLotteryState.titlePlayersJson =
                        payload.playersJson() == null ? "" : payload.playersJson();
                LotteryNetwork.ClientLotteryState.titleVersion++;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(com.habitrain.lottery.network.WarehouseNetwork.Snapshot.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().screen instanceof com.habitrain.lottery.client.gui.WarehouseScreen screen)
                        screen.receive(payload);
                }));
        ClientPlayNetworking.registerGlobalReceiver(CardUseMenuS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.cardUseRemainingUses = payload.remainingUses();
                LotteryNetwork.ClientLotteryState.cardUseRemainingSelfUses = payload.remainingSelfUses();
                java.util.Map<String, Integer> balances = new com.google.gson.Gson().fromJson(payload.balancesJson(),
                        new com.google.gson.reflect.TypeToken<java.util.Map<String, Integer>>() {}.getType());
                LotteryNetwork.ClientLotteryState.cardBalances = balances == null ? java.util.Map.of() : balances;
                LotteryNetwork.ClientLotteryState.cardInventoryVersion++;
                if ("inventory".equals(payload.questKey())) return;
                LotteryNetwork.ClientLotteryState.cardUseMenuQuestKey =
                        payload.questKey() == null ? "" : payload.questKey();
                LotteryNetwork.ClientLotteryState.cardUseRemainingUses = payload.remainingUses();
                LotteryNetwork.ClientLotteryState.cardUseCandidatesJson =
                        payload.candidatesJson() == null ? "" : payload.candidatesJson();
                LotteryNetwork.ClientLotteryState.cardUseMenuVersion++;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.screen instanceof com.habitrain.lottery.client.gui.WarehouseScreen screen)
                    screen.receiveCardMenu(payload);
            });
        });
    }

    /**
     * Re-enables the SRE gacha result screens (upstream left the client receivers empty).
     * SRE registered empty stubs first, so unregister before re-registering — and this
     * must be re-runnable, because SRE's own client entrypoint may run after ours and
     * would otherwise put its empty stubs back on top.
     */
    private static void registerLootClient() {
        ClientPlayNetworking.unregisterGlobalReceiver(LootResultS2CPacket.ID.id());
        ClientPlayNetworking.registerGlobalReceiver(LootResultS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null && mc.screen != null) {
                        // Server already sent final coin/draw values via LootDataRefreshS2CPacket,
                        // so no local decrement needed — just show the result screen.
                        mc.setScreen(new PagedLootMultiScreen(payload.poolID(), java.util.List.of(new int[]{payload.quality(), payload.ansID()}), mc.screen));
                    }
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Failed showing loot result screen: {}", t.toString());
                }
            });
        });

        ClientPlayNetworking.unregisterGlobalReceiver(LootMultiResultS2CPacket.ID.id());
        ClientPlayNetworking.registerGlobalReceiver(LootMultiResultS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null && mc.screen != null) {
                        if (!payload.results().isEmpty()) {
                            mc.setScreen(new PagedLootMultiScreen(payload.poolID(), payload.results(), mc.screen));
                        }
                    }
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Failed showing multi-loot result screen: {}", t.toString());
                }
            });
        });

        ClientPlayNetworking.unregisterGlobalReceiver(LootDataRefreshS2CPacket.ID.id());
        ClientPlayNetworking.registerGlobalReceiver(LootDataRefreshS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof LootInfoScreen screen) {
                        screen.setCoinNumber(payload.coinNumber());
                        screen.setLotteryChance(payload.lootChance());
                    }
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Failed refreshing loot economy display: {}", t.toString());
                }
            });
        });
    }

    public static boolean canSendPlay() {
        try {
            return ClientPlayNetworking.canSend(LotteryNetwork.ConfigRequestC2S.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestDailyTasks() {
        if (!ClientPlayNetworking.canSend(DailyTaskRequestC2S.TYPE)) return false;
        ClientPlayNetworking.send(DailyTaskRequestC2S.INSTANCE);
        return true;
    }

    public static boolean clientClaimDailyTask(String taskId) {
        if (taskId == null || !ClientPlayNetworking.canSend(DailyTaskClaimC2S.TYPE)) return false;
        ClientPlayNetworking.send(new DailyTaskClaimC2S(taskId));
        return true;
    }

    public static boolean clientRequestSnapshot() {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.ConfigRequestC2S());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientSave(String json) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.ConfigSaveC2S(json));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientReload() {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.ConfigReloadC2S());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestPlayerList() {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(LotteryNetwork.PlayerListRequestC2S.INSTANCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientModifyChance(String mode, String targetUuid, int value) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.PlayerChanceModifyC2S(
                    mode, targetUuid == null ? "" : targetUuid, value));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientModifyPlayerCard(
            String targetUuid,
            String questKey,
            String operation,
            int value
    ) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.PlayerCardModifyC2S(
                    targetUuid == null ? "" : targetUuid,
                    questKey == null ? "" : questKey,
                    operation == null ? "" : operation,
                    value));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestBackup() {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(LotteryNetwork.BackupRequestC2S.INSTANCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientSaveTitleCatalog(String json) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.TitleCatalogSaveC2S(json == null ? "" : json));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientTitleModify(String mode, String targetUuid, String payload) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.TitlePlayerModifyC2S(
                    mode == null ? "" : mode,
                    targetUuid == null ? "" : targetUuid,
                    payload == null ? "" : payload));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestTitleSnapshot() {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(LotteryNetwork.TitleSnapshotRequestC2S.INSTANCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestMailbox() {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(MailboxRequestC2S.INSTANCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientClaimMail(String mailId) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new MailboxClaimC2S(mailId == null ? "" : mailId));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientCardUseConfirm(String questKey, String mode, String roleId) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new com.habitrain.lottery.network.CardUseConfirmC2S(
                    questKey == null ? "" : questKey,
                    mode == null ? "" : mode,
                    roleId == null ? "" : roleId));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

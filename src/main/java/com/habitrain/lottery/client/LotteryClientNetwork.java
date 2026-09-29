package com.habitrain.lottery.client;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.client.gui.MailComposeScreen;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-only networking. Kept out of {@link LotteryNetwork} so dedicated servers never
 * resolve client classes (Screen / ClientPlayNetworking) during main entrypoint init.
 */
@Environment(EnvType.CLIENT)
public final class LotteryClientNetwork {
    private static final Gson GSON = new Gson();

    /** Guards the one-time registration of this mod's receivers. */
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private LotteryClientNetwork() {
    }

    /** Idempotent client receiver registration. */
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
    }

    private static void registerCommonReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(DailyTaskBoardS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        DailyTaskSnapshot board = GSON.fromJson(payload.json(), DailyTaskSnapshot.class);
                        if (board == null) return;
                        LotteryNetwork.ClientLotteryState.greenApples = board.greenApples();
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
        ClientPlayNetworking.registerGlobalReceiver(LotteryNetwork.OpStatusS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                LotteryNetwork.ClientLotteryState.op = payload.op();
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
                // 邮箱已并入每日任务终端：已打开终端就切页，否则直接落在「邮箱」页
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof DailyTaskScreen screen) {
                    screen.openMailTab();
                } else {
                    mc.setScreen(DailyTaskScreen.mailbox(mc.screen));
                }
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

    public static boolean canSendPlay() {
        try {
            return ClientPlayNetworking.canSend(LotteryNetwork.PlayerListRequestC2S.TYPE);
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

    public static boolean clientModifyGreenApples(String mode, String targetUuid, int value) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new LotteryNetwork.PlayerGreenApplesModifyC2S(
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

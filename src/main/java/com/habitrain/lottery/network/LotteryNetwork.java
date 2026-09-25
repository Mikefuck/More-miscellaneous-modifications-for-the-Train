package com.habitrain.lottery.network;

import com.habitrain.core.network.C2SRateLimiter;
import com.habitrain.lottery.card.CardUseService;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiAssetOperation;
import com.habitrain.lottery.api.player.HabiAssetPolicy;
import com.habitrain.lottery.api.player.HabiLotteryApi;
import com.habitrain.lottery.backpack.PlayerCardAdminModels;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.backpack.PlayerCardMutationPolicy;
import com.habitrain.lottery.meta.MenuGateServerBridge;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.storage.LotteryBackupService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.PlayerTitleData;
import com.habitrain.lottery.title.TitleCatalog;
import com.habitrain.lottery.title.TitlePaths;
import com.habitrain.lottery.title.TitleService;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Common/server networking for habitrain_lottery.
 * <p>
 * Client receivers and C2S send helpers live in
 * {@code com.habitrain.lottery.client.LotteryClientNetwork} so dedicated servers never
 * resolve client-only classes during {@code main} entrypoint init.
 */
public final class LotteryNetwork {
    private static final Gson GSON = new Gson();

    private LotteryNetwork() {
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(DailyTaskBoardS2C.TYPE, DailyTaskBoardS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(DailyTaskRequestC2S.TYPE, DailyTaskRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(DailyTaskClaimC2S.TYPE, DailyTaskClaimC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayerListRequestC2S.TYPE, PlayerListRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayerGreenApplesModifyC2S.TYPE, PlayerGreenApplesModifyC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayerCardModifyC2S.TYPE, PlayerCardModifyC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(BackupRequestC2S.TYPE, BackupRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MailComposeC2SPayload.TYPE, MailComposeC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MailboxClaimC2S.TYPE, MailboxClaimC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MailboxRequestC2S.TYPE, MailboxRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(TitleCatalogSaveC2S.TYPE, TitleCatalogSaveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(TitlePlayerModifyC2S.TYPE, TitlePlayerModifyC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(TitleSnapshotRequestC2S.TYPE, TitleSnapshotRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CardUseRequestC2S.TYPE, CardUseRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CardUseConfirmC2S.TYPE, CardUseConfirmC2S.CODEC);

        PayloadTypeRegistry.playS2C().register(OpStatusS2C.TYPE, OpStatusS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerListS2C.TYPE, PlayerListS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BackupDataS2C.TYPE, BackupDataS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(AdminActionResultS2C.TYPE, AdminActionResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(LoginStateS2C.TYPE, LoginStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenMailComposeS2C.TYPE, OpenMailComposeS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenMailboxS2C.TYPE, OpenMailboxS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MailboxListS2C.TYPE, MailboxListS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TitleSnapshotS2C.TYPE, TitleSnapshotS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CardUseMenuS2C.TYPE, CardUseMenuS2C.CODEC);
    }

    public static void registerServer() {
        registerPayloadTypes();
        WarehouseNetwork.register();

        ServerPlayNetworking.registerGlobalReceiver(DailyTaskRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player != null && !rateLimited(player, "daily_task_request", 350))
                        sendDailyTaskSnapshot(player, false);
                }));
        ServerPlayNetworking.registerGlobalReceiver(DailyTaskClaimC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || rateLimited(player, "daily_task_claim", 350)) return;
                    ResourceLocation id = ResourceLocation.tryParse(payload.taskId());
                    if (id != null && com.habitrain.lottery.api.daily.HabiDailyTaskApi.claim(player, id)) {
                        player.sendSystemMessage(Component.literal("§a[每日任务] 奖励已领取"));
                    } else {
                        player.sendSystemMessage(Component.literal("§c[每日任务] 暂时无法领取，请刷新任务状态"));
                        sendDailyTaskSnapshot(player, false);
                    }
                }));

        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "player_list_req", 2000)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handlePlayerList(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PlayerGreenApplesModifyC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "player_green_apples_mod", 2000)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handleGreenApplesModify(player, payload);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PlayerCardModifyC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "player_card_mod", 500)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handleCardModify(player, payload);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(BackupRequestC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "backup_req", 8000)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handleBackup(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(MailComposeC2SPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "mail_compose", 2000)) {
                    player.sendSystemMessage(Component.literal("§c[邮件] 操作过快"));
                    return;
                }
                MailComposeC2SPayload.handle(player, payload);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(MailboxClaimC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || rateLimited(player, "mailbox_claim", 500)) {
                    return;
                }
                String mailId = payload.mailId();
                if (mailId == null || mailId.isBlank() || "*".equals(mailId)) {
                    MailService.claimAll(player);
                } else {
                    MailService.claim(player, mailId);
                }
                sendMailboxList(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(MailboxRequestC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || rateLimited(player, "mailbox_request", 500)) {
                    return;
                }
                sendMailboxList(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TitleCatalogSaveC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "title_catalog_save", 2000)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handleTitleCatalogSave(player, payload.json());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TitlePlayerModifyC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "title_player_mod", 2000)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handleTitlePlayerModify(player, payload);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(TitleSnapshotRequestC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) {
                    return;
                }
                if (rateLimited(player, "title_snapshot_req", 2000)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("操作过快", false));
                    return;
                }
                handleTitleSnapshotRequest(player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CardUseRequestC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || rateLimited(player, "card_use_request", 500)) {
                    return;
                }
                com.habitrain.lottery.card.CardUseService.handleRequest(player, payload.questKey());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(CardUseConfirmC2S.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || rateLimited(player, "card_use_confirm", 500)) {
                    return;
                }
                com.habitrain.lottery.card.CardUseService.handleConfirm(
                        player, payload.questKey(), payload.mode(), payload.roleId());
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendOpStatus(handler.player);
        });
    }

    public static void sendLoginState(ServerPlayer player) {
        if (player == null) {
            return;
        }
        var data = PlayerLotteryStore.get().getOrLoad(player);
        long epoch = data.lastLoginEpochDay >= 0
                ? data.lastLoginEpochDay
                : com.habitrain.lottery.grant.LoginRewardService.todayEpochDayUtc();
        java.time.LocalDate date = java.time.LocalDate.ofEpochDay(epoch);
        int mask = 0;
        if (data.loginDaysThisMonth != null) {
            for (Integer d : data.loginDaysThisMonth) {
                if (d != null && d >= 1 && d <= 31) {
                    mask |= (1 << (d - 1));
                }
            }
        }
        int streak = Math.max(0, data.consecutiveLoginDays);
        sendIfSupported(player, new LoginStateS2C(
                epoch,
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth(),
                streak,
                mask
        ));
    }

    /**
     * 审核 N-05：S2C 发送统一加 {@code canSend} 守卫。
     *
     * <p>旧实现全部直接 {@code ServerPlayNetworking.send(...)}。因为 {@code depends} 是硬依赖，
     * 原版客户端进不了服，所以目前只是潜在问题；但通过代理 / 未安装本模组客户端的
     * 边缘场景下会在服务端抛异常，而<b>客户端</b>侧早已普遍使用 {@code canSendPlay()}（不对称）。
     *
     * <p>本方法在客户端不支持该 payload 时静默跳过并记 debug 日志，绝不抛出。
     */
    public static boolean sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null) {
            return false;
        }
        try {
            if (!ServerPlayNetworking.canSend(player, payload.type())) {
                HabiLotteryMod.LOGGER.debug(
                        "skip S2C {} to {}: client cannot receive it",
                        payload.type().id(), player.getGameProfile().getName());
                return false;
            }
            ServerPlayNetworking.send(player, payload);
            return true;
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("S2C {} failed: {}", payload.type().id(), t.toString());
            return false;
        }
    }

    public static void sendOpenMailCompose(ServerPlayer player) {
        sendIfSupported(player, OpenMailComposeS2C.INSTANCE);
    }

    public static void sendOpenMailbox(ServerPlayer player) {
        sendIfSupported(player, OpenMailboxS2C.INSTANCE);
        sendMailboxList(player);
    }

    /** Public server-side opener for the board block and other mods. */
    public static void sendDailyTaskSnapshot(ServerPlayer player, boolean open) {
        if (player == null || !WorldLotteryPaths.ready()
                || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) return;
        try {
            String json = GSON.toJson(com.habitrain.lottery.daily.DailyTaskBoardService.snapshot(player));
            sendIfSupported(player, new DailyTaskBoardS2C(json, open));
        } catch (RuntimeException error) {
            HabiLotteryMod.LOGGER.warn("Daily task snapshot failed for {}", player.getUUID(), error);
        }
    }

    public static void sendMailboxList(ServerPlayer player) {
        if (player == null) {
            return;
        }
        String json = GSON.toJson(MailService.list(player));
        sendIfSupported(player, new MailboxListS2C(json));
    }

    public static boolean isOp(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return player.hasPermissions(2);
    }

    /** Mod 菜单门控：专用服务器上门控开启且该玩家未授权时拒绝其配置写入请求。 */
    public static boolean gateBlocked(ServerPlayer player) {
        return MenuGateServerBridge.isBlocked(player, player == null ? null : player.server);
    }

    /**
     * Per-player C2S cooldown. Delegates to habitrain_core's shared
     * {@link C2SRateLimiter} — there is exactly one limiter table in the JVM, so a
     * flood on a lottery channel also counts against the core's own channels and
     * vice versa.
     *
     * @return {@code true} when the slot is free (the old local copy's semantics)
     */
    public static boolean tryAcquire(UUID playerId, String channel, long cooldownMs) {
        return C2SRateLimiter.tryAcquire(playerId, channel, cooldownMs);
    }

    /** @return {@code true} if the packet should be ignored (cooldown not elapsed). */
    public static boolean rateLimited(ServerPlayer player, String channel, long cooldownMs) {
        if (player == null) {
            return true;
        }
        return !tryAcquire(player.getUUID(), channel, cooldownMs);
    }

    /**
     * Clears the shared limiter slots of one player so a reconnect does not inherit
     * the previous session's stamps.
     *
     * <p><b>Wiring note for {@code HabiLotteryMod}:</b> this must be called from the
     * {@code ServerPlayConnectionEvents.DISCONNECT} handler
     * ({@code LotteryNetwork.clearRateLimits(handler.player.getUUID())}). The
     * disconnect handler lives in {@code HabiLotteryMod} and is owned by the lead,
     * so it is not edited here; without that call the limiter keeps a disconnected
     * player's last stamp forever.
     *
     * <p>Null-safe: a {@code null} uuid clears nothing and never throws.
     */
    public static void clearRateLimits(UUID playerId) {
        if (playerId == null) {
            return;
        }
        try {
            C2SRateLimiter.clear(playerId);
        } catch (Throwable t) {
            // Cleanup must never fail a disconnect path.
            HabiLotteryMod.LOGGER.debug("Failed clearing C2S rate limits for {}: {}", playerId, t.toString());
        }
    }

    private static void handlePlayerList(ServerPlayer player) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能查看玩家资产", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        PlayerAdminModels.PlayerListSnapshot snap = new PlayerAdminModels.PlayerListSnapshot();
        snap.serverTime = System.currentTimeMillis();
        snap.players = PlayerLotteryStore.get().listPlayers(player.server);
        for (PlayerAdminModels.PlayerRow row : snap.players) {
            if (row == null || row.uuid == null || row.uuid.isBlank()) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(row.uuid);
                ServerPlayer online = player.server.getPlayerList().getPlayer(uuid);
                PlayerCardAdminModels.CardSnapshot cards = online != null
                        ? PlayerCardAdminService.snapshotOnline(online)
                        : PlayerCardAdminService.snapshotOffline(uuid);
                row.cardStatus = cards.status().name();
                row.cards = new LinkedHashMap<>(cards.cards());
            } catch (Exception e) {
                row.cardStatus = PlayerCardAdminModels.CardStoreStatus.CORRUPT.name();
                row.cards = new LinkedHashMap<>();
                HabiLotteryMod.LOGGER.warn("Failed reading admin card snapshot for {}: {}", row.uuid, e.toString());
            }
        }
        ServerPlayNetworking.send(player, new PlayerListS2C(GSON.toJson(snap)));
    }

    private static void handleCardModify(ServerPlayer player, PlayerCardModifyC2S payload) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能修改角色卡", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        if (!WorldLotteryPaths.ready()) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("世界角色卡存储未就绪", false));
            return;
        }
        try {
            UUID target = UUID.fromString(payload.targetUuid() == null ? "" : payload.targetUuid().trim());
            boolean limitBreak = "limit_break".equalsIgnoreCase(payload.questKey());
            if (limitBreak || "self_select".equalsIgnoreCase(payload.questKey())) {
                var op = PlayerCardAdminModels.CardOperation.parse(payload.operation());
                var result = limitBreak ? PlayerCardAdminService.mutateLimitBreak(target, op, payload.value())
                        : PlayerCardAdminService.mutateSelfSelect(target, op, payload.value());
                ServerPlayNetworking.send(player, new AdminActionResultS2C(
                        result.ok() ? (limitBreak ? "破限卡已更新" : "自选卡已更新") : result.message(), false));
                if (result.ok()) {
                    handlePlayerList(player);
                    CardUseService.handleRequest(player.server.getPlayerList().getPlayer(target), CardUseService.INVENTORY_KEY);
                }
                return;
            }
            FactionCardType type = PlayerCardMutationPolicy.parseType(payload.questKey());
            PlayerCardAdminModels.CardOperation operation =
                    PlayerCardAdminModels.CardOperation.parse(payload.operation());
            if (type == null) {
                ServerPlayNetworking.send(player, new AdminActionResultS2C("未知角色卡类型", false));
                return;
            }
            PlayerCardMutationPolicy.validate(operation, payload.value());
            PlayerCardAdminModels.CardMutationResult result = PlayerCardAdminService.mutate(
                    player.server, target, type, operation, payload.value());
            if (!result.ok()) {
                ServerPlayNetworking.send(player, new AdminActionResultS2C(result.message(), false));
                return;
            }
            // ADD 改的是增量，不能回显成「设为」——那会把 +3 说成设成 3，误导管理员。
            String cardName = Component.translatable(
                    "screen.habitrain_lottery.config.cards." + type.questKey).getString();
            String msg = operation == PlayerCardAdminModels.CardOperation.ADD
                    ? "已将玩家 " + cardName + " 角色卡" + (payload.value() > 0 ? "增加 " : "减少 ")
                            + Math.abs(payload.value()) + " 张（现为 " + result.newCount() + "）"
                    : "已将玩家 " + cardName + " 角色卡设为 " + result.newCount();
            ServerPlayNetworking.send(player, new AdminActionResultS2C(msg, false));
            handlePlayerList(player);
            CardUseService.handleRequest(player.server.getPlayerList().getPlayer(target), CardUseService.INVENTORY_KEY);
        } catch (IllegalArgumentException e) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("角色卡修改失败: " + e.getMessage(), false));
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Player card admin mutation failed: {}", e.toString());
            ServerPlayNetworking.send(player, new AdminActionResultS2C("角色卡修改失败: " + e.getMessage(), false));
        }
    }

    private static void handleGreenApplesModify(ServerPlayer player, PlayerGreenApplesModifyC2S payload) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能修改绿苹果", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        try {
            String mode = payload.mode() == null ? "" : payload.mode();
            int value = payload.value();
            if (("add_all_online".equals(mode) || "add_one".equals(mode))
                    && !HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.ADD, value)) {
                ServerPlayNetworking.send(player, new AdminActionResultS2C("绿苹果增量不能为 0", false));
                return;
            }
            if (("set_all_online".equals(mode) || "set_one".equals(mode))
                    && !HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.SET, value)) {
                ServerPlayNetworking.send(player, new AdminActionResultS2C("绿苹果余额不能为负数", false));
                return;
            }
            String target = payload.targetUuid() == null ? "" : payload.targetUuid();
            String msg;
            switch (mode) {
                case "add_all_online" -> {
                    int n = HabiLotteryApi.addGreenApplesToOnline(value);
                    msg = "已为在线 " + n + " 人调整绿苹果 " + (value >= 0 ? "+" : "") + value;
                }
                case "set_all_online" -> {
                    int n = HabiLotteryApi.setGreenApplesToOnline(value);
                    msg = "已将在线 " + n + " 人绿苹果设为 " + Math.max(0, value);
                }
                case "add_one" -> {
                    UUID id = UUID.fromString(target);
                    var result = HabiLotteryApi.addGreenApples(id, value);
                    if (!result.ok()) {
                        ServerPlayNetworking.send(player, new AdminActionResultS2C(
                                result.message(), false));
                        return;
                    }
                    msg = "已调整玩家绿苹果 " + (value >= 0 ? "+" : "") + value
                            + "（现为 " + result.newValue() + "）";
                }
                case "set_one" -> {
                    UUID id = UUID.fromString(target);
                    var result = HabiLotteryApi.setGreenApples(id, value);
                    if (!result.ok()) {
                        ServerPlayNetworking.send(player, new AdminActionResultS2C(
                                result.message(), false));
                        return;
                    }
                    msg = "已将玩家绿苹果设为 " + result.newValue();
                }
                default -> {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("未知修改模式: " + mode, false));
                    return;
                }
            }
            ServerPlayNetworking.send(player, new AdminActionResultS2C(msg, true));
            handlePlayerList(player);
        } catch (Exception e) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("修改失败: " + e.getMessage(), false));
        }
    }

    private static void handleBackup(ServerPlayer player) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能备份", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        LotteryBackupService.Result result = LotteryBackupService.createTimestampedBackup();
        ServerPlayNetworking.send(player, new AdminActionResultS2C(result.message(), result.ok()));
    }

    private static void handleTitleCatalogSave(ServerPlayer player, String json) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能保存称号模板", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        if (!WorldLotteryPaths.ready()) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("世界称号存储未就绪", false));
            return;
        }
        try {
            TitleCatalog catalog = GSON.fromJson(json == null ? "" : json, TitleCatalog.class);
            if (catalog == null) {
                catalog = new TitleCatalog();
            }
            if (catalog.titles == null) {
                catalog.titles = new ArrayList<>();
            }
            // Reject duplicate template ids
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (TitleCatalog.TitleEntry e : catalog.titles) {
                if (e == null) {
                    continue;
                }
                String id = e.id == null ? "" : e.id.trim();
                if (id.isEmpty()) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("称号模板 id 不能为空", false));
                    return;
                }
                if (!seen.add(id)) {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("称号模板 id 重复: " + id, false));
                    return;
                }
                e.id = id;
                if (e.display == null) {
                    e.display = "";
                }
            }
            if (catalog.version <= 0) {
                catalog.version = 1;
            }
            TitlePaths.ensureDirs();
            LocalTitleStore.get().saveCatalog(catalog);
            ServerPlayNetworking.send(player, new AdminActionResultS2C("称号模板库已保存", false));
            if (player.server != null) {
                for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
                    sendTitleSnapshot(p);
                }
            } else {
                sendTitleSnapshot(player);
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Title catalog save failed: {}", e.toString());
            ServerPlayNetworking.send(player, new AdminActionResultS2C("称号模板保存失败: " + e.getMessage(), false));
        }
    }

    private static void handleTitlePlayerModify(ServerPlayer player, TitlePlayerModifyC2S payload) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能修改称号", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        if (!WorldLotteryPaths.ready()) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("世界称号存储未就绪", false));
            return;
        }
        try {
            String mode = payload.mode() == null ? "" : payload.mode().trim();
            String target = payload.targetUuid() == null ? "" : payload.targetUuid().trim();
            String display = payload.payload() == null ? "" : payload.payload();
            UUID id = UUID.fromString(target);
            ServerPlayer online = player.server.getPlayerList().getPlayer(id);
            boolean ok;
            String msg;
            switch (mode) {
                case "grant" -> {
                    if (display.isBlank()) {
                        ServerPlayNetworking.send(player, new AdminActionResultS2C("授予称号不能为空", false));
                        return;
                    }
                    if (online != null) {
                        ok = TitleService.grantOnline(online, display);
                    } else {
                        ok = TitleService.grantOffline(id, display);
                    }
                    msg = ok ? "已授予称号" : "授予失败（可能已拥有）";
                }
                case "revoke" -> {
                    if (display.isBlank()) {
                        ServerPlayNetworking.send(player, new AdminActionResultS2C("收回称号不能为空", false));
                        return;
                    }
                    if (online != null) {
                        ok = TitleService.revokeOnline(online, display);
                    } else {
                        ok = TitleService.revokeOffline(id, display);
                    }
                    msg = ok ? "已收回称号" : "收回失败（未拥有）";
                }
                case "set_current" -> {
                    if (online != null) {
                        ok = TitleService.setCurrentOnline(online, display);
                    } else {
                        ok = TitleService.setCurrentOffline(id, display);
                    }
                    msg = ok ? (display.isBlank() ? "已清空佩戴" : "已设置佩戴称号") : "设佩戴失败（须已拥有）";
                }
                case "clear" -> {
                    if (online != null) {
                        TitleService.clearOnline(online);
                    } else {
                        TitleService.clearOffline(id);
                    }
                    ok = true;
                    msg = "已清空玩家称号";
                }
                default -> {
                    ServerPlayNetworking.send(player, new AdminActionResultS2C("未知称号操作: " + mode, false));
                    return;
                }
            }
            ServerPlayNetworking.send(player, new AdminActionResultS2C(msg, false));
            if (ok) {
                sendTitleSnapshot(player);
            }
        } catch (IllegalArgumentException e) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("无效玩家 UUID", false));
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Title player modify failed: {}", e.toString());
            ServerPlayNetworking.send(player, new AdminActionResultS2C("称号修改失败: " + e.getMessage(), false));
        }
    }

    private static void handleTitleSnapshotRequest(ServerPlayer player) {
        if (!isOp(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("需要 OP 才能查看称号", false));
            return;
        }
        if (gateBlocked(player)) {
            ServerPlayNetworking.send(player, new AdminActionResultS2C("当前为未授权的访问", false));
            return;
        }
        sendTitleSnapshot(player);
    }

    public static void sendTitleSnapshot(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            if (WorldLotteryPaths.ready()) {
                TitlePaths.ensureDirs();
            }
            TitleCatalog catalog = LocalTitleStore.get().loadCatalog();
            String catalogJson = GSON.toJson(catalog);

            Map<String, Object> players = new LinkedHashMap<>();
            // Prefer online CCA snapshot via store (persist keeps them aligned); also disk files.
            List<UUID> ids = LocalTitleStore.get().listKnownPlayerIds();
            if (player.server != null) {
                for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                    if (online != null && !ids.contains(online.getUUID())) {
                        ids.add(online.getUUID());
                    }
                }
            }
            for (UUID id : ids) {
                PlayerTitleData data = LocalTitleStore.get().loadPlayer(id);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("owned", data.owned != null ? data.owned : List.of());
                entry.put("current", data.current != null ? data.current : "");
                players.put(id.toString(), entry);
            }
            String playersJson = GSON.toJson(players);
            ServerPlayNetworking.send(player, new TitleSnapshotS2C(catalogJson, playersJson));
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Title snapshot failed for {}: {}", player.getGameProfile().getName(), e.toString());
            ServerPlayNetworking.send(player, new AdminActionResultS2C("称号快照失败: " + e.getMessage(), false));
        }
    }

    public static void sendOpStatus(ServerPlayer player) {
        if (player != null) {
            ServerPlayNetworking.send(player, new OpStatusS2C(isOp(player)));
        }
    }

    // ---- packets ----

    public record OpStatusS2C(boolean op) implements CustomPacketPayload {
        public static final Type<OpStatusS2C> TYPE = new Type<>(id("op_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpStatusS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBoolean(value.op),
                buf -> new OpStatusS2C(buf.readBoolean())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayerListRequestC2S() implements CustomPacketPayload {
        public static final PlayerListRequestC2S INSTANCE = new PlayerListRequestC2S();
        public static final Type<PlayerListRequestC2S> TYPE = new Type<>(id("player_list_req"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerListRequestC2S> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayerListS2C(String json) implements CustomPacketPayload {
        public static final Type<PlayerListS2C> TYPE = new Type<>(id("player_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerListS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.json, 1_000_000),
                buf -> new PlayerListS2C(buf.readUtf(1_000_000))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayerGreenApplesModifyC2S(String mode, String targetUuid, int value) implements CustomPacketPayload {
        public static final Type<PlayerGreenApplesModifyC2S> TYPE = new Type<>(id("player_green_apples_mod"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerGreenApplesModifyC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.mode == null ? "" : value.mode, 64);
                    buf.writeUtf(value.targetUuid == null ? "" : value.targetUuid, 64);
                    buf.writeVarInt(value.value);
                },
                buf -> new PlayerGreenApplesModifyC2S(buf.readUtf(64), buf.readUtf(64), buf.readVarInt())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayerCardModifyC2S(
            String targetUuid,
            String questKey,
            String operation,
            int value
    ) implements CustomPacketPayload {
        public static final Type<PlayerCardModifyC2S> TYPE = new Type<>(id("player_card_mod"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCardModifyC2S> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.targetUuid == null ? "" : payload.targetUuid, 64);
                    buf.writeUtf(payload.questKey == null ? "" : payload.questKey, 32);
                    buf.writeUtf(payload.operation == null ? "" : payload.operation, 8);
                    buf.writeVarInt(payload.value);
                },
                buf -> new PlayerCardModifyC2S(
                        buf.readUtf(64),
                        buf.readUtf(32),
                        buf.readUtf(8),
                        buf.readVarInt())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BackupRequestC2S() implements CustomPacketPayload {
        public static final BackupRequestC2S INSTANCE = new BackupRequestC2S();
        public static final Type<BackupRequestC2S> TYPE = new Type<>(id("backup_req"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackupRequestC2S> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BackupDataS2C(String json) implements CustomPacketPayload {
        public static final Type<BackupDataS2C> TYPE = new Type<>(id("backup_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackupDataS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.json, 1_000_000),
                buf -> new BackupDataS2C(buf.readUtf(1_000_000))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminActionResultS2C(String message, boolean refreshPlayers) implements CustomPacketPayload {
        public static final Type<AdminActionResultS2C> TYPE = new Type<>(id("admin_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminActionResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.message == null ? "" : value.message, 512);
                    buf.writeBoolean(value.refreshPlayers);
                },
                buf -> new AdminActionResultS2C(buf.readUtf(512), buf.readBoolean())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LoginStateS2C(
            long epochDay,
            int year,
            int month,
            int dayOfMonth,
            int streak,
            int loginDaysMask
    ) implements CustomPacketPayload {
        public static final Type<LoginStateS2C> TYPE = new Type<>(id("login_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LoginStateS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeLong(value.epochDay);
                    buf.writeVarInt(value.year);
                    buf.writeVarInt(value.month);
                    buf.writeVarInt(value.dayOfMonth);
                    buf.writeVarInt(value.streak);
                    buf.writeInt(value.loginDaysMask);
                },
                buf -> new LoginStateS2C(
                        buf.readLong(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readInt()
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TitleCatalogSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<TitleCatalogSaveC2S> TYPE = new Type<>(id("title_catalog_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TitleCatalogSaveC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.json == null ? "" : value.json, 1_000_000),
                buf -> new TitleCatalogSaveC2S(buf.readUtf(1_000_000))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TitlePlayerModifyC2S(String mode, String targetUuid, String payload) implements CustomPacketPayload {
        public static final Type<TitlePlayerModifyC2S> TYPE = new Type<>(id("title_player_mod"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TitlePlayerModifyC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.mode == null ? "" : value.mode, 64);
                    buf.writeUtf(value.targetUuid == null ? "" : value.targetUuid, 64);
                    buf.writeUtf(value.payload == null ? "" : value.payload, 1024);
                },
                buf -> new TitlePlayerModifyC2S(buf.readUtf(64), buf.readUtf(64), buf.readUtf(1024))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TitleSnapshotRequestC2S() implements CustomPacketPayload {
        public static final TitleSnapshotRequestC2S INSTANCE = new TitleSnapshotRequestC2S();
        public static final Type<TitleSnapshotRequestC2S> TYPE = new Type<>(id("title_snapshot_req"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TitleSnapshotRequestC2S> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TitleSnapshotS2C(String catalogJson, String playersJson) implements CustomPacketPayload {
        public static final Type<TitleSnapshotS2C> TYPE = new Type<>(id("title_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TitleSnapshotS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.catalogJson == null ? "" : value.catalogJson, 1_000_000);
                    buf.writeUtf(value.playersJson == null ? "" : value.playersJson, 1_000_000);
                },
                buf -> new TitleSnapshotS2C(buf.readUtf(1_000_000), buf.readUtf(1_000_000))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }

    /** Client-side cache (fields only; no client class references). */
    public static final class ClientLotteryState {
        public static boolean op;
        public static PlayerAdminModels.PlayerListSnapshot playerList = new PlayerAdminModels.PlayerListSnapshot();
        public static int playerListVersion;
        public static String lastBackupJson = "";
        public static int lastBackupVersion;
        public static String lastAdminMessage = "";
        /** Full title catalog JSON from last TitleSnapshotS2C. */
        public static String titleCatalogJson = "";
        /** Compact players map JSON: uuid → {owned,current}. */
        public static String titlePlayersJson = "";
        public static int titleVersion;
        /** Mailbox list JSON from last MailboxListS2C (list of MailJson). */
        public static String mailboxJson = "";
        public static int mailboxVersion;
        public static int greenApples;
        /** Card-use menu state from CardUseMenuS2C (questKey, remaining uses, candidate JSON). */
        public static String cardUseMenuQuestKey = "";
        public static int cardUseRemainingUses;
        public static int cardUseRemainingSelfUses;
        public static java.util.Map<String, Integer> cardBalances = java.util.Map.of();
        public static int cardInventoryVersion;
        public static String cardUseCandidatesJson = "";
        public static int cardUseMenuVersion;
    }

    /** Client cache for login calendar BER. */
    public static final class ClientLoginState {
        public static boolean hasData;
        public static long epochDay;
        public static int year;
        public static int month;
        public static int dayOfMonth;
        public static int streak;
        /** Bit i set => day-of-month (i+1) logged in this month. */
        public static int loginDaysMask;
        public static int version;

        public static boolean loggedIn(int dayOfMonth) {
            if (dayOfMonth < 1 || dayOfMonth > 31) {
                return false;
            }
            return (loginDaysMask & (1 << (dayOfMonth - 1))) != 0;
        }
    }
}

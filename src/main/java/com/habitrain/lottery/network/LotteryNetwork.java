package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.core.network.C2SRateLimiter;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.meta.MenuGateServerBridge;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common/server networking coordinator for {@code habitrain_lottery} ({@code N-01}, {@code B-10}, {@code N-02}).
 *
 * <p>Client receivers and C2S send helpers live in {@code com.habitrain.lottery.client.LotteryClientNetwork}.
 * Payload records and client state snapshots live in top-level classes in {@code com.habitrain.lottery.network}.
 * Server admin and title business handlers live in {@link LotteryAdminServerHandlers} and
 * {@link LotteryTitleServerHandlers}.
 */
public final class LotteryNetwork {
    public static final int ADMIN_C2S_JSON_MAX_CHARS = ChunkedJsonTransfer.C2S_CHUNK_CHARS;
    public static final int ADMIN_S2C_JSON_MAX_CHARS = 262_144;

    private static final Gson GSON = new Gson();
    private static final Map<UUID, Boolean> LAST_SENT_OP_STATUS = new ConcurrentHashMap<>();
    private static volatile int lastOpSyncTick = -1;

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
        PayloadTypeRegistry.playC2S().register(TitlePlayerPageRequestC2S.TYPE, TitlePlayerPageRequestC2S.CODEC);
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
        PayloadTypeRegistry.playS2C().register(TitlePlayerPageS2C.TYPE, TitlePlayerPageS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CardUseMenuS2C.TYPE, CardUseMenuS2C.CODEC);
    }

    public static void registerServer() {
        registerPayloadTypes();
        WarehouseNetwork.register();

        ServerPlayNetworking.registerGlobalReceiver(DailyTaskRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (!rateLimited(player, "daily_task_request", 350)) {
                        sendDailyTaskSnapshot(player, false);
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(DailyTaskClaimC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "daily_task_claim", 350)) return;
                    if (PlayerStateGate.spectatorRestOrDead(player)) {
                        player.sendSystemMessage(PlayerStateGate.actionBlockedComponent());
                        sendDailyTaskSnapshot(player, false);
                        return;
                    }
                    ResourceLocation id = ResourceLocation.tryParse(payload.taskId());
                    if (id != null && com.habitrain.lottery.api.daily.HabiDailyTaskApi.claim(player, id)) {
                        player.sendSystemMessage(Component.translatable("message.habitrain_lottery.daily.claimed"));
                    } else {
                        player.sendSystemMessage(Component.translatable("message.habitrain_lottery.daily.claim_failed"));
                        sendDailyTaskSnapshot(player, false);
                    }
                }));

        ServerPlayNetworking.registerGlobalReceiver(PlayerListRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "player_list_req", 350)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryAdminServerHandlers.handlePlayerList(player, payload);
                }));
        ServerPlayNetworking.registerGlobalReceiver(PlayerGreenApplesModifyC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "player_green_apples_mod", 1000)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryAdminServerHandlers.handleGreenApplesModify(player, payload);
                }));
        ServerPlayNetworking.registerGlobalReceiver(PlayerCardModifyC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "player_card_mod", 500)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryAdminServerHandlers.handleCardModify(player, payload);
                }));
        ServerPlayNetworking.registerGlobalReceiver(BackupRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "backup_req", 8000)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryAdminServerHandlers.handleBackup(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(MailComposeC2SPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "mail_compose", 2000)) {
                        player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.rate_limited"));
                        return;
                    }
                    MailComposeC2SPayload.handle(player, payload);
                }));
        ServerPlayNetworking.registerGlobalReceiver(MailboxClaimC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected() || rateLimited(player, "mailbox_claim", 500)) {
                        return;
                    }
                    if (PlayerStateGate.spectatorRestOrDead(player)) {
                        player.sendSystemMessage(PlayerStateGate.mailBlockedComponent());
                        sendMailboxList(player);
                        return;
                    }
                    String mailId = payload.mailId();
                    if (mailId == null || mailId.isBlank() || "*".equals(mailId)) {
                        MailService.claimAll(player);
                    } else {
                        MailService.claim(player, mailId);
                    }
                    sendMailboxList(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(MailboxRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected() || rateLimited(player, "mailbox_request", 500)) {
                        return;
                    }
                    sendMailboxList(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(TitleCatalogSaveC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    boolean authorized = AdminAuth.isAuthorizedOp(player);
                    ChunkedJsonTransfer.GateResult<Long> gate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                            player.getUUID(),
                            "title_catalog_save",
                            authorized,
                            () -> rateLimited(player, "title_catalog_save", 2000),
                            payload.json(),
                            payload.transferId(),
                            payload.chunkIndex(),
                            payload.chunkCount(),
                            payload.expectedRevision());
                    if (gate.status() == ChunkedJsonTransfer.GateStatus.UNAUTHORIZED) {
                        if (!rateLimited(player, "title_catalog_save_unauth", 1000)) {
                            LotteryTitleServerHandlers.sendCatalogAuthDenied(player);
                        }
                        return;
                    }
                    if (gate.status() == ChunkedJsonTransfer.GateStatus.RATE_LIMITED) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    if (gate.status() == ChunkedJsonTransfer.GateStatus.COMPLETED) {
                        LotteryTitleServerHandlers.handleTitleCatalogSave(
                                player, gate.json(), gate.metadata());
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(TitlePlayerModifyC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "title_player_mod", 1000)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryTitleServerHandlers.handleTitlePlayerModify(player, payload);
                }));
        ServerPlayNetworking.registerGlobalReceiver(TitleSnapshotRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "title_snapshot_req", 1000)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryTitleServerHandlers.handleTitleSnapshotRequest(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(TitlePlayerPageRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (rateLimited(player, "title_player_page_req", 350)) {
                        sendRateLimitedAdminResult(player);
                        return;
                    }
                    LotteryTitleServerHandlers.handleTitlePlayerPageRequest(player, payload);
                }));

        ServerPlayNetworking.registerGlobalReceiver(CardUseRequestC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected() || rateLimited(player, "card_use_request", 500)) {
                        return;
                    }
                    CardUseService.handleRequest(player, payload.questKey());
                }));
        ServerPlayNetworking.registerGlobalReceiver(CardUseConfirmC2S.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || player.hasDisconnected() || rateLimited(player, "card_use_confirm", 500)) {
                        return;
                    }
                    CardUseService.handleConfirm(
                            player, payload.questKey(), payload.mode(), payload.roleId());
                }));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendOpStatus(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(LotteryNetwork::syncOpStatusOnTick);
    }

    private static void sendRateLimitedAdminResult(ServerPlayer player) {
        sendIfSupported(player, AdminActionResultS2C.translated(
                "message.habitrain_lottery.admin.rate_limited", "操作过快"));
    }

    public static void sendLoginState(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) return;
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
                mask,
                com.habitrain.lottery.time.LotteryDayClock.zone().getId()));
    }

    /**
     * Unified S2C sender with {@code canSend} guard ({@code N-05}) and automatic UTF-8 chunking
     * for all large admin/catalog S2C payloads ({@code B-10}).
     */
    public static boolean sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null || player.hasDisconnected()) {
            return false;
        }
        try {
            if (payload instanceof TitlePlayerPageS2C page
                    && TitlePageTransfer.LOGICAL_PAGE.equals(page.transferId())) {
                return sendChunks(player, TitlePageTransfer.chunks(page));
            }
            if (payload instanceof PlayerListS2C list
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(list.transferId())) {
                return sendChunks(player, list.toWireChunks());
            }
            if (payload instanceof TitleSnapshotS2C snap
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(snap.transferId())) {
                return sendChunks(player, snap.toWireChunks());
            }
            if (payload instanceof CrateNetwork.CatalogS2C cat
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(cat.transferId())) {
                return sendChunks(player, cat.toWireChunks());
            }
            if (payload instanceof CrateNetwork.ConfigS2C cfg
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(cfg.transferId())) {
                return sendChunks(player, cfg.toWireChunks());
            }
            if (payload instanceof DailyTaskAdminNetwork.ConfigS2C cfg
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(cfg.transferId())) {
                return sendChunks(player, cfg.toWireChunks());
            }
            if (payload instanceof DailyShopNetwork.ConfigS2C cfg
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(cfg.transferId())) {
                return sendChunks(player, cfg.toWireChunks());
            }
            if (payload instanceof SelfSelectAdminNetwork.ConfigS2C cfg
                    && ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(cfg.transferId())) {
                return sendChunks(player, cfg.toWireChunks());
            }
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

    private static boolean sendChunks(
            ServerPlayer player,
            Iterable<? extends CustomPacketPayload> chunks
    ) {
        for (CustomPacketPayload chunk : chunks) {
            if (!sendIfSupported(player, chunk)) {
                return false;
            }
        }
        return true;
    }

    public static void sendOpenMailCompose(ServerPlayer player) {
        sendIfSupported(player, OpenMailComposeS2C.INSTANCE);
    }

    public static void sendOpenMailbox(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) return;
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            player.sendSystemMessage(PlayerStateGate.mailBlockedComponent());
            return;
        }
        sendIfSupported(player, OpenMailboxS2C.INSTANCE);
        sendMailboxList(player);
    }

    public static void sendDailyTaskSnapshot(ServerPlayer player, boolean open) {
        sendDailyTaskSnapshot(player, open, null);
    }

    public static void sendDailyTaskSnapshot(ServerPlayer player, boolean open, String shopReceipt) {
        if (player == null || player.hasDisconnected() || !WorldLotteryPaths.ready()
                || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) return;
        try {
            String json = GSON.toJson(com.habitrain.lottery.daily.DailyTaskBoardService.snapshot(player, shopReceipt));
            sendIfSupported(player, new DailyTaskBoardS2C(json, open));
        } catch (RuntimeException error) {
            HabiLotteryMod.LOGGER.warn("Daily task snapshot failed for {}", player.getUUID(), error);
        }
    }

    public static void sendMailboxList(ServerPlayer player) {
        if (player != null && !player.hasDisconnected()) {
            MailboxPages.send(player);
        }
    }

    public static boolean isOp(ServerPlayer player) {
        return AdminAuth.isOpOrSingleplayerOwner(player);
    }

    public static boolean gateBlocked(ServerPlayer player) {
        return MenuGateServerBridge.isBlocked(player, player == null ? null : player.server);
    }

    public static boolean tryAcquire(UUID playerId, String channel, long cooldownMs) {
        return C2SRateLimiter.tryAcquire(playerId, channel, cooldownMs);
    }

    public static boolean rateLimited(ServerPlayer player, String channel, long cooldownMs) {
        if (player == null || player.hasDisconnected()) {
            return true;
        }
        return !tryAcquire(player.getUUID(), channel, cooldownMs);
    }

    public static void clearRateLimits(UUID playerId) {
        MailboxPages.forget(playerId);
        LotteryAdminServerHandlers.forget(playerId);
        LotteryTitleServerHandlers.forget(playerId);
        ChunkedJsonTransfer.clearPlayer(playerId);
        if (playerId == null) return;
        LAST_SENT_OP_STATUS.remove(playerId);
        try {
            C2SRateLimiter.clear(playerId);
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("Failed clearing C2S rate limits for {}: {}", playerId, t.toString());
        }
    }

    public static void onServerStopping() {
        LAST_SENT_OP_STATUS.clear();
        LotteryAdminServerHandlers.clearAll();
        LotteryTitleServerHandlers.clearAll();
        ChunkedJsonTransfer.clearAll();
        lastOpSyncTick = -1;
    }

    public static void sendTitleSnapshot(ServerPlayer player) {
        LotteryTitleServerHandlers.sendTitleSnapshot(player);
    }

    public static void sendOpStatus(ServerPlayer player) {
        if (player != null && !player.hasDisconnected()) {
            boolean op = AdminAuth.isOpOrSingleplayerOwner(player);
            LAST_SENT_OP_STATUS.put(player.getUUID(), op);
            sendIfSupported(player, new OpStatusS2C(op));
        }
    }

    public static void syncOpStatusOnTick(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) return;
        int tick = server.getTickCount();
        if (tick == lastOpSyncTick || tick % 20 != 0) return;
        lastOpSyncTick = tick;
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online == null || online.hasDisconnected()) continue;
            boolean currentOp = AdminAuth.isOpOrSingleplayerOwner(online);
            Boolean previous = LAST_SENT_OP_STATUS.put(online.getUUID(), currentOp);
            if (previous == null || previous.booleanValue() != currentOp) {
                sendIfSupported(online, new OpStatusS2C(currentOp));
            }
        }
    }

    public static void resetClientState() {
        ClientLotteryState.resetAll();
    }
}

package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiAssetOperation;
import com.habitrain.lottery.api.player.HabiAssetPolicy;
import com.habitrain.lottery.api.player.HabiLotteryApi;
import com.habitrain.lottery.backpack.PlayerCardAdminModels;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.backpack.PlayerCardMutationPolicy;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.LotteryBackupService;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Extracted server-side player asset &amp; backup admin handlers ({@code N-01}, {@code B-10},
 * {@code I-09}, {@code N-02}).
 */
final class LotteryAdminServerHandlers {
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 50;

    private static final Gson GSON = new Gson();
    private static final Map<UUID, PageQuery> LAST_QUERIES = new ConcurrentHashMap<>();

    private LotteryAdminServerHandlers() {
    }

    record PageQuery(String query, int page, int pageSize) {
        static final PageQuery DEFAULT = new PageQuery("", 0, DEFAULT_PAGE_SIZE);
    }

    record PlayerStub(UUID uuid, String name, boolean online) {
    }

    static final Comparator<PlayerStub> STUB_COMPARATOR = Comparator
            .comparing(PlayerStub::online).reversed()
            .thenComparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(s -> s.uuid().toString());

    static int clampPageSize(int requested) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, requested));
    }

    static void forget(UUID playerId) {
        if (playerId != null) {
            LAST_QUERIES.remove(playerId);
        }
    }

    static void clearAll() {
        LAST_QUERIES.clear();
    }

    static void handlePlayerList(ServerPlayer player, PlayerListRequestC2S request) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.players.need_op_view", "需要 OP 才能查看玩家资产"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        PageQuery query = request == null
                ? LAST_QUERIES.getOrDefault(player.getUUID(), PageQuery.DEFAULT)
                : new PageQuery(
                        sanitizeQuery(request.query()),
                        Math.max(0, request.page()),
                        clampPageSize(request.pageSize()));
        LAST_QUERIES.put(player.getUUID(), query);
        pushPage(player, query);
    }

    private static void pushCurrentPage(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        PageQuery query = LAST_QUERIES.getOrDefault(player.getUUID(), PageQuery.DEFAULT);
        pushPage(player, query);
    }

    private static void pushPage(ServerPlayer player, PageQuery query) {
        MinecraftServer server = player.server;
        List<PlayerStub> stubs = collectPlayerStubs(server);
        PlayerListS2C packet = buildPaginatedPlayerList(
                stubs,
                query.query(),
                query.page(),
                query.pageSize(),
                stub -> hydratePlayerRow(server, stub));
        LAST_QUERIES.put(player.getUUID(), new PageQuery(query.query(), packet.page(), query.pageSize()));
        LotteryNetwork.sendIfSupported(player, packet);
    }

    /**
     * Pure, unit-testable pagination engine: filters &amp; sorts lightweight stubs first, and invokes
     * {@code rowHydrator} strictly for the {@code <= 50} entries on the matched page slice.
     *
     * <p>Audit {@code B-10}: NEVER silently drops matched players on the page due to byte caps;
     * {@link PlayerListS2C#toWireChunks()} streams oversized pages safely across bounded chunks.
     */
    static PlayerListS2C buildPaginatedPlayerList(
            List<PlayerStub> candidates,
            String rawQuery,
            int requestedPage,
            int requestedPageSize,
            Function<PlayerStub, PlayerAdminModels.PlayerRow> rowHydrator
    ) {
        int pageSize = clampPageSize(requestedPageSize);
        String needle = sanitizeQuery(rawQuery).toLowerCase(Locale.ROOT);

        List<PlayerStub> matched = new ArrayList<>();
        if (candidates != null) {
            for (PlayerStub stub : candidates) {
                if (stub == null || stub.uuid() == null) {
                    continue;
                }
                if (matchesQuery(stub, needle)) {
                    matched.add(stub);
                }
            }
        }
        matched.sort(STUB_COMPARATOR);

        int totalMatched = matched.size();
        int totalPages = Math.max(1, (totalMatched + pageSize - 1) / pageSize);
        int page = Math.min(Math.max(0, requestedPage), totalPages - 1);
        int from = page * pageSize;
        int to = Math.min(totalMatched, from + pageSize);

        List<PlayerAdminModels.PlayerRow> rows = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            PlayerAdminModels.PlayerRow row = rowHydrator.apply(matched.get(i));
            if (row == null) {
                continue;
            }
            if (row.name != null && row.name.length() > 64) {
                row.name = row.name.substring(0, 64);
            }
            rows.add(row);
        }

        PlayerAdminModels.PlayerListSnapshot snap = new PlayerAdminModels.PlayerListSnapshot();
        snap.serverTime = System.currentTimeMillis();
        snap.page = page;
        snap.totalPages = totalPages;
        snap.totalMatched = totalMatched;
        snap.players = rows;
        return new PlayerListS2C(GSON.toJson(snap), page, totalPages, totalMatched);
    }

    static boolean matchesQuery(PlayerStub stub, String lowerNeedle) {
        if (lowerNeedle == null || lowerNeedle.isEmpty()) {
            return true;
        }
        String name = stub.name() == null ? "" : stub.name().toLowerCase(Locale.ROOT);
        if (name.contains(lowerNeedle)) {
            return true;
        }
        return stub.uuid().toString().toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    static String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String trimmed = query.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    static List<PlayerStub> collectPlayerStubs(MinecraftServer server) {
        Map<UUID, PlayerStub> byId = new LinkedHashMap<>();
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (server != null && server.getPlayerList() != null) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (sp == null || sp.getUUID() == null || store.isLoadFailed(sp.getUUID())) {
                    continue;
                }
                String name = sp.getGameProfile().getName();
                byId.put(sp.getUUID(), new PlayerStub(
                        sp.getUUID(),
                        name == null || name.isBlank() ? shortUuid(sp.getUUID()) : name,
                        true));
            }
        }
        Path playersDir = WorldLotteryPaths.playersDir();
        if (WorldLotteryPaths.ready() && playersDir != null && Files.isDirectory(playersDir)) {
            try (var stream = Files.list(playersDir)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String fn = p.getFileName().toString();
                    if (!fn.matches("[0-9a-fA-F-]{36}\\.json(?:\\.bak)?")) {
                        return;
                    }
                    try {
                        UUID uuid = UUID.fromString(fn.substring(0, 36));
                        if (!byId.containsKey(uuid) && !store.isLoadFailed(uuid)) {
                            String name = ProfileCacheLookup.lookupCachedName(server, uuid)
                                    .orElseGet(() -> shortUuid(uuid));
                            byId.put(uuid, new PlayerStub(uuid, name, false));
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.warn("Admin player stub scan failed: {}", e.toString());
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static PlayerAdminModels.PlayerRow hydratePlayerRow(MinecraftServer server, PlayerStub stub) {
        UUID uuid = stub.uuid();
        ServerPlayer online = server == null || server.getPlayerList() == null
                ? null
                : server.getPlayerList().getPlayer(uuid);
        int greenApples = 0;
        int unlockCount = 0;
        if (online != null) {
            PlayerLotteryData d = PlayerLotteryStore.get().getOrLoad(online);
            greenApples = Math.max(0, d.greenApples);
            unlockCount = countUnlocked(d);
        } else {
            Path file = WorldLotteryPaths.playerFile(uuid);
            if (file != null) {
                var load = AtomicJsonFiles.readJson(file, PlayerLotteryData.class, GSON);
                if (load.ok() && load.value() != null) {
                    greenApples = Math.max(0, load.value().greenApples);
                    unlockCount = countUnlocked(load.value());
                }
            }
        }
        PlayerAdminModels.PlayerRow row = new PlayerAdminModels.PlayerRow(
                stub.name(), uuid, greenApples, unlockCount, online != null);
        try {
            PlayerCardAdminModels.CardSnapshot cards = online != null
                    ? PlayerCardAdminService.snapshotOnline(online)
                    : PlayerCardAdminService.snapshotOffline(uuid);
            row.cardStatus = cards.status().name();
            row.cards = new LinkedHashMap<>(cards.cards());
        } catch (Exception e) {
            row.cardStatus = PlayerCardAdminModels.CardStoreStatus.CORRUPT.name();
            row.cards = new LinkedHashMap<>();
            HabiLotteryMod.LOGGER.warn("Failed reading admin card snapshot for {}: {}", uuid, e.toString());
        }
        return row;
    }

    private static int countUnlocked(PlayerLotteryData data) {
        if (data == null || data.unlocked == null) {
            return 0;
        }
        int sum = 0;
        for (Map<String, Boolean> group : data.unlocked.values()) {
            if (group != null) {
                for (Boolean flag : group.values()) {
                    if (Boolean.TRUE.equals(flag)) {
                        sum++;
                    }
                }
            }
        }
        return sum;
    }

    static String shortUuid(UUID id) {
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    static void handleCardModify(ServerPlayer player, PlayerCardModifyC2S payload) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.cards.need_op", "需要 OP 才能修改角色卡"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        if (!WorldLotteryPaths.ready()) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.cards.storage_not_ready", "世界角色卡存储未就绪"));
            return;
        }
        try {
            UUID target = UUID.fromString(payload.targetUuid() == null ? "" : payload.targetUuid().trim());
            boolean limitBreak = "limit_break".equalsIgnoreCase(payload.questKey());
            if (limitBreak || "self_select".equalsIgnoreCase(payload.questKey())) {
                var op = PlayerCardAdminModels.CardOperation.parse(payload.operation());
                var result = limitBreak
                        ? PlayerCardAdminService.mutateLimitBreak(target, op, payload.value())
                        : PlayerCardAdminService.mutateSelfSelect(target, op, payload.value());
                if (result.ok()) {
                    LotteryNetwork.sendIfSupported(player, limitBreak
                            ? AdminActionResultS2C.translated(
                                    "message.habitrain_lottery.admin.cards.limit_break_updated", "破限卡已更新")
                            : AdminActionResultS2C.translated(
                                    "message.habitrain_lottery.admin.cards.self_select_updated", "自选卡已更新"));
                    pushCurrentPage(player);
                    CardUseService.handleRequest(player.server.getPlayerList().getPlayer(target), CardUseService.INVENTORY_KEY);
                } else {
                    LotteryNetwork.sendIfSupported(player, new AdminActionResultS2C(result.message(), false));
                }
                return;
            }
            FactionCardType type = PlayerCardMutationPolicy.parseType(payload.questKey());
            PlayerCardAdminModels.CardOperation operation =
                    PlayerCardAdminModels.CardOperation.parse(payload.operation());
            if (type == null) {
                LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                        "message.habitrain_lottery.admin.cards.unknown_type", "未知角色卡类型"));
                return;
            }
            PlayerCardMutationPolicy.validate(operation, payload.value());
            PlayerCardAdminModels.CardMutationResult result = PlayerCardAdminService.mutate(
                    player.server, target, type, operation, payload.value());
            if (!result.ok()) {
                LotteryNetwork.sendIfSupported(player,
                        new AdminActionResultS2C(result.message(), false));
                return;
            }
            String cardKey = "screen.habitrain_lottery.config.cards." + type.questKey;
            String cardName = Component.translatable(cardKey).getString();
            AdminActionResultS2C reply;
            if (operation == PlayerCardAdminModels.CardOperation.ADD) {
                int absDelta = Math.abs(payload.value());
                String fallback = "已将玩家 " + cardName + " 角色卡" + (payload.value() > 0 ? "增加 " : "减少 ")
                        + absDelta + " 张（现为 " + result.newCount() + "）";
                reply = payload.value() > 0
                        ? AdminActionResultS2C.translated(
                                "message.habitrain_lottery.admin.cards.added",
                                fallback,
                                "@" + cardKey, absDelta, result.newCount())
                        : AdminActionResultS2C.translated(
                                "message.habitrain_lottery.admin.cards.removed",
                                fallback,
                                "@" + cardKey, absDelta, result.newCount());
            } else {
                String fallback = "已将玩家 " + cardName + " 角色卡设为 " + result.newCount();
                reply = AdminActionResultS2C.translated(
                        "message.habitrain_lottery.admin.cards.set",
                        fallback,
                        "@" + cardKey, result.newCount());
            }
            LotteryNetwork.sendIfSupported(player, reply);
            pushCurrentPage(player);
            CardUseService.handleRequest(player.server.getPlayerList().getPlayer(target), CardUseService.INVENTORY_KEY);
        } catch (IllegalArgumentException e) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.cards.modify_error",
                    "角色卡修改失败: " + e.getMessage(),
                    e.getMessage()));
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Player card admin mutation failed: {}", e.toString());
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.cards.modify_error",
                    "角色卡修改失败: " + e.getMessage(),
                    e.getMessage()));
        }
    }

    static void handleGreenApplesModify(ServerPlayer player, PlayerGreenApplesModifyC2S payload) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.apples.need_op", "需要 OP 才能修改绿苹果"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        try {
            String mode = payload.mode() == null ? "" : payload.mode();
            int value = payload.value();
            if (("add_all_online".equals(mode) || "add_one".equals(mode))
                    && !HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.ADD, value)) {
                LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                        "message.habitrain_lottery.admin.apples.delta_zero", "绿苹果增量不能为 0"));
                return;
            }
            if (("set_all_online".equals(mode) || "set_one".equals(mode))
                    && !HabiAssetPolicy.isValidCurrencyOperation(HabiAssetOperation.SET, value)) {
                LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                        "message.habitrain_lottery.admin.apples.negative", "绿苹果余额不能为负数"));
                return;
            }
            String target = payload.targetUuid() == null ? "" : payload.targetUuid();
            String signed = (value >= 0 ? "+" : "") + value;
            AdminActionResultS2C reply;
            switch (mode) {
                case "add_all_online" -> {
                    int n = HabiLotteryApi.addGreenApplesToOnline(value);
                    reply = AdminActionResultS2C.translated(
                            "message.habitrain_lottery.admin.apples.add_all_online",
                            "已为在线 " + n + " 人调整绿苹果 " + signed,
                            n, signed);
                }
                case "set_all_online" -> {
                    int n = HabiLotteryApi.setGreenApplesToOnline(value);
                    int clamped = Math.max(0, value);
                    reply = AdminActionResultS2C.translated(
                            "message.habitrain_lottery.admin.apples.set_all_online",
                            "已将在线 " + n + " 人绿苹果设为 " + clamped,
                            n, clamped);
                }
                case "add_one" -> {
                    UUID id = UUID.fromString(target);
                    var result = HabiLotteryApi.addGreenApples(id, value);
                    if (!result.ok()) {
                        LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                                result.failure().translationKey(), result.message()));
                        return;
                    }
                    reply = AdminActionResultS2C.translated(
                            "message.habitrain_lottery.admin.apples.add_one",
                            "已调整玩家绿苹果 " + signed + "（现为 " + result.newValue() + "）",
                            signed, result.newValue());
                }
                case "set_one" -> {
                    UUID id = UUID.fromString(target);
                    var result = HabiLotteryApi.setGreenApples(id, value);
                    if (!result.ok()) {
                        LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                                result.failure().translationKey(), result.message()));
                        return;
                    }
                    reply = AdminActionResultS2C.translated(
                            "message.habitrain_lottery.admin.apples.set_one",
                            "已将玩家绿苹果设为 " + result.newValue(),
                            result.newValue());
                }
                default -> {
                    LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                            "message.habitrain_lottery.admin.apples.unknown_mode",
                            "未知修改模式: " + mode,
                            mode));
                    return;
                }
            }
            LotteryNetwork.sendIfSupported(player, reply);
            pushCurrentPage(player);
        } catch (Exception e) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.apples.modify_error",
                    "修改失败: " + e.getMessage(),
                    e.getMessage()));
        }
    }

    static void handleBackup(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.backup.need_op", "需要 OP 才能备份"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        LotteryBackupService.Result result = LotteryBackupService.createTimestampedBackup();
        if (result.ok() && result.snapshotDir() != null && result.snapshotDir().getFileName() != null) {
            String rel = "lottery_backup/" + result.snapshotDir().getFileName();
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.backup.created",
                    result.message(),
                    rel));
        } else {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.backup.failed",
                    result.message(),
                    result.message()));
        }
    }
}

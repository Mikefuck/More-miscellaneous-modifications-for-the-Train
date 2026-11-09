package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.PlayerTitleData;
import com.habitrain.lottery.title.TitleCatalog;
import com.habitrain.lottery.title.TitlePaths;
import com.habitrain.lottery.title.TitleService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Extracted server-side title catalog &amp; player title handlers ({@code N-01}, {@code B-01},
 * {@code B-08}, {@code B-10}, {@code I-10}, {@code N-02}).
 */
public final class LotteryTitleServerHandlers {
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 50;

    public static final String CORRUPT_CATALOG_MESSAGE =
            "称号目录存档损坏已被保护锁定，请先检查服务器 titles/catalog.json";
    public static final String REVISION_CONFLICT_MESSAGE =
            "称号模板已被其他管理员更新（版本冲突），请刷新后重试";
    public static final String CORRUPT_PLAYER_TITLE_MESSAGE =
            "该玩家称号存档损坏已被保护锁定，请先检查服务器 titles/players 存档";
    public static final String PLAYER_REVISION_CONFLICT_MESSAGE =
            "该玩家称号数据已变动（版本冲突），已自动同步最新状态，请重试";

    private static final Gson GSON = new Gson();
    private static final Map<UUID, LotteryAdminServerHandlers.PageQuery> LAST_TITLE_QUERIES =
            new ConcurrentHashMap<>();

    private LotteryTitleServerHandlers() {
    }

    public record SaveOutcome(
            boolean ok,
            String message,
            TitleCatalog savedCatalog,
            String translationKey,
            List<String> translationArgs
    ) {
        public SaveOutcome(boolean ok, String message, TitleCatalog savedCatalog) {
            this(ok, message, savedCatalog, "", List.of());
        }

        public static SaveOutcome fail(String message) {
            return new SaveOutcome(false, message, null, "", List.of());
        }

        public static SaveOutcome failKey(String key, String fallback, Object... args) {
            AdminActionResultS2C pkt = AdminActionResultS2C.translated(key, fallback, args);
            return new SaveOutcome(false, pkt.message(), null, pkt.translationKey(), pkt.translationArgs());
        }

        public static SaveOutcome successKey(String key, String fallback, TitleCatalog savedCatalog) {
            return new SaveOutcome(true, fallback, savedCatalog, key, List.of());
        }

        public AdminActionResultS2C toAdminResult() {
            return new AdminActionResultS2C(message, false, translationKey, translationArgs);
        }
    }

    public record PlayerModifyOutcome(
            boolean ok,
            boolean conflict,
            String message,
            long currentRevision,
            String translationKey,
            List<String> translationArgs
    ) {
        public static PlayerModifyOutcome fail(
                boolean conflict,
                long currentRevision,
                String key,
                String fallback,
                Object... args
        ) {
            AdminActionResultS2C pkt = AdminActionResultS2C.translated(key, fallback, args);
            return new PlayerModifyOutcome(
                    false, conflict, pkt.message(), currentRevision, pkt.translationKey(), pkt.translationArgs());
        }

        public static PlayerModifyOutcome success(
                long currentRevision,
                String key,
                String fallback,
                Object... args
        ) {
            AdminActionResultS2C pkt = AdminActionResultS2C.translated(key, fallback, args);
            return new PlayerModifyOutcome(
                    true, false, pkt.message(), currentRevision, pkt.translationKey(), pkt.translationArgs());
        }

        public AdminActionResultS2C toAdminResult() {
            return new AdminActionResultS2C(message, false, translationKey, translationArgs);
        }
    }

    record BroadcastRecipient<P>(P player, boolean authorizedOp) {
    }

    static void forget(UUID playerId) {
        if (playerId != null) {
            LAST_TITLE_QUERIES.remove(playerId);
        }
    }

    static void clearAll() {
        LAST_TITLE_QUERIES.clear();
    }

    static void sendCatalogAuthDenied(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.title.need_op_save", "需要 OP 才能保存称号模板"));
        } else {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
        }
    }

    static void handleTitleCatalogSave(ServerPlayer player, String json, long expectedRevision) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player) || !AdminAuth.isAuthorizedOp(player)) {
            sendCatalogAuthDenied(player);
            return;
        }
        SaveOutcome outcome = saveCatalogOptimistically(json, expectedRevision);
        LotteryNetwork.sendIfSupported(player, outcome.toAdminResult());
        if (!outcome.ok() || outcome.savedCatalog() == null) {
            if (REVISION_CONFLICT_MESSAGE.equals(outcome.message())) {
                sendTitleSnapshot(player);
            }
            return;
        }

        MinecraftServer server = player.server;
        List<BroadcastRecipient<ServerPlayer>> recipients = new ArrayList<>();
        if (server != null && server.getPlayerList() != null) {
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                if (online != null && !online.hasDisconnected()) {
                    recipients.add(new BroadcastRecipient<>(online, AdminAuth.isAuthorizedOp(online)));
                }
            }
        } else {
            recipients.add(new BroadcastRecipient<>(player, true));
        }

        TitlePlayerPageS2C[] cachedDefaultPage = {null};
        broadcastSavedCatalog(
                outcome.savedCatalog(),
                recipients,
                () -> {
                    cachedDefaultPage[0] = buildTitlePlayersPage(
                            server, LotteryAdminServerHandlers.PageQuery.DEFAULT);
                    return "{}";
                },
                LotteryNetwork::sendIfSupported);
        for (BroadcastRecipient<ServerPlayer> r : recipients) {
            if (r == null || r.player() == null || !r.authorizedOp()) {
                continue;
            }
            LotteryAdminServerHandlers.PageQuery q = LAST_TITLE_QUERIES.getOrDefault(
                    r.player().getUUID(), LotteryAdminServerHandlers.PageQuery.DEFAULT);
            TitlePlayerPageS2C pageToSend =
                    LotteryAdminServerHandlers.PageQuery.DEFAULT.equals(q) && cachedDefaultPage[0] != null
                            ? cachedDefaultPage[0]
                            : buildTitlePlayersPage(server, q);
            LAST_TITLE_QUERIES.put(r.player().getUUID(),
                    new LotteryAdminServerHandlers.PageQuery(q.query(), pageToSend.page(), q.pageSize()));
            LotteryNetwork.sendIfSupported(r.player(), pageToSend);
        }
    }

    /**
     * Public &amp; unit-testable optimistic-locked catalog save with {@code isCatalogCorrupt()}
     * protection ({@code B-01} + {@code I-10}).
     */
    public static SaveOutcome saveCatalogOptimistically(String json, long expectedRevision) {
        if (!WorldLotteryPaths.ready()) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.storage_not_ready", "世界称号存储未就绪");
        }
        LocalTitleStore store = LocalTitleStore.get();
        if (store.isCatalogCorrupt()) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_corrupt", CORRUPT_CATALOG_MESSAGE);
        }
        TitleCatalog current = store.loadCatalog();
        if (store.isCatalogCorrupt()) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_corrupt", CORRUPT_CATALOG_MESSAGE);
        }
        try {
            TitleCatalog incoming = GSON.fromJson(json == null ? "" : json, TitleCatalog.class);
            if (incoming == null) {
                incoming = new TitleCatalog();
            }
            String validationError = incoming.validateAndNormalize();
            if (validationError != null) {
                return mapCatalogValidationOutcome(validationError);
            }
            long currentRevision = current == null ? 0L : Math.max(0L, current.revision);
            if (expectedRevision != currentRevision) {
                return SaveOutcome.failKey(
                        "message.habitrain_lottery.admin.title.catalog_conflict", REVISION_CONFLICT_MESSAGE);
            }
            incoming.revision = currentRevision + 1L;
            TitlePaths.ensureDirs();
            if (!store.saveCatalogChecked(incoming)) {
                return SaveOutcome.failKey(
                        "message.habitrain_lottery.admin.title.catalog_save_failed", "称号模板保存失败，原模板已保留");
            }
            return SaveOutcome.successKey(
                    "message.habitrain_lottery.admin.title.catalog_saved", "称号模板库已保存", incoming);
        } catch (JsonSyntaxException syntax) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_invalid_json", "称号模板 JSON 格式无效");
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Title catalog save failed: {}", e.toString());
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_save_error",
                    "称号模板保存失败: " + e.getMessage(),
                    e.getMessage());
        }
    }

    private static SaveOutcome mapCatalogValidationOutcome(String validationError) {
        if ("称号模板 id 不能为空".equals(validationError)) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_empty_id", validationError);
        }
        if (validationError.startsWith("称号模板数量超出上限")) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_too_many",
                    validationError,
                    TitleCatalog.MAX_TITLES);
        }
        if (validationError.startsWith("称号模板 id 过长")) {
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_id_too_long",
                    validationError,
                    TitleCatalog.MAX_ID_LENGTH);
        }
        if (validationError.startsWith("称号模板 id 重复: ")) {
            String dupId = validationError.substring("称号模板 id 重复: ".length());
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_duplicate_id",
                    validationError,
                    dupId);
        }
        if (validationError.startsWith("称号展示文本过长")) {
            int sep = validationError.indexOf(": ");
            String badId = sep >= 0 ? validationError.substring(sep + 2) : "";
            return SaveOutcome.failKey(
                    "message.habitrain_lottery.admin.title.catalog_display_too_long",
                    validationError,
                    TitleCatalog.MAX_DISPLAY_LENGTH,
                    badId);
        }
        return SaveOutcome.fail(validationError);
    }

    static <P> void broadcastSavedCatalog(
            TitleCatalog savedCatalog,
            List<BroadcastRecipient<P>> recipients,
            Supplier<String> adminPlayersJsonSupplier,
            BiConsumer<P, TitleSnapshotS2C> sender
    ) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        String catalogJson = GSON.toJson(savedCatalog == null ? new TitleCatalog() : savedCatalog);
        boolean anyAuthorizedOp = false;
        for (BroadcastRecipient<P> r : recipients) {
            if (r != null && r.authorizedOp()) {
                anyAuthorizedOp = true;
                break;
            }
        }
        String adminPlayersJson = anyAuthorizedOp ? adminPlayersJsonSupplier.get() : "{}";
        if (adminPlayersJson == null) {
            adminPlayersJson = "{}";
        }
        TitleSnapshotS2C publicPacket = new TitleSnapshotS2C(catalogJson, "{}");
        TitleSnapshotS2C adminPacket = new TitleSnapshotS2C(catalogJson, adminPlayersJson);
        for (BroadcastRecipient<P> r : recipients) {
            if (r == null || r.player() == null) {
                continue;
            }
            sender.accept(r.player(), r.authorizedOp() ? adminPacket : publicPacket);
        }
    }

    static void handleTitleSnapshotRequest(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.title.need_op_view", "需要 OP 才能查看称号"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        sendTitleSnapshot(player);
    }

    static void handleTitlePlayerPageRequest(
            ServerPlayer player,
            TitlePlayerPageRequestC2S request
    ) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.title.need_op_view", "需要 OP 才能查看称号"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        LotteryAdminServerHandlers.PageQuery query = request == null
                ? LAST_TITLE_QUERIES.getOrDefault(
                        player.getUUID(), LotteryAdminServerHandlers.PageQuery.DEFAULT)
                : new LotteryAdminServerHandlers.PageQuery(
                        LotteryAdminServerHandlers.sanitizeQuery(request.query()),
                        Math.max(0, request.page()),
                        LotteryAdminServerHandlers.clampPageSize(request.pageSize()));
        TitlePlayerPageS2C page = buildTitlePlayersPage(player.server, query);
        LAST_TITLE_QUERIES.put(player.getUUID(),
                new LotteryAdminServerHandlers.PageQuery(query.query(), page.page(), query.pageSize()));
        LotteryNetwork.sendIfSupported(player, page);
    }

    static void sendTitleSnapshot(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        try {
            if (WorldLotteryPaths.ready()) {
                TitlePaths.ensureDirs();
            }
            TitleCatalog catalog = LocalTitleStore.get().loadCatalog();
            String catalogJson = GSON.toJson(catalog);

            if (!AdminAuth.isAuthorizedOp(player)) {
                LotteryNetwork.sendIfSupported(player, new TitleSnapshotS2C(catalogJson, "{}"));
                return;
            }
            LotteryAdminServerHandlers.PageQuery query = LAST_TITLE_QUERIES.getOrDefault(
                    player.getUUID(), LotteryAdminServerHandlers.PageQuery.DEFAULT);
            TitlePlayerPageS2C page = buildTitlePlayersPage(player.server, query);
            LAST_TITLE_QUERIES.put(player.getUUID(),
                    new LotteryAdminServerHandlers.PageQuery(query.query(), page.page(), query.pageSize()));
            LotteryNetwork.sendIfSupported(player, new TitleSnapshotS2C(catalogJson, "{}"));
            LotteryNetwork.sendIfSupported(player, page);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Title snapshot failed for {}: {}",
                    player.getGameProfile().getName(), e.toString());
            if (AdminAuth.isAuthorizedOp(player)) {
                LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                        "message.habitrain_lottery.admin.title.snapshot_failed",
                        "称号快照失败: " + e.getMessage(),
                        e.getMessage()));
            }
        }
    }

    private static TitlePlayerPageS2C buildTitlePlayersPage(
            MinecraftServer server,
            LotteryAdminServerHandlers.PageQuery query
    ) {
        List<LotteryAdminServerHandlers.PlayerStub> stubs = collectTitlePlayerStubs(server);
        return buildPaginatedTitlePlayers(
                stubs,
                query.query(),
                query.page(),
                query.pageSize(),
                id -> LocalTitleStore.get().loadPlayer(id));
    }

    private static List<LotteryAdminServerHandlers.PlayerStub> collectTitlePlayerStubs(MinecraftServer server) {
        Map<UUID, LotteryAdminServerHandlers.PlayerStub> byId = new LinkedHashMap<>();
        for (LotteryAdminServerHandlers.PlayerStub stub : LotteryAdminServerHandlers.collectPlayerStubs(server)) {
            byId.put(stub.uuid(), stub);
        }
        for (UUID id : LocalTitleStore.get().listKnownPlayerIds()) {
            if (id != null && !byId.containsKey(id)) {
                String name = ProfileCacheLookup.lookupCachedName(server, id)
                        .orElseGet(() -> LotteryAdminServerHandlers.shortUuid(id));
                byId.put(id, new LotteryAdminServerHandlers.PlayerStub(id, name, false));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Pure, unit-testable pagination engine for player title inventories: loads at most {@code <= 50}
     * {@link PlayerTitleData} entries on the matched page slice, including per-player {@code revision}
     * for {@code I-10} optimistic locking.
     */
    static TitlePlayerPageS2C buildPaginatedTitlePlayers(
            List<LotteryAdminServerHandlers.PlayerStub> candidates,
            String rawQuery,
            int requestedPage,
            int requestedPageSize,
            Function<UUID, PlayerTitleData> titleLoader
    ) {
        int pageSize = LotteryAdminServerHandlers.clampPageSize(requestedPageSize);
        String needle = LotteryAdminServerHandlers.sanitizeQuery(rawQuery).toLowerCase(Locale.ROOT);

        List<LotteryAdminServerHandlers.PlayerStub> matched = new ArrayList<>();
        if (candidates != null) {
            for (LotteryAdminServerHandlers.PlayerStub stub : candidates) {
                if (stub != null && stub.uuid() != null
                        && LotteryAdminServerHandlers.matchesQuery(stub, needle)) {
                    matched.add(stub);
                }
            }
        }
        matched.sort(LotteryAdminServerHandlers.STUB_COMPARATOR);

        int totalMatched = matched.size();
        int totalPages = Math.max(1, (totalMatched + pageSize - 1) / pageSize);
        int page = Math.min(Math.max(0, requestedPage), totalPages - 1);
        int from = page * pageSize;
        int to = Math.min(totalMatched, from + pageSize);

        Map<String, Object> players = new LinkedHashMap<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            LotteryAdminServerHandlers.PlayerStub stub = matched.get(i);
            PlayerTitleData data = titleLoader.apply(stub.uuid());
            Map<String, Object> entry = new LinkedHashMap<>();
            String displayName = stub.name() == null ? "" : stub.name();
            if (displayName.length() > 64) {
                displayName = displayName.substring(0, 64);
            }
            entry.put("name", displayName);
            entry.put("online", stub.online());
            entry.put("owned", data != null && data.owned != null ? data.owned : List.of());
            entry.put("current", data != null && data.current != null ? data.current : "");
            entry.put("revision", data != null ? Math.max(0L, data.revision) : 0L);
            players.put(stub.uuid().toString(), entry);
        }

        return new TitlePlayerPageS2C(
                GSON.toJson(players), page, totalPages, totalMatched);
    }

    /**
     * Public &amp; unit-testable optimistic-locked player title modification ({@code I-10}).
     */
    public static PlayerModifyOutcome modifyPlayerTitleOptimistically(
            UUID targetId,
            ServerPlayer onlineTarget,
            String rawMode,
            String rawDisplay,
            long expectedRevision
    ) {
        if (!WorldLotteryPaths.ready()) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.title.storage_not_ready", "世界称号存储未就绪");
        }
        if (targetId == null) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.invalid_uuid", "无效玩家 UUID");
        }
        String mode = rawMode == null ? "" : rawMode.trim();
        String display = rawDisplay == null ? "" : rawDisplay;
        if (display.length() > TitleCatalog.MAX_DISPLAY_LENGTH) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.title.display_too_long",
                    "称号长度超出上限（最多 " + TitleCatalog.MAX_DISPLAY_LENGTH + " 字符）",
                    TitleCatalog.MAX_DISPLAY_LENGTH);
        }
        if ("grant".equals(mode) && display.isBlank()) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.title.grant_blank", "授予称号不能为空");
        }
        if ("revoke".equals(mode) && display.isBlank()) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.title.revoke_blank", "收回称号不能为空");
        }
        if (!"grant".equals(mode) && !"revoke".equals(mode)
                && !"set_current".equals(mode) && !"clear".equals(mode)) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.title.unknown_mode",
                    "未知称号操作: " + mode,
                    mode);
        }

        LocalTitleStore store = LocalTitleStore.get();
        LocalTitleStore.PlayerLoad currentLoad = store.loadPlayerDetailed(targetId);
        if (currentLoad.corrupt()) {
            return PlayerModifyOutcome.fail(
                    false, 0L,
                    "message.habitrain_lottery.admin.title.player_corrupt",
                    CORRUPT_PLAYER_TITLE_MESSAGE);
        }
        long currentRevision = currentLoad.data() == null ? 0L : Math.max(0L, currentLoad.data().revision);
        if (expectedRevision != currentRevision) {
            return PlayerModifyOutcome.fail(
                    true,
                    currentRevision,
                    "message.habitrain_lottery.admin.title.player_conflict",
                    PLAYER_REVISION_CONFLICT_MESSAGE);
        }

        boolean ok;
        switch (mode) {
            case "grant" -> {
                ok = onlineTarget != null
                        ? TitleService.grantOnline(onlineTarget, display)
                        : TitleService.grantOffline(targetId, display);
                long nextRev = store.loadPlayer(targetId).revision;
                return ok
                        ? PlayerModifyOutcome.success(nextRev,
                                "message.habitrain_lottery.admin.title.granted", "已授予称号")
                        : PlayerModifyOutcome.fail(false, nextRev,
                                "message.habitrain_lottery.admin.title.grant_failed", "授予失败（可能已拥有）");
            }
            case "revoke" -> {
                ok = onlineTarget != null
                        ? TitleService.revokeOnline(onlineTarget, display)
                        : TitleService.revokeOffline(targetId, display);
                long nextRev = store.loadPlayer(targetId).revision;
                return ok
                        ? PlayerModifyOutcome.success(nextRev,
                                "message.habitrain_lottery.admin.title.revoked", "已收回称号")
                        : PlayerModifyOutcome.fail(false, nextRev,
                                "message.habitrain_lottery.admin.title.revoke_failed", "收回失败（未拥有）");
            }
            case "set_current" -> {
                ok = onlineTarget != null
                        ? TitleService.setCurrentOnline(onlineTarget, display)
                        : TitleService.setCurrentOffline(targetId, display);
                long nextRev = store.loadPlayer(targetId).revision;
                if (!ok) {
                    return PlayerModifyOutcome.fail(false, nextRev,
                            "message.habitrain_lottery.admin.title.equip_failed", "设佩戴失败（须已拥有）");
                }
                return display.isBlank()
                        ? PlayerModifyOutcome.success(nextRev,
                                "message.habitrain_lottery.admin.title.unequipped", "已清空佩戴")
                        : PlayerModifyOutcome.success(nextRev,
                                "message.habitrain_lottery.admin.title.equipped", "已设置佩戴称号");
            }
            default -> {
                ok = onlineTarget != null
                        ? TitleService.clearOnlineChecked(onlineTarget)
                        : store.clearChecked(targetId);
                long nextRev = store.loadPlayer(targetId).revision;
                return ok
                        ? PlayerModifyOutcome.success(nextRev,
                                "message.habitrain_lottery.admin.title.cleared", "已清空玩家称号")
                        : PlayerModifyOutcome.fail(false, nextRev,
                                "message.habitrain_lottery.admin.title.clear_failed", "称号保存失败，未清空");
            }
        }
    }

    static void handleTitlePlayerModify(ServerPlayer player, TitlePlayerModifyC2S payload) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.title.need_op_modify", "需要 OP 才能修改称号"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.unauthorized", "当前为未授权的访问"));
            return;
        }
        try {
            String target = payload.targetUuid() == null ? "" : payload.targetUuid().trim();
            UUID id = UUID.fromString(target);
            ServerPlayer online = player.server == null || player.server.getPlayerList() == null
                    ? null
                    : player.server.getPlayerList().getPlayer(id);
            PlayerModifyOutcome outcome = modifyPlayerTitleOptimistically(
                    id, online, payload.mode(), payload.payload(), payload.expectedRevision());
            LotteryNetwork.sendIfSupported(player, outcome.toAdminResult());
            if (outcome.ok() || outcome.conflict()) {
                sendTitleSnapshot(player);
            }
        } catch (IllegalArgumentException e) {
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.invalid_uuid", "无效玩家 UUID"));
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Title player modify failed: {}", e.toString());
            LotteryNetwork.sendIfSupported(player, AdminActionResultS2C.translated(
                    "message.habitrain_lottery.admin.title.modify_error",
                    "称号修改失败: " + e.getMessage(),
                    e.getMessage()));
        }
    }
}

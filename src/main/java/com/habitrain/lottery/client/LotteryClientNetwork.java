package com.habitrain.lottery.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.client.gui.CardGuiGameState;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.client.gui.MailComposeScreen;
import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
import com.habitrain.lottery.network.AdminActionResultS2C;
import com.habitrain.lottery.network.BackupDataS2C;
import com.habitrain.lottery.network.BackupRequestC2S;
import com.habitrain.lottery.network.CardUseConfirmC2S;
import com.habitrain.lottery.network.CardUseMenuS2C;
import com.habitrain.lottery.network.ChunkedJsonTransfer;
import com.habitrain.lottery.network.ClientLoginState;
import com.habitrain.lottery.network.ClientLotteryState;
import com.habitrain.lottery.network.DailyTaskBoardS2C;
import com.habitrain.lottery.network.DailyTaskClaimC2S;
import com.habitrain.lottery.network.DailyTaskRequestC2S;
import com.habitrain.lottery.network.LoginStateS2C;
import com.habitrain.lottery.network.MailboxClaimC2S;
import com.habitrain.lottery.network.MailboxListS2C;
import com.habitrain.lottery.network.MailboxPages;
import com.habitrain.lottery.network.MailboxRequestC2S;
import com.habitrain.lottery.network.OpStatusS2C;
import com.habitrain.lottery.network.OpenMailComposeS2C;
import com.habitrain.lottery.network.OpenMailboxS2C;
import com.habitrain.lottery.network.PlayerAdminModels;
import com.habitrain.lottery.network.PlayerCardModifyC2S;
import com.habitrain.lottery.network.PlayerGreenApplesModifyC2S;
import com.habitrain.lottery.network.PlayerListRequestC2S;
import com.habitrain.lottery.network.PlayerListS2C;
import com.habitrain.lottery.network.TitleCatalogSaveC2S;
import com.habitrain.lottery.network.TitlePageTransfer;
import com.habitrain.lottery.network.TitlePlayerModifyC2S;
import com.habitrain.lottery.network.TitlePlayerPageRequestC2S;
import com.habitrain.lottery.network.TitlePlayerPageS2C;
import com.habitrain.lottery.network.TitleSnapshotRequestC2S;
import com.habitrain.lottery.network.TitleSnapshotS2C;
import com.habitrain.lottery.network.WarehouseNetwork;
import com.habitrain.lottery.title.TitleCatalog;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-only networking ({@code N-01}, {@code B-10}, {@code I-10}, {@code I-22}, {@code N-02}).
 */
@Environment(EnvType.CLIENT)
public final class LotteryClientNetwork {
    private static final Gson GSON = new Gson();
    private static final int MAX_QUERY_CHARS = 64;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private record PlayerListMeta(int page, int totalPages, int totalMatched) {
    }

    /** Immutable pre-parsed player title summary cached on packet arrival ({@code I-22}, {@code I-10}). */
    public record TitlePlayerSummary(
            String uuid,
            String name,
            boolean online,
            List<String> owned,
            String current,
            long revision
    ) {
        public TitlePlayerSummary {
            uuid = uuid == null ? "" : uuid;
            name = name == null ? "" : name;
            owned = owned == null || owned.isEmpty() ? List.of() : List.copyOf(owned);
            current = current == null ? "" : current;
            revision = Math.max(0L, revision);
        }

        public TitlePlayerSummary(
                String uuid,
                String name,
                boolean online,
                List<String> owned,
                String current
        ) {
            this(uuid, name, online, owned, current, 0L);
        }
    }

    private record ParsedTitlePlayersView(
            String rawJson,
            Map<String, TitlePlayerSummary> byUuid,
            Map<String, String> currentByUuid,
            Map<String, List<String>> ownedByUuid
    ) {
        static final ParsedTitlePlayersView EMPTY =
                new ParsedTitlePlayersView("", Map.of(), Map.of(), Map.of());
    }

    private static volatile ParsedTitlePlayersView parsedTitlePlayers = ParsedTitlePlayersView.EMPTY;
    private static final TitlePageTransfer.Assembler TITLE_PAGE_TRANSFER =
            new TitlePageTransfer.Assembler();
    private static final TitleSnapshotS2C.Assembler TITLE_SNAPSHOT_TRANSFER =
            new TitleSnapshotS2C.Assembler();
    private static final ChunkedJsonTransfer.Assembler<PlayerListMeta> PLAYER_LIST_TRANSFER =
            ChunkedJsonTransfer.newClientS2CAssembler();
    private static volatile String cachedCatalogRaw = "";
    private static volatile long cachedCatalogRevision = 0L;

    private LotteryClientNetwork() {
    }

    /** Idempotent client receiver registration. */
    public static void ensureRegistered() {
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                registerCommonReceivers();
                HabiLotteryMod.LOGGER.debug("habitrain_lottery client receivers registered");
            } catch (Throwable t) {
                REGISTERED.set(false);
                HabiLotteryMod.LOGGER.warn("Client receiver registration failed, will retry: {}", t.toString());
            }
        }
    }

    /** Resets all client-side caches on world/server disconnect and join ({@code B-23.1}). */
    public static void resetAllClientCaches() {
        TITLE_PAGE_TRANSFER.reset();
        TITLE_SNAPSHOT_TRANSFER.reset();
        PLAYER_LIST_TRANSFER.reset();
        CrateClientNetwork.resetAssemblers();
        DailyTaskAdminClient.resetAssembler();
        DailyShopClient.resetAssembler();
        ClientLotteryState.resetAll();
        parsedTitlePlayers = ParsedTitlePlayersView.EMPTY;
        cachedCatalogRaw = "";
        cachedCatalogRevision = 0L;
        CardGuiGameState.invalidate();
        SelfSelectAdminClient.reset();
    }

    private static void runOnClientThread(Minecraft client, Runnable action) {
        if (client == null || client.isSameThread()) {
            action.run();
        } else {
            client.execute(action);
        }
    }

    private static void registerCommonReceivers() {
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> runOnClientThread(client, LotteryClientNetwork::resetAllClientCaches));
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> runOnClientThread(client, LotteryClientNetwork::resetAllClientCaches));

        ClientPlayNetworking.registerGlobalReceiver(MailboxPages.Snapshot.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    ClientLotteryState.mailboxJson = payload.json();
                    ClientLotteryState.mailboxOffset = payload.offset();
                    ClientLotteryState.mailboxNext = payload.next();
                    ClientLotteryState.mailboxTotal = payload.total();
                    ClientLotteryState.mailboxUnclaimed = payload.unclaimed();
                    ClientLotteryState.mailboxVersion++;
                }));
        ClientPlayNetworking.registerGlobalReceiver(DailyTaskBoardS2C.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        DailyTaskSnapshot board = GSON.fromJson(payload.json(), DailyTaskSnapshot.class);
                        if (board == null) return;
                        ClientLotteryState.greenApples = board.greenApples();
                        ClientLoginState.resetZoneId = board.effectiveResetZoneId();
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
        ClientPlayNetworking.registerGlobalReceiver(OpStatusS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                ClientLotteryState.op = payload.op();
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(PlayerListS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                var complete = PLAYER_LIST_TRANSFER.accept(
                        payload.json(),
                        payload.transferId(),
                        payload.chunkIndex(),
                        payload.chunkCount(),
                        new PlayerListMeta(
                                Math.max(0, payload.page()),
                                Math.max(1, payload.totalPages()),
                                Math.max(0, payload.totalMatched())));
                if (complete == null) return;
                try {
                    PlayerAdminModels.PlayerListSnapshot snap =
                            GSON.fromJson(complete.json(), PlayerAdminModels.PlayerListSnapshot.class);
                    int page = complete.metadata().page();
                    int totalPages = complete.metadata().totalPages();
                    int totalMatched = complete.metadata().totalMatched();
                    if (snap != null && snap.players != null) {
                        snap.page = page;
                        snap.totalPages = totalPages;
                        snap.totalMatched = totalMatched;
                        ClientLotteryState.playerList = snap;
                    }
                    ClientLotteryState.playerListPage = page;
                    ClientLotteryState.playerListTotalPages = totalPages;
                    ClientLotteryState.playerListTotalMatched = totalMatched;
                    ClientLotteryState.playerListVersion++;
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Failed parsing player list: {}", t.toString());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(BackupDataS2C.TYPE, (payload, context) -> {
            HabiLotteryMod.LOGGER.debug("Ignoring legacy BackupDataS2C ({} chars)",
                    payload.json() == null ? 0 : payload.json().length());
        });
        ClientPlayNetworking.registerGlobalReceiver(AdminActionResultS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                ClientLotteryState.lastAdminMessage = payload.toComponent().getString();
                if (payload.refreshPlayers() && ClientLotteryState.op) {
                    clientRequestPlayerList(
                            ClientLotteryState.playerListQuery,
                            ClientLotteryState.playerListPage,
                            PlayerListRequestC2S.DEFAULT_PAGE_SIZE);
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(LoginStateS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                ClientLoginState.epochDay = payload.epochDay();
                ClientLoginState.year = payload.year();
                ClientLoginState.month = payload.month();
                ClientLoginState.dayOfMonth = payload.dayOfMonth();
                ClientLoginState.streak = payload.streak();
                ClientLoginState.loginDaysMask = payload.loginDaysMask();
                ClientLoginState.resetZoneId =
                        payload.resetZoneId() == null || payload.resetZoneId().isBlank() ? "UTC" : payload.resetZoneId();
                ClientLoginState.hasData = true;
                ClientLoginState.version++;
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
                if (mc.screen instanceof DailyTaskScreen screen) {
                    screen.openMailTab();
                } else {
                    mc.setScreen(DailyTaskScreen.mailbox(mc.screen));
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(MailboxListS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                ClientLotteryState.mailboxJson =
                        payload.json() == null ? "" : payload.json();
                ClientLotteryState.mailboxVersion++;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(TitleSnapshotS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                TitleSnapshotS2C complete = TITLE_SNAPSHOT_TRANSFER.accept(payload);
                if (complete != null) {
                    applyTitleSnapshot(complete);
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(TitlePlayerPageS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                var complete = TITLE_PAGE_TRANSFER.accept(payload);
                if (complete == null) return;
                String playersJson = complete.playersJson();
                ClientLotteryState.titlePlayersJson = playersJson;
                ClientLotteryState.titlePlayersPage = Math.max(0, complete.page());
                ClientLotteryState.titlePlayersTotalPages = Math.max(1, complete.totalPages());
                ClientLotteryState.titlePlayersTotalMatched = Math.max(0, complete.totalMatched());
                parsedTitlePlayers = parseTitlePlayersView(playersJson);
                ClientLotteryState.titleVersion++;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(WarehouseNetwork.Snapshot.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().screen instanceof WarehouseScreen screen)
                        screen.receive(payload);
                }));
        ClientPlayNetworking.registerGlobalReceiver(CardUseMenuS2C.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                ClientLotteryState.cardUseRemainingUses = payload.remainingUses();
                ClientLotteryState.cardUseRemainingSelfUses = payload.remainingSelfUses();
                Map<String, Integer> balances = GSON.fromJson(payload.balancesJson(),
                        new TypeToken<Map<String, Integer>>() { }.getType());
                ClientLotteryState.cardBalances = balances == null ? Map.of() : balances;
                ClientLotteryState.cardInventoryVersion++;
                if ("inventory".equals(payload.questKey())) return;
                ClientLotteryState.cardUseMenuQuestKey =
                        payload.questKey() == null ? "" : payload.questKey();
                ClientLotteryState.cardUseRemainingUses = payload.remainingUses();
                ClientLotteryState.cardUseCandidatesJson =
                        payload.candidatesJson() == null ? "" : payload.candidatesJson();
                ClientLotteryState.cardUseMenuVersion++;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.screen instanceof WarehouseScreen screen)
                    screen.receiveCardMenu(payload);
            });
        });
    }

    public static void applyTitleSnapshot(TitleSnapshotS2C payload) {
        if (payload == null) {
            return;
        }
        String catalogJson = payload.catalogJson() == null ? "" : payload.catalogJson();
        String playersJson = payload.playersJson() == null ? "" : payload.playersJson();
        ClientLotteryState.titleCatalogJson = catalogJson;
        updateCachedCatalogRevision(catalogJson);
        String activeQuery = ClientLotteryState.titlePlayersQuery;
        boolean hasActivePaginationOrFilter = ClientLotteryState.titlePlayersPage > 0
                || (activeQuery != null && !activeQuery.isEmpty());
        String existingPlayers = ClientLotteryState.titlePlayersJson;
        if (!hasActivePaginationOrFilter || existingPlayers == null || existingPlayers.isEmpty()
                || !ClientLotteryState.op) {
            ClientLotteryState.titlePlayersJson = playersJson;
            parsedTitlePlayers = parseTitlePlayersView(playersJson);
        }
        ClientLotteryState.titleVersion++;
    }

    private static ParsedTitlePlayersView ensureTitlePlayersParsed() {
        String raw = ClientLotteryState.titlePlayersJson;
        String safe = raw == null ? "" : raw;
        ParsedTitlePlayersView current = parsedTitlePlayers;
        if (Objects.equals(current.rawJson(), safe)) {
            return current;
        }
        ParsedTitlePlayersView rebuilt = parseTitlePlayersView(safe);
        parsedTitlePlayers = rebuilt;
        return rebuilt;
    }

    private static ParsedTitlePlayersView parseTitlePlayersView(String json) {
        String safe = json == null ? "" : json;
        if (safe.isBlank()) {
            return new ParsedTitlePlayersView(safe, Map.of(), Map.of(), Map.of());
        }
        try {
            JsonElement parsed = JsonParser.parseString(safe);
            if (parsed == null || !parsed.isJsonObject()) {
                return new ParsedTitlePlayersView(safe, Map.of(), Map.of(), Map.of());
            }
            JsonObject root = parsed.getAsJsonObject();
            Map<String, TitlePlayerSummary> byUuid = new LinkedHashMap<>(root.size());
            Map<String, String> currentByUuid = new LinkedHashMap<>(root.size());
            Map<String, List<String>> ownedByUuid = new LinkedHashMap<>(root.size());
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String uuid = entry.getKey();
                JsonElement value = entry.getValue();
                if (uuid == null || uuid.isBlank() || value == null || !value.isJsonObject()) {
                    continue;
                }
                JsonObject obj = value.getAsJsonObject();
                String name = getString(obj, "name");
                boolean online = obj.has("online") && obj.get("online").isJsonPrimitive()
                        && obj.get("online").getAsBoolean();
                String current = getString(obj, "current");
                long revision = obj.has("revision") && obj.get("revision").isJsonPrimitive()
                        ? Math.max(0L, obj.get("revision").getAsLong())
                        : 0L;
                List<String> owned = List.of();
                if (obj.has("owned") && obj.get("owned").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("owned");
                    List<String> tmp = new ArrayList<>(arr.size());
                    for (JsonElement el : arr) {
                        if (el != null && el.isJsonPrimitive()) {
                            String item = el.getAsString();
                            if (item != null && !item.isEmpty()) {
                                tmp.add(item);
                            }
                        }
                    }
                    owned = tmp.isEmpty() ? List.of() : List.copyOf(tmp);
                }
                byUuid.put(uuid, new TitlePlayerSummary(uuid, name, online, owned, current, revision));
                currentByUuid.put(uuid, current);
                ownedByUuid.put(uuid, owned);
            }
            return new ParsedTitlePlayersView(
                    safe,
                    Collections.unmodifiableMap(byUuid),
                    Collections.unmodifiableMap(currentByUuid),
                    Collections.unmodifiableMap(ownedByUuid));
        } catch (Exception e) {
            return new ParsedTitlePlayersView(safe, Map.of(), Map.of(), Map.of());
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        String s = obj.get(key).getAsString();
        return s == null ? "" : s;
    }

    private static void updateCachedCatalogRevision(String catalogJson) {
        String safe = catalogJson == null ? "" : catalogJson;
        long rev = 0L;
        if (!safe.isBlank()) {
            try {
                TitleCatalog catalog = GSON.fromJson(safe, TitleCatalog.class);
                if (catalog != null) {
                    rev = Math.max(0L, catalog.revision);
                }
            } catch (Exception ignored) {
            }
        }
        cachedCatalogRaw = safe;
        cachedCatalogRevision = rev;
    }

    public static long currentTitleCatalogRevision() {
        String raw = ClientLotteryState.titleCatalogJson;
        String safe = raw == null ? "" : raw;
        if (!Objects.equals(cachedCatalogRaw, safe)) {
            updateCachedCatalogRevision(safe);
        }
        return cachedCatalogRevision;
    }

    public static Map<String, TitlePlayerSummary> cachedTitlePlayers() {
        return ensureTitlePlayersParsed().byUuid();
    }

    public static Map<String, String> cachedTitlePlayerCurrent() {
        return ensureTitlePlayersParsed().currentByUuid();
    }

    public static Map<String, List<String>> cachedTitlePlayerOwned() {
        return ensureTitlePlayersParsed().ownedByUuid();
    }

    public static OptionalLong cachedTitlePlayerRevision(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return OptionalLong.empty();
        }
        TitlePlayerSummary summary = ensureTitlePlayersParsed().byUuid().get(uuid);
        return summary == null ? OptionalLong.empty() : OptionalLong.of(summary.revision());
    }

    private static String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String trimmed = query.trim();
        return trimmed.length() <= MAX_QUERY_CHARS ? trimmed : trimmed.substring(0, MAX_QUERY_CHARS);
    }

    private static int clampPageSize(int pageSize, int defaultSize, int maxSize) {
        if (pageSize <= 0) {
            return defaultSize;
        }
        return Math.min(maxSize, pageSize);
    }

    public static boolean canSendPlay() {
        try {
            return ClientPlayNetworking.canSend(PlayerListRequestC2S.TYPE);
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
        return clientRequestPlayerList(
                ClientLotteryState.playerListQuery,
                ClientLotteryState.playerListPage,
                PlayerListRequestC2S.DEFAULT_PAGE_SIZE);
    }

    public static boolean clientRequestPlayerList(String query, int page, int pageSize) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            String q = sanitizeQuery(query);
            int p = Math.max(0, page);
            int ps = clampPageSize(
                    pageSize,
                    PlayerListRequestC2S.DEFAULT_PAGE_SIZE,
                    PlayerListRequestC2S.MAX_PAGE_SIZE);
            ClientLotteryState.playerListQuery = q;
            ClientPlayNetworking.send(new PlayerListRequestC2S(q, p, ps));
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
            ClientPlayNetworking.send(new PlayerGreenApplesModifyC2S(
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
            ClientPlayNetworking.send(new PlayerCardModifyC2S(
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
            ClientPlayNetworking.send(BackupRequestC2S.INSTANCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientSaveTitleCatalog(String json) {
        return clientSaveTitleCatalog(json, currentTitleCatalogRevision());
    }

    public static boolean clientSaveTitleCatalog(String json, long expectedRevision) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            TitleCatalogSaveC2S logical = new TitleCatalogSaveC2S(
                    json == null ? "" : json,
                    Math.max(0L, expectedRevision));
            for (TitleCatalogSaveC2S chunk : logical.toWireChunks()) {
                ClientPlayNetworking.send(chunk);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientTitleModify(String mode, String targetUuid, String payload) {
        long rev = cachedTitlePlayerRevision(targetUuid).orElse(0L);
        return clientTitleModify(mode, targetUuid, payload, rev);
    }

    public static boolean clientTitleModify(String mode, String targetUuid, String payload, long expectedRevision) {
        if (!canSendPlay()) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new TitlePlayerModifyC2S(
                    mode == null ? "" : mode,
                    targetUuid == null ? "" : targetUuid,
                    payload == null ? "" : payload,
                    Math.max(0L, expectedRevision)));
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
            ClientPlayNetworking.send(TitleSnapshotRequestC2S.INSTANCE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestTitlePlayerPage() {
        return clientRequestTitlePlayerPage(
                ClientLotteryState.titlePlayersQuery,
                ClientLotteryState.titlePlayersPage,
                TitlePlayerPageRequestC2S.DEFAULT_PAGE_SIZE);
    }

    public static boolean clientRequestTitlePlayerPage(String query, int page, int pageSize) {
        try {
            if (!ClientPlayNetworking.canSend(TitlePlayerPageRequestC2S.TYPE)) {
                return false;
            }
            String q = sanitizeQuery(query);
            int p = Math.max(0, page);
            int ps = clampPageSize(
                    pageSize,
                    TitlePlayerPageRequestC2S.DEFAULT_PAGE_SIZE,
                    TitlePlayerPageRequestC2S.MAX_PAGE_SIZE);
            ClientLotteryState.titlePlayersQuery = q;
            ClientPlayNetworking.send(new TitlePlayerPageRequestC2S(q, p, ps));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clientRequestMailboxPage(int offset) {
        if (!ClientPlayNetworking.canSend(MailboxPages.Request.TYPE)) return false;
        ClientPlayNetworking.send(new MailboxPages.Request(Math.max(0, offset)));
        return true;
    }

    public static boolean clientRequestMailbox() {
        if (clientRequestMailboxPage(ClientLotteryState.mailboxOffset)) return true;
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
            ClientPlayNetworking.send(new CardUseConfirmC2S(
                    questKey == null ? "" : questKey,
                    mode == null ? "" : mode,
                    roleId == null ? "" : roleId));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

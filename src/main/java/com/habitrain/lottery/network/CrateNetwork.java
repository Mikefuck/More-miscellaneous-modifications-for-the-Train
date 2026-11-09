package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.crate.CrateService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** Network boundary for crate opening and the OP quota editor ({@code B-10}, {@code N-02}). */
public final class CrateNetwork {
    public static final int C2S_SAVE_MAX_CHARS = ChunkedJsonTransfer.C2S_CHUNK_CHARS;
    public static final int S2C_JSON_MAX_CHARS = ChunkedJsonTransfer.S2C_CHUNK_CHARS;
    public static final String RATE_LIMITED_KEY = "message.habitrain_lottery.crate.rate_limited";
    private static final Gson GSON = new Gson();

    private CrateNetwork() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }

    public record InventoryRequestC2S() implements CustomPacketPayload {
        public static final InventoryRequestC2S INSTANCE = new InventoryRequestC2S();
        public static final Type<InventoryRequestC2S> TYPE = new Type<>(id("crate_inventory_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InventoryRequestC2S> CODEC = StreamCodec.unit(INSTANCE);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record InventoryS2C(String json) implements CustomPacketPayload {
        public static final Type<InventoryS2C> TYPE = new Type<>(id("crate_inventory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InventoryS2C> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "{}" : p.json, LotteryNetwork.ADMIN_S2C_JSON_MAX_CHARS),
                b -> new InventoryS2C(b.readUtf(LotteryNetwork.ADMIN_S2C_JSON_MAX_CHARS)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CatalogS2C(String json, UUID transferId, int chunkIndex, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<CatalogS2C> TYPE = new Type<>(id("crate_catalog_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CatalogS2C> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.json == null ? "[]" : p.json, S2C_JSON_MAX_CHARS);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new CatalogS2C(
                        b.readUtf(S2C_JSON_MAX_CHARS),
                        b.readUUID(),
                        b.readVarInt(),
                        b.readVarInt()));

        public CatalogS2C(String json) {
            this(json, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
        }

        public List<CatalogS2C> toWireChunks() {
            return ChunkedJsonTransfer.splitS2C(
                    json == null ? "[]" : json,
                    CatalogS2C::new);
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenRequestC2S(String crateId, String keyId, String openId) implements CustomPacketPayload {
        public static final Type<OpenRequestC2S> TYPE = new Type<>(id("crate_open_request_v3"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequestC2S> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.crateId == null ? "" : p.crateId, 64);
                    b.writeUtf(p.keyId == null ? "" : p.keyId, 128);
                    b.writeUtf(p.openId == null ? "" : p.openId, 36);
                },
                b -> new OpenRequestC2S(b.readUtf(64), b.readUtf(128), b.readUtf(36)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Existing clients receive a readable upgrade error and never reach settlement. */
    public record LegacyOpenRequestC2S(String crateId, String keyId) implements CustomPacketPayload {
        public static final Type<LegacyOpenRequestC2S> TYPE = new Type<>(id("crate_open_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyOpenRequestC2S> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.crateId == null ? "" : p.crateId, 64);
                    b.writeUtf(p.keyId == null ? "" : p.keyId, 128);
                },
                b -> new LegacyOpenRequestC2S(b.readUtf(64), b.readUtf(128)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record LegacyOpenResultS2C(boolean success, String crateId, String keyId, String skinType,
                                      String skin, String quality, String message, int weeklyRemaining,
                                      int monthlyRemaining) implements CustomPacketPayload {
        public static final Type<LegacyOpenResultS2C> TYPE = new Type<>(id("crate_open_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyOpenResultS2C> CODEC = StreamCodec.of((b, p) -> {
            b.writeBoolean(p.success); b.writeUtf(p.crateId, 64); b.writeUtf(p.keyId, 128);
            b.writeUtf(p.skinType, 32); b.writeUtf(p.skin, 64); b.writeUtf(p.quality, 16);
            b.writeUtf(p.message, 256); b.writeVarInt(p.weeklyRemaining); b.writeVarInt(p.monthlyRemaining);
        }, b -> new LegacyOpenResultS2C(b.readBoolean(), b.readUtf(64), b.readUtf(128),
                b.readUtf(32), b.readUtf(64), b.readUtf(16), b.readUtf(256), b.readVarInt(), b.readVarInt()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenResultS2C(String openId, boolean success, String crateId, String keyId, String skinType, String skin,
                                String quality, String message, int weeklyRemaining, int monthlyRemaining,
                                String rewardsJson, long inventoryRevision)
            implements CustomPacketPayload {
        public static final Type<OpenResultS2C> TYPE = new Type<>(id("crate_open_result_v3"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenResultS2C> CODEC = StreamCodec.of((b, p) -> {
            b.writeUtf(p.openId == null ? "" : p.openId, 36);
            b.writeBoolean(p.success);
            b.writeUtf(p.crateId == null ? "" : p.crateId, 64);
            b.writeUtf(p.keyId == null ? "" : p.keyId, 128);
            b.writeUtf(p.skinType == null ? "" : p.skinType, 32);
            b.writeUtf(p.skin == null ? "" : p.skin, 64);
            b.writeUtf(p.quality == null ? "white" : p.quality, 16);
            b.writeUtf(p.message == null ? "" : p.message, 256);
            b.writeVarInt(p.weeklyRemaining);
            b.writeVarInt(p.monthlyRemaining);
            b.writeUtf(p.rewardsJson == null ? "[]" : p.rewardsJson, 16384);
            b.writeVarLong(p.inventoryRevision);
        }, b -> new OpenResultS2C(b.readUtf(36), b.readBoolean(), b.readUtf(64), b.readUtf(128), b.readUtf(32),
                b.readUtf(64), b.readUtf(16), b.readUtf(256), b.readVarInt(), b.readVarInt(), b.readUtf(16384), b.readVarLong()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigRequestC2S() implements CustomPacketPayload {
        public static final ConfigRequestC2S INSTANCE = new ConfigRequestC2S();
        public static final Type<ConfigRequestC2S> TYPE = new Type<>(id("crate_config_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigRequestC2S> CODEC = StreamCodec.unit(INSTANCE);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigSaveC2S(String json, UUID transferId, int chunkIndex, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<ConfigSaveC2S> TYPE = new Type<>(id("crate_config_save_v6"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.json == null ? "" : p.json, C2S_SAVE_MAX_CHARS);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new ConfigSaveC2S(
                        b.readUtf(C2S_SAVE_MAX_CHARS),
                        b.readUUID(),
                        b.readVarInt(),
                        b.readVarInt()));

        public ConfigSaveC2S(String json) {
            this(json, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
        }

        public List<ConfigSaveC2S> toWireChunks() {
            return ChunkedJsonTransfer.splitC2S(
                    json == null ? "" : json,
                    ConfigSaveC2S::new);
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Pre-material-quota editors cannot round-trip the per-crate settings safely. */
    public record LegacyV3ConfigSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<LegacyV3ConfigSaveC2S> TYPE = new Type<>(id("crate_config_save_v3"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyV3ConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, C2S_SAVE_MAX_CHARS),
                b -> new LegacyV3ConfigSaveC2S(b.readUtf(C2S_SAVE_MAX_CHARS)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record LegacyConfigSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<LegacyConfigSaveC2S> TYPE = new Type<>(id("crate_config_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, C2S_SAVE_MAX_CHARS),
                b -> new LegacyConfigSaveC2S(b.readUtf(C2S_SAVE_MAX_CHARS)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigS2C(String json, String message, UUID transferId, int chunkIndex, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("crate_config_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.json == null ? "{}" : p.json, S2C_JSON_MAX_CHARS);
                    b.writeUtf(p.message == null ? "" : p.message, 256);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new ConfigS2C(
                        b.readUtf(S2C_JSON_MAX_CHARS),
                        b.readUtf(256),
                        b.readUUID(),
                        b.readVarInt(),
                        b.readVarInt()));

        public ConfigS2C(String json, String message) {
            this(json, message, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
        }

        public List<ConfigS2C> toWireChunks() {
            String safeMessage = message == null ? "" : message;
            return ChunkedJsonTransfer.splitS2C(
                    json == null ? "{}" : json,
                    (piece, tid, idx, total) -> new ConfigS2C(piece, safeMessage, tid, idx, total));
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(InventoryRequestC2S.TYPE, InventoryRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenRequestC2S.TYPE, OpenRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LegacyOpenRequestC2S.TYPE, LegacyOpenRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigRequestC2S.TYPE, ConfigRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigSaveC2S.TYPE, ConfigSaveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LegacyV3ConfigSaveC2S.TYPE, LegacyV3ConfigSaveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LegacyConfigSaveC2S.TYPE, LegacyConfigSaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(InventoryS2C.TYPE, InventoryS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CatalogS2C.TYPE, CatalogS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenResultS2C.TYPE, OpenResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(LegacyOpenResultS2C.TYPE, LegacyOpenResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer joined = handler.getPlayer();
            if (joined != null && !joined.hasDisconnected()) {
                sendCatalog(joined);
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(InventoryRequestC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> {
                    ServerPlayer player = ctx.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (LotteryNetwork.rateLimited(player, "crate_inventory_req", 350)) return;
                    sendCatalog(player);
                    sendInventory(player);
                }));
        ServerPlayNetworking.registerGlobalReceiver(OpenRequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            String replyCrateId = resolveReplyCrateId(payload.crateId());
            String replyKeyId = payload.keyId() == null ? "" : payload.keyId();
            if (LotteryNetwork.rateLimited(player, "crate_open", 800)) {
                if (!LotteryNetwork.rateLimited(player, "crate_open_reply", 1000)) {
                    sendOpenFailure(player, payload.openId(), replyCrateId, replyKeyId, RATE_LIMITED_KEY);
                }
                return;
            }
            if (PlayerStateGate.spectatorRestOrDead(player)) {
                sendOpenFailure(player, payload.openId(), replyCrateId, replyKeyId, PlayerStateGate.ACTION_BLOCKED_KEY);
                return;
            }
            if (ServerMatchGate.hasActiveMatch(player)) {
                sendOpenFailure(player, payload.openId(), replyCrateId, replyKeyId, ServerMatchGate.MATCH_ACTIVE_BLOCKED_KEY);
                return;
            }
            CrateService.OpenResult result = CrateService.open(player, payload.crateId(), payload.keyId(), payload.openId());

            sendIfSupported(player, new OpenResultS2C(payload.openId(), result.success(), result.crateId(), result.keyId(), result.type(),
                    result.skin(), result.quality().id(), result.message(), result.weeklyRemaining(), result.monthlyRemaining(),
                    GSON.toJson(result.rewards()), result.inventoryRevision()));
            sendInventory(player);
        }));
        ServerPlayNetworking.registerGlobalReceiver(LegacyOpenRequestC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> {
                    ServerPlayer player = ctx.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (LotteryNetwork.rateLimited(player, "crate_legacy_open", 1000)) return;
                    sendIfSupported(player, new LegacyOpenResultS2C(false,
                            payload.crateId(), payload.keyId(), "", "", "white", "crates.client_outdated", 0, 0));
                }));
        ServerPlayNetworking.registerGlobalReceiver(ConfigRequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            if (LotteryNetwork.rateLimited(player, "crate_config_req", 350)) return;
            if (!canManage(player)) {
                sendIfSupported(player, new ConfigS2C("{}", "crates.no_permission"));
            } else {
                sendIfSupported(player, new ConfigS2C(CrateService.configJson(), ""));
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(ConfigSaveC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            boolean authorized = canManage(player);
            ChunkedJsonTransfer.GateResult<String> gate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    player.getUUID(),
                    "crate_config_save",
                    authorized,
                    () -> LotteryNetwork.rateLimited(player, "crate_config_save", 500),
                    payload.json(),
                    payload.transferId(),
                    payload.chunkIndex(),
                    payload.chunkCount(),
                    "");
            if (gate.status() == ChunkedJsonTransfer.GateStatus.UNAUTHORIZED) {
                if (!LotteryNetwork.rateLimited(player, "crate_config_save_unauth", 500)) {
                    sendIfSupported(player, new ConfigS2C("{}", "crates.no_permission"));
                }
                return;
            }
            if (gate.status() != ChunkedJsonTransfer.GateStatus.COMPLETED) {
                return;
            }
            boolean ok = CrateService.applyConfigJson(gate.json());
            String resultMessage = ok ? "crates.config_saved" : CrateService.configError();
            String latestConfig = CrateService.configJson();
            sendIfSupported(player, new ConfigS2C(latestConfig, resultMessage));
            if (ok) {
                String latestCatalog = CrateService.catalogJson();
                for (ServerPlayer online : ctx.server().getPlayerList().getPlayers()) {
                    if (online == null || online.hasDisconnected()) continue;
                    if (online != player && canManage(online)) {
                        sendIfSupported(online, new ConfigS2C(latestConfig, ""));
                    }
                    sendIfSupported(online, new CatalogS2C(latestCatalog));
                }
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(LegacyConfigSaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> {
                    ServerPlayer player = ctx.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (LotteryNetwork.rateLimited(player, "crate_legacy_save", 1000)) return;
                    sendIfSupported(player, new ConfigS2C("{}", "crates.client_outdated"));
                }));
        ServerPlayNetworking.registerGlobalReceiver(LegacyV3ConfigSaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> {
                    ServerPlayer player = ctx.player();
                    if (player == null || player.hasDisconnected()) return;
                    if (LotteryNetwork.rateLimited(player, "crate_legacy_v3_save", 1000)) return;
                    sendIfSupported(player, new ConfigS2C("{}", "crates.client_outdated"));
                }));
    }

    /** Broadcasts refreshed crate catalog and admin config after a server-side reload command. */
    public static void broadcastReload(MinecraftServer server) {
        if (server == null || server.getPlayerList() == null) return;
        String latestCatalog = CrateService.catalogJson();
        String latestConfig = CrateService.configJson();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online == null || online.hasDisconnected()) continue;
            sendIfSupported(online, new CatalogS2C(latestCatalog));
            if (canManage(online)) {
                sendIfSupported(online, new ConfigS2C(latestConfig, ""));
            }
        }
    }

    private static String resolveReplyCrateId(String rawCrateId) {
        CrateCatalog.Entry entry = CrateCatalog.find(rawCrateId);
        if (entry != null) {
            return entry.id();
        }
        return rawCrateId == null ? "" : rawCrateId;
    }

    private static void sendOpenFailure(ServerPlayer player, String openId, String crateId, String keyId, String message) {
        sendIfSupported(player, new OpenResultS2C(
                openId, false, crateId, keyId, "", "", "white", message, 0, 0, "[]", 0L));
    }

    /** A local world owner can configure their own crates without enabling cheats. LAN guests still need OP. */
    private static boolean canManage(ServerPlayer player) {
        return AdminAuth.isAuthorizedOp(player);
    }

    private static void sendCatalog(ServerPlayer player) {
        if (player != null && !player.hasDisconnected()) {
            sendIfSupported(player, new CatalogS2C(CrateService.catalogJson()));
        }
    }

    private static void sendInventory(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) return;
        sendIfSupported(player, new InventoryS2C(GSON.toJson(CrateService.inventory(player.getUUID()))));
    }

    private static void sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        LotteryNetwork.sendIfSupported(player, payload);
    }
}

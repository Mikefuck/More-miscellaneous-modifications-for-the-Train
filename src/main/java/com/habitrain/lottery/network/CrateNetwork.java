package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.api.skin.SkinQuality;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Network boundary for crate opening and the OP quota editor. */
public final class CrateNetwork {
    private CrateNetwork() {}
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path); }

    public record InventoryRequestC2S() implements CustomPacketPayload {
        public static final InventoryRequestC2S INSTANCE = new InventoryRequestC2S();
        public static final Type<InventoryRequestC2S> TYPE = new Type<>(id("crate_inventory_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InventoryRequestC2S> CODEC = StreamCodec.unit(INSTANCE);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record InventoryS2C(String json) implements CustomPacketPayload {
        public static final Type<InventoryS2C> TYPE = new Type<>(id("crate_inventory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InventoryS2C> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "{}" : p.json, 512_000),
                b -> new InventoryS2C(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CatalogS2C(String json) implements CustomPacketPayload {
        public static final Type<CatalogS2C> TYPE = new Type<>(id("crate_catalog_v3"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CatalogS2C> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "[]" : p.json, 512_000),
                b -> new CatalogS2C(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenRequestC2S(String crateId, String keyId, String openId) implements CustomPacketPayload {
        public static final Type<OpenRequestC2S> TYPE = new Type<>(id("crate_open_request_v3"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequestC2S> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.crateId == null ? "" : p.crateId, 64); b.writeUtf(p.keyId == null ? "" : p.keyId, 128);
                    b.writeUtf(p.openId == null ? "" : p.openId, 36); },
                b -> new OpenRequestC2S(b.readUtf(64), b.readUtf(128), b.readUtf(36)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Existing clients receive a readable upgrade error and never reach settlement. */
    public record LegacyOpenRequestC2S(String crateId, String keyId) implements CustomPacketPayload {
        public static final Type<LegacyOpenRequestC2S> TYPE = new Type<>(id("crate_open_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyOpenRequestC2S> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.crateId, 64); b.writeUtf(p.keyId, 128); },
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
            b.writeUtf(p.openId, 36);
            b.writeBoolean(p.success); b.writeUtf(p.crateId, 64); b.writeUtf(p.keyId, 128); b.writeUtf(p.skinType, 32);
            b.writeUtf(p.skin, 64); b.writeUtf(p.quality, 16); b.writeUtf(p.message, 256);
            b.writeVarInt(p.weeklyRemaining); b.writeVarInt(p.monthlyRemaining);
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

    public record ConfigSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<ConfigSaveC2S> TYPE = new Type<>(id("crate_config_save_v5"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, 512_000), b -> new ConfigSaveC2S(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Pre-material-quota editors cannot round-trip the per-crate settings safely. */
    public record LegacyV3ConfigSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<LegacyV3ConfigSaveC2S> TYPE = new Type<>(id("crate_config_save_v3"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyV3ConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, 512_000),
                b -> new LegacyV3ConfigSaveC2S(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record LegacyConfigSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<LegacyConfigSaveC2S> TYPE = new Type<>(id("crate_config_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LegacyConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, 512_000),
                b -> new LegacyConfigSaveC2S(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigS2C(String json, String message) implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("crate_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.json == null ? "{}" : p.json, 512_000); b.writeUtf(p.message == null ? "" : p.message, 256); },
                b -> new ConfigS2C(b.readUtf(512_000), b.readUtf(256)));
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

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> sendCatalog(handler.getPlayer())));
        ServerPlayNetworking.registerGlobalReceiver(InventoryRequestC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> { sendCatalog(ctx.player()); sendInventory(ctx.player()); }));
        ServerPlayNetworking.registerGlobalReceiver(OpenRequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || LotteryNetwork.rateLimited(player, "crate_open", 800)) return;
            CrateService.OpenResult result = CrateService.open(player, payload.crateId(), payload.keyId(), payload.openId());
            if (result.success()) com.habitrain.lottery.daily.config.DailyTaskTracker.onCrateOpened(
                    player, result.crateId(), payload.openId());
            sendIfSupported(player, new OpenResultS2C(payload.openId(), result.success(), result.crateId(), result.keyId(), result.type(),
                    result.skin(), result.quality().id(), result.message(), result.weeklyRemaining(), result.monthlyRemaining(),
                    new com.google.gson.Gson().toJson(result.rewards()), result.inventoryRevision()));
            sendInventory(player);
        }));
        ServerPlayNetworking.registerGlobalReceiver(LegacyOpenRequestC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> sendIfSupported(ctx.player(), new LegacyOpenResultS2C(false,
                        payload.crateId(), payload.keyId(), "", "", "white", "crates.client_outdated", 0, 0))));
        ServerPlayNetworking.registerGlobalReceiver(ConfigRequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            if (!canManage(player)) {
                sendIfSupported(player, new ConfigS2C("{}", "crates.no_permission"));
            } else {
                sendIfSupported(player, new ConfigS2C(CrateService.configJson(), ""));
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(ConfigSaveC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            if (!canManage(player)) {
                sendIfSupported(player, new ConfigS2C("{}", "crates.no_permission"));
                return;
            }
            boolean ok = CrateService.applyConfigJson(payload.json());
            String resultMessage = ok ? "crates.config_saved" : CrateService.configError();
            sendIfSupported(player, new ConfigS2C(CrateService.configJson(), resultMessage));
            if (ok) for (ServerPlayer online : ctx.server().getPlayerList().getPlayers()) {
                if (online != player && canManage(online))
                    sendIfSupported(online, new ConfigS2C(CrateService.configJson(), ""));
                sendCatalog(online);
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(LegacyConfigSaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> sendIfSupported(ctx.player(),
                        new ConfigS2C(CrateService.configJson(), "crates.client_outdated"))));
        ServerPlayNetworking.registerGlobalReceiver(LegacyV3ConfigSaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> sendIfSupported(ctx.player(),
                        new ConfigS2C("{}", "crates.client_outdated"))));
    }

    /** A local world owner can configure their own crates without enabling cheats. LAN guests still need OP. */
    private static boolean canManage(ServerPlayer player) {
        return player != null && (LotteryNetwork.isOp(player)
                || player.server.isSingleplayerOwner(player.getGameProfile())) && !LotteryNetwork.gateBlocked(player);
    }

    private static void sendCatalog(ServerPlayer player) {
        if (player != null) sendIfSupported(player, new CatalogS2C(CrateService.catalogJson()));
    }

    private static void sendInventory(ServerPlayer player) {
        if (player == null) return;
        sendIfSupported(player, new InventoryS2C(new com.google.gson.Gson().toJson(CrateService.inventory(player.getUUID()))));
    }

    private static void sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }
}

package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.api.skin.SkinQuality;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

    public record OpenRequestC2S(String crateId, String keyId) implements CustomPacketPayload {
        public static final Type<OpenRequestC2S> TYPE = new Type<>(id("crate_open_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequestC2S> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.crateId == null ? "" : p.crateId, 64); b.writeUtf(p.keyId == null ? "" : p.keyId, 128); },
                b -> new OpenRequestC2S(b.readUtf(64), b.readUtf(128)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenResultS2C(boolean success, String crateId, String keyId, String skinType, String skin,
                                String quality, String message, int weeklyRemaining, int monthlyRemaining)
            implements CustomPacketPayload {
        public static final Type<OpenResultS2C> TYPE = new Type<>(id("crate_open_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenResultS2C> CODEC = StreamCodec.of((b, p) -> {
            b.writeBoolean(p.success); b.writeUtf(p.crateId, 64); b.writeUtf(p.keyId, 128); b.writeUtf(p.skinType, 32);
            b.writeUtf(p.skin, 64); b.writeUtf(p.quality, 16); b.writeUtf(p.message, 256);
            b.writeVarInt(p.weeklyRemaining); b.writeVarInt(p.monthlyRemaining);
        }, b -> new OpenResultS2C(b.readBoolean(), b.readUtf(64), b.readUtf(128), b.readUtf(32),
                b.readUtf(64), b.readUtf(16), b.readUtf(256), b.readVarInt(), b.readVarInt()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigRequestC2S() implements CustomPacketPayload {
        public static final ConfigRequestC2S INSTANCE = new ConfigRequestC2S();
        public static final Type<ConfigRequestC2S> TYPE = new Type<>(id("crate_config_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigRequestC2S> CODEC = StreamCodec.unit(INSTANCE);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigSaveC2S(String json) implements CustomPacketPayload {
        public static final Type<ConfigSaveC2S> TYPE = new Type<>(id("crate_config_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, 512_000), b -> new ConfigSaveC2S(b.readUtf(512_000)));
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
        PayloadTypeRegistry.playC2S().register(ConfigRequestC2S.TYPE, ConfigRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigSaveC2S.TYPE, ConfigSaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(InventoryS2C.TYPE, InventoryS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenResultS2C.TYPE, OpenResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(InventoryRequestC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> sendInventory(ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(OpenRequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || LotteryNetwork.rateLimited(player, "crate_open", 800)) return;
            CrateService.OpenResult result = CrateService.open(player, payload.crateId(), payload.keyId());
            sendIfSupported(player, new OpenResultS2C(result.success(), result.crateId(), result.keyId(), result.type(),
                    result.skin(), result.quality().id(), result.message(), result.weeklyRemaining(), result.monthlyRemaining()));
            sendInventory(player);
        }));
        ServerPlayNetworking.registerGlobalReceiver(ConfigRequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            if (!LotteryNetwork.isOp(player) || LotteryNetwork.gateBlocked(player)) {
                sendIfSupported(player, new ConfigS2C("{}", "crates.no_permission"));
            } else {
                sendIfSupported(player, new ConfigS2C(CrateService.configJson(), ""));
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(ConfigSaveC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            if (!LotteryNetwork.isOp(player) || LotteryNetwork.gateBlocked(player)) {
                sendIfSupported(player, new ConfigS2C(CrateService.configJson(), "crates.no_permission"));
                return;
            }
            boolean ok = CrateService.applyConfigJson(payload.json());
            sendIfSupported(player, new ConfigS2C(CrateService.configJson(), ok ? "crates.config_saved" : "crates.config_failed"));
        }));
    }

    private static void sendInventory(ServerPlayer player) {
        if (player == null) return;
        sendIfSupported(player, new InventoryS2C(new com.google.gson.Gson().toJson(CrateService.inventory(player.getUUID()))));
    }

    private static void sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }
}

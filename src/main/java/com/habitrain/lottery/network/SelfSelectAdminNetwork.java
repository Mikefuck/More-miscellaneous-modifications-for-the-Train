package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.card.SelfSelectRoleConfigService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** OP/owner editor. Every response is tied to its originating request and screen. */
public final class SelfSelectAdminNetwork {
    private SelfSelectAdminNetwork() { }
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }

    public record RequestC2S(String requestId) implements CustomPacketPayload {
        public static final Type<RequestC2S> TYPE = new Type<>(id("self_select_admin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.requestId, 64), b -> new RequestC2S(b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SaveC2S(String requestId, String json) implements CustomPacketPayload {
        public static final Type<SaveC2S> TYPE = new Type<>(id("self_select_admin_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveC2S> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.requestId, 64); b.writeUtf(p.json, 1_200_000); },
                b -> new SaveC2S(b.readUtf(64), b.readUtf(1_200_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigS2C(String requestId, String json, String message) implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("self_select_admin_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.requestId, 64); b.writeUtf(p.json, 2_000_000); b.writeUtf(p.message, 64); },
                b -> new ConfigS2C(b.readUtf(64), b.readUtf(2_000_000), b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(RequestC2S.TYPE, RequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveC2S.TYPE, SaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestC2S.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (LotteryNetwork.rateLimited(player, "self_select_admin_request", 300)) {
                send(player, p.requestId, "rate_limited");
                return;
            }
            send(player, p.requestId, "");
        }));
        ServerPlayNetworking.registerGlobalReceiver(SaveC2S.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (!DailyTaskAdminNetwork.canManage(player)) {
                send(player, p.requestId, "no_permission");
                return;
            }
            if (LotteryNetwork.rateLimited(player, "self_select_admin_save", 500)) {
                send(player, p.requestId, "rate_limited");
                return;
            }
            String error = SelfSelectRoleConfigService.apply(p.json);
            send(player, p.requestId, error.isEmpty() ? "saved" : error);
            if (error.isEmpty()) HabiLotteryMod.LOGGER.info("{} saved self-select role restrictions",
                    player.getGameProfile().getName());
        }));
    }

    private static void send(ServerPlayer player, String requestId, String message) {
        if (player == null || !ServerPlayNetworking.canSend(player, ConfigS2C.TYPE)) return;
        if (!DailyTaskAdminNetwork.canManage(player)) {
            ServerPlayNetworking.send(player, new ConfigS2C(requestId, "{}", "no_permission"));
            return;
        }
        String error = SelfSelectRoleConfigService.loadError();
        ServerPlayNetworking.send(player, new ConfigS2C(requestId, SelfSelectRoleConfigService.snapshotJson(),
                message.isEmpty() ? error : message));
    }
}

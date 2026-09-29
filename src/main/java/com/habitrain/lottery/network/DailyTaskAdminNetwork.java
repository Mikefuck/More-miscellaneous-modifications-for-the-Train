package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.daily.config.DailyTaskConfigService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** OP editor channel for {@code daily_tasks.json}; the server validates and owns every save. */
public final class DailyTaskAdminNetwork {
    public static final String NO_PERMISSION = "no_permission";
    public static final String SAVED = "saved";

    private DailyTaskAdminNetwork() { }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }

    public record RequestC2S() implements CustomPacketPayload {
        public static final RequestC2S INSTANCE = new RequestC2S();
        public static final Type<RequestC2S> TYPE = new Type<>(id("daily_admin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestC2S> CODEC = StreamCodec.unit(INSTANCE);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SaveC2S(String json) implements CustomPacketPayload {
        public static final Type<SaveC2S> TYPE = new Type<>(id("daily_admin_save_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, 512_000), b -> new SaveC2S(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** {@code message}: empty, {@link #SAVED}, {@link #NO_PERMISSION} or a validation code. */
    public record ConfigS2C(String json, String message) implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("daily_admin_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.json == null ? "{}" : p.json, 1_000_000); b.writeUtf(p.message == null ? "" : p.message, 512); },
                b -> new ConfigS2C(b.readUtf(1_000_000), b.readUtf(512)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(RequestC2S.TYPE, RequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveC2S.TYPE, SaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(RequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || LotteryNetwork.rateLimited(player, "daily_admin_request", 300)) return;
            send(player, canManage(player) ? DailyTaskConfigService.snapshotJson() : "{}",
                    canManage(player) ? "" : NO_PERMISSION);
        }));
        ServerPlayNetworking.registerGlobalReceiver(SaveC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            if (!canManage(player)) {
                send(player, "{}", NO_PERMISSION);
                return;
            }
            if (LotteryNetwork.rateLimited(player, "daily_admin_save", 500)) return;
            boolean ok = DailyTaskConfigService.apply(payload.json());
            send(player, DailyTaskConfigService.snapshotJson(), ok ? SAVED : DailyTaskConfigService.lastError());
            if (!ok) return;
            HabiLotteryMod.LOGGER.info("{} saved the daily task configuration", player.getGameProfile().getName());
            for (ServerPlayer online : ctx.server().getPlayerList().getPlayers()) {
                if (online != player && canManage(online)) send(online, DailyTaskConfigService.snapshotJson(), "");
                // Boards refresh immediately so players see new / changed tasks without reopening.
                LotteryNetwork.sendDailyTaskSnapshot(online, false);
            }
        }));
    }

    /** Same rule as the crate editor: OP, or the owner of a singleplayer world; never gate-blocked. */
    public static boolean canManage(ServerPlayer player) {
        return player != null && (LotteryNetwork.isOp(player)
                || player.server.isSingleplayerOwner(player.getGameProfile())) && !LotteryNetwork.gateBlocked(player);
    }

    private static void send(ServerPlayer player, String json, String message) {
        ConfigS2C payload = new ConfigS2C(json, message);
        if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }
}

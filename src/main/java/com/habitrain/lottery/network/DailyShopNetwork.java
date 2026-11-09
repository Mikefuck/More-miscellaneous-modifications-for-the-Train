package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.daily.shop.DailyShopService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Daily-terminal shop: the player purchase request, and the OP editor channel for
 * {@code daily_shop.json} ({@code B-10}, {@code N-02}).
 */
public final class DailyShopNetwork {
    public static final String NO_PERMISSION = DailyTaskAdminNetwork.NO_PERMISSION;
    public static final String SAVED = DailyTaskAdminNetwork.SAVED;
    public static final int C2S_SAVE_MAX_CHARS = ChunkedJsonTransfer.C2S_CHUNK_CHARS;
    public static final int S2C_JSON_MAX_CHARS = ChunkedJsonTransfer.S2C_CHUNK_CHARS;

    private DailyShopNetwork() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }

    /** {@code price} is what the player saw; the server refuses if it no longer matches. */
    public record BuyC2S(String itemId, int price) implements CustomPacketPayload {
        public static final Type<BuyC2S> TYPE = new Type<>(id("daily_shop_buy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BuyC2S> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.itemId == null ? "" : p.itemId, 64); b.writeVarInt(p.price); },
                b -> new BuyC2S(b.readUtf(64), b.readVarInt()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestC2S() implements CustomPacketPayload {
        public static final RequestC2S INSTANCE = new RequestC2S();
        public static final Type<RequestC2S> TYPE = new Type<>(id("daily_shop_admin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestC2S> CODEC = StreamCodec.unit(INSTANCE);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SaveC2S(String json, UUID transferId, int chunkIndex, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<SaveC2S> TYPE = new Type<>(id("daily_shop_admin_save_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveC2S> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.json == null ? "" : p.json, C2S_SAVE_MAX_CHARS);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new SaveC2S(
                        b.readUtf(C2S_SAVE_MAX_CHARS),
                        b.readUUID(),
                        b.readVarInt(),
                        b.readVarInt()));

        public SaveC2S(String json) {
            this(json, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
        }

        public List<SaveC2S> toWireChunks() {
            return ChunkedJsonTransfer.splitC2S(
                    json == null ? "" : json,
                    SaveC2S::new);
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** {@code message}: empty, {@link #SAVED}, {@link #NO_PERMISSION} or a validation code. */
    public record ConfigS2C(String json, String message, UUID transferId, int chunkIndex, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("daily_shop_admin_config_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.json == null ? "{}" : p.json, S2C_JSON_MAX_CHARS);
                    b.writeUtf(p.message == null ? "" : p.message, 512);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new ConfigS2C(
                        b.readUtf(S2C_JSON_MAX_CHARS),
                        b.readUtf(512),
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
        PayloadTypeRegistry.playC2S().register(BuyC2S.TYPE, BuyC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestC2S.TYPE, RequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveC2S.TYPE, SaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(BuyC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            // Buying is a player action: the Mod-menu gate only guards config writes, so it is not checked here.
            if (LotteryNetwork.rateLimited(player, "daily_shop_buy", 350)) {
                // Still answer so the button leaves "buying…"; a second slot keeps a flood from buying boards.
                if (!LotteryNetwork.rateLimited(player, "daily_shop_buy_reply", 1000)) {
                    player.sendSystemMessage(Component.translatable("message.habitrain_lottery.shop.rate_limited"));
                    LotteryNetwork.sendDailyTaskSnapshot(player, false, payload.itemId());
                }
                return;
            }
            if (PlayerStateGate.spectatorRestOrDead(player)) {
                player.sendSystemMessage(PlayerStateGate.actionBlockedComponent());
                LotteryNetwork.sendDailyTaskSnapshot(player, false, payload.itemId());
                return;
            }
            String result = DailyShopService.buy(player, payload.itemId(), payload.price());
            if (!"ok".equals(result)) {
                player.sendSystemMessage(failComponent(result));
            }
            // Always answer with a fresh board: its receipt clears the client's pending state and shows the new balance.
            LotteryNetwork.sendDailyTaskSnapshot(player, false, payload.itemId());
        }));

        ServerPlayNetworking.registerGlobalReceiver(RequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()
                    || LotteryNetwork.rateLimited(player, "daily_shop_admin_request", 300)) return;
            boolean ok = DailyTaskAdminNetwork.canManage(player);
            send(player, ok ? DailyShopService.snapshotJson() : "{}", ok ? "" : NO_PERMISSION);
        }));
        ServerPlayNetworking.registerGlobalReceiver(SaveC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            boolean authorized = DailyTaskAdminNetwork.canManage(player);
            ChunkedJsonTransfer.GateResult<String> gate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    player.getUUID(),
                    "daily_shop_admin_save",
                    authorized,
                    () -> LotteryNetwork.rateLimited(player, "daily_shop_admin_save", 500),
                    payload.json(),
                    payload.transferId(),
                    payload.chunkIndex(),
                    payload.chunkCount(),
                    "");
            if (gate.status() == ChunkedJsonTransfer.GateStatus.UNAUTHORIZED) {
                if (!LotteryNetwork.rateLimited(player, "daily_shop_admin_save_unauth", 500)) {
                    send(player, "{}", NO_PERMISSION);
                }
                return;
            }
            if (gate.status() != ChunkedJsonTransfer.GateStatus.COMPLETED) {
                return;
            }
            boolean ok = DailyShopService.apply(gate.json());
            String snapshot = DailyShopService.snapshotJson();
            send(player, snapshot, ok ? SAVED : DailyShopService.lastError());
            if (!ok) return;
            HabiLotteryMod.LOGGER.info("{} saved the daily shop configuration", player.getGameProfile().getName());
            for (ServerPlayer online : ctx.server().getPlayerList().getPlayers()) {
                if (online == null || online.hasDisconnected()) continue;
                if (online != player && DailyTaskAdminNetwork.canManage(online)) send(online, snapshot, "");
                LotteryNetwork.sendDailyTaskSnapshot(online, false);
            }
        }));
    }

    public static String failKey(String code) {
        return switch (code == null ? "" : code) {
            case "gone" -> "message.habitrain_lottery.shop.fail.gone";
            case "price_changed" -> "message.habitrain_lottery.shop.fail.price_changed";
            case "sold_out" -> "message.habitrain_lottery.shop.fail.sold_out";
            case "insufficient" -> "message.habitrain_lottery.shop.fail.insufficient";
            case "owned" -> "message.habitrain_lottery.shop.fail.owned";
            case "in_match" -> "message.habitrain_lottery.shop.fail.in_match";
            case "broken" -> "message.habitrain_lottery.shop.fail.broken";
            case "unavailable" -> "message.habitrain_lottery.shop.fail.unavailable";
            default -> "message.habitrain_lottery.shop.fail.default";
        };
    }

    public static Component failComponent(String code) {
        return Component.translatable(failKey(code));
    }

    private static void send(ServerPlayer player, String json, String message) {
        LotteryNetwork.sendIfSupported(player, new ConfigS2C(json, message));
    }
}

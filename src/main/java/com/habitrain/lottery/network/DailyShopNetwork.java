package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.daily.shop.DailyShopService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Daily-terminal shop: the player purchase request, and the OP editor channel for
 * {@code daily_shop.json} (same request / save / config shape as {@link DailyTaskAdminNetwork}).
 */
public final class DailyShopNetwork {
    public static final String NO_PERMISSION = DailyTaskAdminNetwork.NO_PERMISSION;
    public static final String SAVED = DailyTaskAdminNetwork.SAVED;

    private DailyShopNetwork() { }

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

    public record SaveC2S(String json) implements CustomPacketPayload {
        public static final Type<SaveC2S> TYPE = new Type<>(id("daily_shop_admin_save_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.json == null ? "" : p.json, 512_000), b -> new SaveC2S(b.readUtf(512_000)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** {@code message}: empty, {@link #SAVED}, {@link #NO_PERMISSION} or a validation code. */
    public record ConfigS2C(String json, String message) implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("daily_shop_admin_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.json == null ? "{}" : p.json, 1_000_000); b.writeUtf(p.message == null ? "" : p.message, 512); },
                b -> new ConfigS2C(b.readUtf(1_000_000), b.readUtf(512)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(BuyC2S.TYPE, BuyC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestC2S.TYPE, RequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveC2S.TYPE, SaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(BuyC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            // Buying is a player action: the Mod-menu gate only guards config writes, so it is not checked here.
            if (LotteryNetwork.rateLimited(player, "daily_shop_buy", 350)) {
                // Still answer so the button leaves "buying…"; a second slot keeps a flood from buying boards.
                if (!LotteryNetwork.rateLimited(player, "daily_shop_buy_reply", 1000)) {
                    player.sendSystemMessage(Component.literal("§c[商店] 操作过快，请稍后再试"));
                    LotteryNetwork.sendDailyTaskSnapshot(player, false, payload.itemId());
                }
                return;
            }
            String result = DailyShopService.buy(player, payload.itemId(), payload.price());
            if (!"ok".equals(result)) player.sendSystemMessage(Component.literal("§c[商店] " + failText(result)));
            // Always answer with a fresh board: its receipt clears the client's pending state and shows the new balance.
            LotteryNetwork.sendDailyTaskSnapshot(player, false, payload.itemId());
        }));

        ServerPlayNetworking.registerGlobalReceiver(RequestC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || LotteryNetwork.rateLimited(player, "daily_shop_admin_request", 300)) return;
            boolean ok = DailyTaskAdminNetwork.canManage(player);
            send(player, ok ? DailyShopService.snapshotJson() : "{}", ok ? "" : NO_PERMISSION);
        }));
        ServerPlayNetworking.registerGlobalReceiver(SaveC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null) return;
            if (!DailyTaskAdminNetwork.canManage(player)) {
                send(player, "{}", NO_PERMISSION);
                return;
            }
            if (LotteryNetwork.rateLimited(player, "daily_shop_admin_save", 500)) return;
            boolean ok = DailyShopService.apply(payload.json());
            send(player, DailyShopService.snapshotJson(), ok ? SAVED : DailyShopService.lastError());
            if (!ok) return;
            HabiLotteryMod.LOGGER.info("{} saved the daily shop configuration", player.getGameProfile().getName());
            for (ServerPlayer online : ctx.server().getPlayerList().getPlayers()) {
                if (online != player && DailyTaskAdminNetwork.canManage(online)) send(online, DailyShopService.snapshotJson(), "");
                LotteryNetwork.sendDailyTaskSnapshot(online, false);
            }
        }));
    }

    /** Server-side wording (no client language available for chat receipts). */
    static String failText(String code) {
        return switch (code == null ? "" : code) {
            case "gone" -> "该商品已下架";
            case "price_changed" -> "商品价格已变动，请确认新价格后再购买";
            case "sold_out" -> "已达到限购次数";
            case "insufficient" -> "绿苹果不足";
            case "owned" -> "你已拥有该商品";
            case "in_match" -> "对局中不能购买角色卡，请在对局结束后购买";
            case "broken" -> "商品内容已失效（箱子被归档或皮肤已移除），请联系管理员";
            case "unavailable" -> "玩家存档暂不可用，请稍后再试";
            default -> "购买失败，未扣除绿苹果";
        };
    }

    private static void send(ServerPlayer player, String json, String message) {
        ConfigS2C payload = new ConfigS2C(json, message);
        if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }
}

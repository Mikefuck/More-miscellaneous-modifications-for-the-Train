package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayerListRequestC2S(String query, int page, int pageSize) implements CustomPacketPayload {
    public static final int DEFAULT_PAGE_SIZE = LotteryAdminServerHandlers.DEFAULT_PAGE_SIZE;
    public static final int MAX_PAGE_SIZE = LotteryAdminServerHandlers.MAX_PAGE_SIZE;
    public static final PlayerListRequestC2S INSTANCE = new PlayerListRequestC2S();
    public static final Type<PlayerListRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "player_list_req_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerListRequestC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.query == null ? "" : value.query, 64);
                buf.writeVarInt(Math.max(0, value.page));
                buf.writeVarInt(LotteryAdminServerHandlers.clampPageSize(value.pageSize));
            },
            buf -> new PlayerListRequestC2S(
                    buf.readUtf(64),
                    Math.max(0, buf.readVarInt()),
                    LotteryAdminServerHandlers.clampPageSize(buf.readVarInt()))
    );

    public PlayerListRequestC2S() {
        this("", 0, DEFAULT_PAGE_SIZE);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

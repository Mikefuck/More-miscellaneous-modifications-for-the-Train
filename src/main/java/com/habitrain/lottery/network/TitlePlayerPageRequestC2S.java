package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TitlePlayerPageRequestC2S(String query, int page, int pageSize) implements CustomPacketPayload {
    public static final int DEFAULT_PAGE_SIZE = LotteryTitleServerHandlers.DEFAULT_PAGE_SIZE;
    public static final int MAX_PAGE_SIZE = LotteryTitleServerHandlers.MAX_PAGE_SIZE;
    public static final TitlePlayerPageRequestC2S INSTANCE = new TitlePlayerPageRequestC2S();
    public static final Type<TitlePlayerPageRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "title_player_page_req"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TitlePlayerPageRequestC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.query == null ? "" : value.query, 64);
                buf.writeVarInt(Math.max(0, value.page));
                buf.writeVarInt(LotteryAdminServerHandlers.clampPageSize(value.pageSize));
            },
            buf -> new TitlePlayerPageRequestC2S(
                    buf.readUtf(64),
                    Math.max(0, buf.readVarInt()),
                    LotteryAdminServerHandlers.clampPageSize(buf.readVarInt()))
    );

    public TitlePlayerPageRequestC2S() {
        this("", 0, DEFAULT_PAGE_SIZE);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

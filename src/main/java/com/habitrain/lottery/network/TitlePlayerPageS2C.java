package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record TitlePlayerPageS2C(
        String playersJson,
        int page,
        int totalPages,
        int totalMatched,
        UUID transferId,
        int chunkIndex,
        int chunkCount
) implements CustomPacketPayload {
    public static final Type<TitlePlayerPageS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "title_player_page_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TitlePlayerPageS2C> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.playersJson == null ? "{}" : value.playersJson, TitlePageTransfer.CHUNK_CHARS);
                buf.writeVarInt(Math.max(0, value.page));
                buf.writeVarInt(Math.max(1, value.totalPages));
                buf.writeVarInt(Math.max(0, value.totalMatched));
                buf.writeUUID(value.transferId == null ? TitlePageTransfer.LOGICAL_PAGE : value.transferId);
                buf.writeVarInt(value.chunkIndex);
                buf.writeVarInt(value.chunkCount);
            },
            buf -> new TitlePlayerPageS2C(
                    buf.readUtf(TitlePageTransfer.CHUNK_CHARS),
                    Math.max(0, buf.readVarInt()),
                    Math.max(1, buf.readVarInt()),
                    Math.max(0, buf.readVarInt()),
                    buf.readUUID(),
                    buf.readVarInt(),
                    buf.readVarInt())
    );

    public TitlePlayerPageS2C(String playersJson, int page, int totalPages, int totalMatched) {
        this(playersJson, page, totalPages, totalMatched, TitlePageTransfer.LOGICAL_PAGE, 0, 1);
    }

    public TitlePlayerPageS2C(String playersJson) {
        this(playersJson, 0, 1, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

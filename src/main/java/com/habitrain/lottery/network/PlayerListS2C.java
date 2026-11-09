package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

public record PlayerListS2C(
        String json,
        int page,
        int totalPages,
        int totalMatched,
        UUID transferId,
        int chunkIndex,
        int chunkCount
) implements CustomPacketPayload {
    public static final int CHUNK_CHARS = ChunkedJsonTransfer.S2C_CHUNK_CHARS;
    public static final Type<PlayerListS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "player_list_v3"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerListS2C> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.json == null ? "{}" : value.json, CHUNK_CHARS);
                buf.writeVarInt(Math.max(0, value.page));
                buf.writeVarInt(Math.max(1, value.totalPages));
                buf.writeVarInt(Math.max(0, value.totalMatched));
                buf.writeUUID(value.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : value.transferId);
                buf.writeVarInt(value.chunkIndex);
                buf.writeVarInt(value.chunkCount);
            },
            buf -> new PlayerListS2C(
                    buf.readUtf(CHUNK_CHARS),
                    Math.max(0, buf.readVarInt()),
                    Math.max(1, buf.readVarInt()),
                    Math.max(0, buf.readVarInt()),
                    buf.readUUID(),
                    buf.readVarInt(),
                    buf.readVarInt())
    );

    public PlayerListS2C(String json, int page, int totalPages, int totalMatched) {
        this(json, page, totalPages, totalMatched, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
    }

    public PlayerListS2C(String json) {
        this(json, 0, 1, 0);
    }

    public List<PlayerListS2C> toWireChunks() {
        return ChunkedJsonTransfer.splitS2C(
                json == null ? "{}" : json,
                (piece, tid, idx, total) -> new PlayerListS2C(
                        piece, page, totalPages, totalMatched, tid, idx, total));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

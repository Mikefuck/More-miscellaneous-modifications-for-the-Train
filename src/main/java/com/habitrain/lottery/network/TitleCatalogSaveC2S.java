package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

public record TitleCatalogSaveC2S(
        String json,
        long expectedRevision,
        UUID transferId,
        int chunkIndex,
        int chunkCount
) implements CustomPacketPayload {
    public static final int MAX_JSON_CHARS = ChunkedJsonTransfer.C2S_CHUNK_CHARS;
    public static final Type<TitleCatalogSaveC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "title_catalog_save_v3"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TitleCatalogSaveC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.json == null ? "" : value.json, MAX_JSON_CHARS);
                buf.writeVarLong(value.expectedRevision);
                buf.writeUUID(value.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : value.transferId);
                buf.writeVarInt(value.chunkIndex);
                buf.writeVarInt(value.chunkCount);
            },
            buf -> new TitleCatalogSaveC2S(
                    buf.readUtf(MAX_JSON_CHARS),
                    buf.readVarLong(),
                    buf.readUUID(),
                    buf.readVarInt(),
                    buf.readVarInt())
    );

    public TitleCatalogSaveC2S(String json, long expectedRevision) {
        this(json, expectedRevision, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
    }

    public TitleCatalogSaveC2S(String json) {
        this(json, -1L);
    }

    public List<TitleCatalogSaveC2S> toWireChunks() {
        return ChunkedJsonTransfer.splitC2S(
                json == null ? "" : json,
                (piece, tid, idx, total) -> new TitleCatalogSaveC2S(
                        piece, expectedRevision, tid, idx, total));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

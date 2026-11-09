package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record TitleSnapshotS2C(
        String catalogJson,
        String playersJson,
        UUID transferId,
        int chunkIndex,
        int chunkCount
) implements CustomPacketPayload {
    public static final int CHUNK_CHARS = ChunkedJsonTransfer.S2C_CHUNK_CHARS;
    private static final int FIELD_CHUNK_CHARS = ChunkedJsonTransfer.C2S_CHUNK_CHARS;
    private static final int FIELD_CHUNK_UTF8_BYTES = ChunkedJsonTransfer.C2S_CHUNK_MAX_UTF8_BYTES;

    public static final Type<TitleSnapshotS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "title_snapshot_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TitleSnapshotS2C> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.catalogJson == null ? "" : value.catalogJson, CHUNK_CHARS);
                buf.writeUtf(value.playersJson == null ? "" : value.playersJson, CHUNK_CHARS);
                buf.writeUUID(value.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : value.transferId);
                buf.writeVarInt(value.chunkIndex);
                buf.writeVarInt(value.chunkCount);
            },
            buf -> new TitleSnapshotS2C(
                    buf.readUtf(CHUNK_CHARS),
                    buf.readUtf(CHUNK_CHARS),
                    buf.readUUID(),
                    buf.readVarInt(),
                    buf.readVarInt())
    );

    public TitleSnapshotS2C(String catalogJson, String playersJson) {
        this(catalogJson, playersJson, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
    }

    public List<TitleSnapshotS2C> toWireChunks() {
        String safeCat = catalogJson == null ? "" : catalogJson;
        String safePlayers = playersJson == null ? "" : playersJson;
        int totalBytes = ChunkedJsonTransfer.utf8Length(safeCat) + ChunkedJsonTransfer.utf8Length(safePlayers);
        if (totalBytes > ChunkedJsonTransfer.S2C_MAX_TOTAL_UTF8_BYTES) {
            throw new IllegalArgumentException("TitleSnapshotS2C exceeds max UTF-8 budget: " + totalBytes);
        }
        List<String> catPieces = ChunkedJsonTransfer.splitPieces(
                safeCat, FIELD_CHUNK_CHARS, FIELD_CHUNK_UTF8_BYTES);
        List<String> playerPieces = ChunkedJsonTransfer.splitPieces(
                safePlayers, FIELD_CHUNK_CHARS, FIELD_CHUNK_UTF8_BYTES);
        int count = Math.max(catPieces.size(), playerPieces.size());
        if (count > ChunkedJsonTransfer.S2C_MAX_CHUNKS) {
            throw new IllegalArgumentException("TitleSnapshotS2C exceeds max chunk count: " + count);
        }
        UUID tid = UUID.randomUUID();
        if (ChunkedJsonTransfer.LOGICAL_TRANSFER.equals(tid)) {
            tid = new UUID(1L, 1L);
        }
        List<TitleSnapshotS2C> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String cPart = i < catPieces.size() ? catPieces.get(i) : "";
            String pPart = i < playerPieces.size() ? playerPieces.get(i) : "";
            out.add(new TitleSnapshotS2C(cPart, pPart, tid, i, count));
        }
        return List.copyOf(out);
    }

    /** Client-side ordered reassembler for {@link TitleSnapshotS2C}. */
    public static final class Assembler {
        private UUID activeId;
        private StringBuilder catalogBuf;
        private StringBuilder playersBuf;
        private int expectedNext;
        private int totalChunks;
        private int totalUtf8Bytes;

        public synchronized void reset() {
            activeId = null;
            catalogBuf = null;
            playersBuf = null;
            expectedNext = 0;
            totalChunks = 0;
            totalUtf8Bytes = 0;
        }

        public synchronized TitleSnapshotS2C accept(TitleSnapshotS2C part) {
            if (part == null
                    || part.catalogJson() == null
                    || part.playersJson() == null
                    || part.transferId() == null
                    || part.chunkCount() < 1
                    || part.chunkCount() > ChunkedJsonTransfer.S2C_MAX_CHUNKS
                    || part.chunkIndex() < 0
                    || part.chunkIndex() >= part.chunkCount()
                    || part.catalogJson().length() > CHUNK_CHARS
                    || part.playersJson().length() > CHUNK_CHARS) {
                reset();
                return null;
            }
            int chunkBytes = ChunkedJsonTransfer.utf8Length(part.catalogJson())
                    + ChunkedJsonTransfer.utf8Length(part.playersJson());
            if (chunkBytes > ChunkedJsonTransfer.S2C_CHUNK_MAX_UTF8_BYTES) {
                reset();
                return null;
            }
            if (part.chunkIndex() == 0) {
                reset();
                if (part.chunkCount() == 1) {
                    return new TitleSnapshotS2C(part.catalogJson(), part.playersJson());
                }
                activeId = part.transferId();
                catalogBuf = new StringBuilder(part.catalogJson());
                playersBuf = new StringBuilder(part.playersJson());
                expectedNext = 1;
                totalChunks = part.chunkCount();
                totalUtf8Bytes = chunkBytes;
                return null;
            }
            if (activeId == null
                    || !activeId.equals(part.transferId())
                    || part.chunkIndex() != expectedNext
                    || part.chunkCount() != totalChunks
                    || totalUtf8Bytes + chunkBytes > ChunkedJsonTransfer.S2C_MAX_TOTAL_UTF8_BYTES) {
                reset();
                return null;
            }
            catalogBuf.append(part.catalogJson());
            playersBuf.append(part.playersJson());
            totalUtf8Bytes += chunkBytes;
            expectedNext++;
            if (expectedNext != totalChunks) {
                return null;
            }
            TitleSnapshotS2C complete = new TitleSnapshotS2C(catalogBuf.toString(), playersBuf.toString());
            reset();
            return complete;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

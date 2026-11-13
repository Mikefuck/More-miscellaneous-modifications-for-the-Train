package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.exmo.sre.record.network.RecordReplayS2CPayload;
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

/** Sends the original record JSON in order, without removing any events. */
public final class MatchRecordNetwork {
    private static final int CHARS = 65_536;
    private static final int CHUNK_BYTES = 192_000;
    private static final int MAX_CHUNKS = 512;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int LEGACY_BYTES = 900_000;

    private MatchRecordNetwork() {}

    public record RecordChunk(String matchId, String json, UUID transferId, int index, int count)
            implements CustomPacketPayload {
        public static final Type<RecordChunk> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "match_record_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RecordChunk> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.matchId, 64);
                    buf.writeUtf(p.json, CHARS);
                    buf.writeUUID(p.transferId);
                    buf.writeVarInt(p.index);
                    buf.writeVarInt(p.count);
                },
                buf -> new RecordChunk(buf.readUtf(64), buf.readUtf(CHARS), buf.readUUID(),
                        buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<RecordChunk> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(RecordChunk.TYPE, RecordChunk.CODEC);
    }

    public static List<RecordChunk> split(String matchId, String json) {
        return ChunkedJsonTransfer.split(json, CHARS, CHUNK_BYTES, MAX_CHUNKS, MAX_BYTES,
                (piece, transferId, index, count) -> new RecordChunk(matchId, piece, transferId, index, count));
    }

    public static ChunkedJsonTransfer.Assembler<String> newAssembler() {
        return new ChunkedJsonTransfer.Assembler<>(CHARS, CHUNK_BYTES, MAX_CHUNKS, MAX_BYTES,
                ChunkedJsonTransfer.TRANSFER_TIMEOUT_MS);
    }

    /** Returns false when the original upstream packet should be sent. */
    public static boolean send(ServerPlayer player, RecordReplayS2CPayload replay) {
        String json = replay.json();
        if (ServerPlayNetworking.canSend(player, RecordChunk.TYPE)) {
            List<RecordChunk> chunks;
            try {
                chunks = split(replay.matchId(), json);
            } catch (IllegalArgumentException e) {
                HabiLotteryMod.LOGGER.warn("Match record {} is too large to send", replay.matchId());
                player.sendSystemMessage(Component.translatable("screen.habitrain_lottery.records.too_large"));
                chunks = split(replay.matchId(), "");
            }
            for (RecordChunk chunk : chunks) {
                ServerPlayNetworking.send(player, chunk);
            }
            return true;
        }
        if (ChunkedJsonTransfer.utf8Length(json) <= LEGACY_BYTES) {
            return false;
        }
        player.sendSystemMessage(Component.translatableWithFallback(
                "screen.habitrain_lottery.records.upgrade_required", "请更新哈比列车抽奖补齐以查看这场较长的对局记录。"));
        ServerPlayNetworking.send(player, new RecordReplayS2CPayload(replay.matchId(), ""));
        return true;
    }
}

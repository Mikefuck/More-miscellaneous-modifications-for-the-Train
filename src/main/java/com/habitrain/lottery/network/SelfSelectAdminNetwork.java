package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.card.SelfSelectRoleConfigService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/** OP/owner editor. Every response is tied to its originating request and screen ({@code B-10}). */
public final class SelfSelectAdminNetwork {
    public static final int C2S_SAVE_MAX_CHARS = ChunkedJsonTransfer.C2S_CHUNK_CHARS;
    public static final int S2C_JSON_MAX_CHARS = ChunkedJsonTransfer.S2C_CHUNK_CHARS;

    private SelfSelectAdminNetwork() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }

    public record RequestC2S(String requestId) implements CustomPacketPayload {
        public static final Type<RequestC2S> TYPE = new Type<>(id("self_select_admin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestC2S> CODEC = StreamCodec.of(
                (b, p) -> b.writeUtf(p.requestId == null ? "" : p.requestId, 64),
                b -> new RequestC2S(b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SaveC2S(String requestId, String json, UUID transferId, int chunkIndex, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<SaveC2S> TYPE = new Type<>(id("self_select_admin_save_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveC2S> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.requestId == null ? "" : p.requestId, 64);
                    b.writeUtf(p.json == null ? "" : p.json, C2S_SAVE_MAX_CHARS);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new SaveC2S(
                        b.readUtf(64),
                        b.readUtf(C2S_SAVE_MAX_CHARS),
                        b.readUUID(),
                        b.readVarInt(),
                        b.readVarInt()));

        public SaveC2S(String requestId, String json) {
            this(requestId, json, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
        }

        public List<SaveC2S> toWireChunks() {
            String safeReq = requestId == null ? "" : requestId;
            return ChunkedJsonTransfer.splitC2S(
                    json == null ? "" : json,
                    (piece, tid, idx, total) -> new SaveC2S(safeReq, piece, tid, idx, total));
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ConfigS2C(
            String requestId,
            String json,
            String message,
            UUID transferId,
            int chunkIndex,
            int chunkCount
    ) implements CustomPacketPayload {
        public static final Type<ConfigS2C> TYPE = new Type<>(id("self_select_admin_config_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigS2C> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeUtf(p.requestId == null ? "" : p.requestId, 64);
                    b.writeUtf(p.json == null ? "{}" : p.json, S2C_JSON_MAX_CHARS);
                    b.writeUtf(p.message == null ? "" : p.message, 64);
                    b.writeUUID(p.transferId == null ? ChunkedJsonTransfer.LOGICAL_TRANSFER : p.transferId);
                    b.writeVarInt(p.chunkIndex);
                    b.writeVarInt(p.chunkCount);
                },
                b -> new ConfigS2C(
                        b.readUtf(64),
                        b.readUtf(S2C_JSON_MAX_CHARS),
                        b.readUtf(64),
                        b.readUUID(),
                        b.readVarInt(),
                        b.readVarInt()));

        public ConfigS2C(String requestId, String json, String message) {
            this(requestId, json, message, ChunkedJsonTransfer.LOGICAL_TRANSFER, 0, 1);
        }

        public List<ConfigS2C> toWireChunks() {
            String safeReq = requestId == null ? "" : requestId;
            String safeMsg = message == null ? "" : message;
            return ChunkedJsonTransfer.splitS2C(
                    json == null ? "{}" : json,
                    (piece, tid, idx, total) -> new ConfigS2C(safeReq, piece, safeMsg, tid, idx, total));
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(RequestC2S.TYPE, RequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SaveC2S.TYPE, SaveC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigS2C.TYPE, ConfigS2C.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestC2S.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            if (LotteryNetwork.rateLimited(player, "self_select_admin_request", 300)) {
                // Audit I-08: never serialize full snapshotJson() on rate-limited rejections!
                LotteryNetwork.sendIfSupported(player, new ConfigS2C(p.requestId, "{}", "rate_limited"));
                return;
            }
            send(player, p.requestId, "");
        }));
        ServerPlayNetworking.registerGlobalReceiver(SaveC2S.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            ServerPlayer player = ctx.player();
            if (player == null || player.hasDisconnected()) return;
            String safeReq = p.requestId == null ? "" : p.requestId;
            boolean authorized = AdminAuth.isAuthorizedOp(player);
            ChunkedJsonTransfer.GateResult<String> gate = ChunkedJsonTransfer.acceptAuthorizedC2S(
                    player.getUUID(),
                    "self_select_admin_save",
                    authorized,
                    () -> LotteryNetwork.rateLimited(player, "self_select_admin_save", 500),
                    p.json,
                    p.transferId,
                    p.chunkIndex,
                    p.chunkCount,
                    safeReq);
            if (gate.status() == ChunkedJsonTransfer.GateStatus.UNAUTHORIZED) {
                if (!LotteryNetwork.rateLimited(player, "self_select_admin_save_unauth", 500)) {
                    LotteryNetwork.sendIfSupported(player, new ConfigS2C(safeReq, "{}", "no_permission"));
                }
                return;
            }
            if (gate.status() == ChunkedJsonTransfer.GateStatus.RATE_LIMITED) {
                // Audit I-08: short rate-limit error without traffic amplification.
                LotteryNetwork.sendIfSupported(player, new ConfigS2C(safeReq, "{}", "rate_limited"));
                return;
            }
            if (gate.status() != ChunkedJsonTransfer.GateStatus.COMPLETED) {
                return;
            }
            String error = SelfSelectRoleConfigService.apply(gate.json());
            send(player, gate.metadata(), error.isEmpty() ? "saved" : error);
            if (error.isEmpty()) HabiLotteryMod.LOGGER.info("{} saved self-select role restrictions",
                    player.getGameProfile().getName());
        }));
    }

    private static void send(ServerPlayer player, String requestId, String message) {
        if (player == null || player.hasDisconnected()) return;
        if (!AdminAuth.isAuthorizedOp(player)) {
            LotteryNetwork.sendIfSupported(player, new ConfigS2C(requestId, "{}", "no_permission"));
            return;
        }
        String error = SelfSelectRoleConfigService.loadError();
        LotteryNetwork.sendIfSupported(player, new ConfigS2C(
                requestId,
                SelfSelectRoleConfigService.snapshotJson(),
                message.isEmpty() ? error : message));
    }
}

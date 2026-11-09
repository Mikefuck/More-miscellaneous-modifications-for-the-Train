package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayerCardModifyC2S(
        String targetUuid,
        String questKey,
        String operation,
        int value
) implements CustomPacketPayload {
    public static final Type<PlayerCardModifyC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "player_card_mod"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCardModifyC2S> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.targetUuid == null ? "" : payload.targetUuid, 64);
                buf.writeUtf(payload.questKey == null ? "" : payload.questKey, 32);
                buf.writeUtf(payload.operation == null ? "" : payload.operation, 8);
                buf.writeVarInt(payload.value);
            },
            buf -> new PlayerCardModifyC2S(
                    buf.readUtf(64),
                    buf.readUtf(32),
                    buf.readUtf(8),
                    buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

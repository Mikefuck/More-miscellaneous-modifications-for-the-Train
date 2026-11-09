package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Optimistic-locked player title modification C2S payload ({@code I-10}).
 */
public record TitlePlayerModifyC2S(
        String mode,
        String targetUuid,
        String payload,
        long expectedRevision
) implements CustomPacketPayload {
    public static final Type<TitlePlayerModifyC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "title_player_mod_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TitlePlayerModifyC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.mode == null ? "" : value.mode, 64);
                buf.writeUtf(value.targetUuid == null ? "" : value.targetUuid, 64);
                buf.writeUtf(value.payload == null ? "" : value.payload, 1024);
                buf.writeVarLong(value.expectedRevision);
            },
            buf -> new TitlePlayerModifyC2S(
                    buf.readUtf(64),
                    buf.readUtf(64),
                    buf.readUtf(1024),
                    buf.readVarLong())
    );

    public TitlePlayerModifyC2S(String mode, String targetUuid, String payload) {
        this(mode, targetUuid, payload, 0L);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

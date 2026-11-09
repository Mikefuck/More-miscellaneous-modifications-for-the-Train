package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayerGreenApplesModifyC2S(String mode, String targetUuid, int value)
        implements CustomPacketPayload {
    public static final Type<PlayerGreenApplesModifyC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "player_green_apples_mod"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerGreenApplesModifyC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.mode == null ? "" : value.mode, 64);
                buf.writeUtf(value.targetUuid == null ? "" : value.targetUuid, 64);
                buf.writeVarInt(value.value);
            },
            buf -> new PlayerGreenApplesModifyC2S(buf.readUtf(64), buf.readUtf(64), buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

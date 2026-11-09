package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpStatusS2C(boolean op) implements CustomPacketPayload {
    public static final Type<OpStatusS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "op_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpStatusS2C> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeBoolean(value.op),
            buf -> new OpStatusS2C(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

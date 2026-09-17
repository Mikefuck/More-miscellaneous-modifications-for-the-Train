package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C open OP mail compose UI. */
public record OpenMailComposeS2C() implements CustomPacketPayload {
    public static final OpenMailComposeS2C INSTANCE = new OpenMailComposeS2C();
    public static final Type<OpenMailComposeS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "open_mail_compose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMailComposeS2C> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S request a fresh mailbox list snapshot. */
public record MailboxRequestC2S() implements CustomPacketPayload {
    public static final MailboxRequestC2S INSTANCE = new MailboxRequestC2S();
    public static final Type<MailboxRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "mailbox_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MailboxRequestC2S> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

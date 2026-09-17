package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C open the player-facing mailbox UI. */
public record OpenMailboxS2C() implements CustomPacketPayload {
    public static final OpenMailboxS2C INSTANCE = new OpenMailboxS2C();
    public static final Type<OpenMailboxS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "open_mailbox"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMailboxS2C> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

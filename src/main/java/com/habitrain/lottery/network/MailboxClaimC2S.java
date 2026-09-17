package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S claim a single mailbox mail by id. */
public record MailboxClaimC2S(String mailId) implements CustomPacketPayload {
    public static final Type<MailboxClaimC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "mailbox_claim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MailboxClaimC2S> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.mailId == null ? "" : value.mailId, 64),
            buf -> new MailboxClaimC2S(buf.readUtf(64))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C mailbox list snapshot (JSON of {@code LocalMailboxStore.MailJson} list). */
public record MailboxListS2C(String json) implements CustomPacketPayload {
    public static final Type<MailboxListS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "mailbox_list"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MailboxListS2C> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.json == null ? "" : value.json, 1_000_000),
            buf -> new MailboxListS2C(buf.readUtf(1_000_000))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

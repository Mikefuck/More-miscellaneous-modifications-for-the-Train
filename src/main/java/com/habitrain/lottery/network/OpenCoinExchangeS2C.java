package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** An authoritative quote for the quantity selection screen; purchases are revalidated. */
public record OpenCoinExchangeS2C(int coins, int draws, int price) implements CustomPacketPayload {
    public static final Type<OpenCoinExchangeS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "open_coin_exchange"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCoinExchangeS2C> CODEC =
            new StreamCodec<>() {
                public OpenCoinExchangeS2C decode(RegistryFriendlyByteBuf buf) {
                    return new OpenCoinExchangeS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                }
                public void encode(RegistryFriendlyByteBuf buf, OpenCoinExchangeS2C value) {
                    buf.writeVarInt(value.coins());
                    buf.writeVarInt(value.draws());
                    buf.writeVarInt(value.price());
                }
            };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

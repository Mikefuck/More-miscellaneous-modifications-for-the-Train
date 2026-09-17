package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a faction-card row was clicked in the backpack GUI. The server
 * answers with {@link CardUseMenuS2C} to open the use menu, or falls back to the
 * plain direct-use path when the menu is unavailable.
 */
public record CardUseRequestC2S(String questKey) implements CustomPacketPayload {

    public static final Type<CardUseRequestC2S> TYPE = new Type<>(id("card_use_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CardUseRequestC2S> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.questKey == null ? "" : value.questKey, 32),
            buf -> new CardUseRequestC2S(buf.readUtf(32))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }
}

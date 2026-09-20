package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player picked an option in the card use menu.
 *
 * @param questKey card type quest key (killer/civilian/neutral/neutral_for_killer), or
 *                 {@code self_select} for an exact-role pick, or {@code limit_break} for the
 *                 bonus-daily-use card
 * @param mode     "direct" → consume one card via the upstream path;
 *                 "self" → consume {@code CardUseService.SELF_SELECT_COST} (= 1) self-select card
 *                 and force the exact role;
 *                 "bonus" → consume one limit-break card for one extra faction-card use today
 * @param roleId   role identifier chosen for {@code mode="self"}; empty otherwise
 */
public record CardUseConfirmC2S(String questKey, String mode, String roleId) implements CustomPacketPayload {

    public static final Type<CardUseConfirmC2S> TYPE = new Type<>(id("card_use_confirm"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CardUseConfirmC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.questKey == null ? "" : value.questKey, 32);
                buf.writeUtf(value.mode == null ? "" : value.mode, 16);
                buf.writeUtf(value.roleId == null ? "" : value.roleId, 256);
            },
            buf -> new CardUseConfirmC2S(buf.readUtf(32), buf.readUtf(16), buf.readUtf(256))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }
}

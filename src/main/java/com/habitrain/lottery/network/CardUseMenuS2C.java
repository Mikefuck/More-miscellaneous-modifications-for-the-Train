package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: open the card use menu for the clicked faction card.
 *
 * @param questKey      clicked card type quest key
 * @param remainingUses daily uses left for this player
 * @param candidatesJson JSON array of selectable roles for the card's faction:
 *                       {@code [{"id":"...","name":"...","color":int,"bound":"..."}]}
 */
public record CardUseMenuS2C(String questKey, int remainingUses, String candidatesJson, String balancesJson, int remainingSelfUses) implements CustomPacketPayload {

    public static final int CANDIDATES_JSON_MAX_CHARS = LotteryNetwork.ADMIN_S2C_JSON_MAX_CHARS;
    public static final Type<CardUseMenuS2C> TYPE = new Type<>(id("card_use_menu_v2"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CardUseMenuS2C> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.questKey == null ? "" : value.questKey, 32);
                buf.writeVarInt(value.remainingUses);
                buf.writeUtf(value.balancesJson == null ? "{}" : value.balancesJson, 4096);
                buf.writeVarInt(value.remainingSelfUses);
                buf.writeUtf(value.candidatesJson == null ? "[]" : value.candidatesJson, CANDIDATES_JSON_MAX_CHARS);
            },
            buf -> {
                String key = buf.readUtf(32);
                int remaining = buf.readVarInt();
                String balances = buf.readUtf(4096);
                int selfRemaining = buf.readVarInt();
                return new CardUseMenuS2C(key, remaining, buf.readUtf(CANDIDATES_JSON_MAX_CHARS), balances, selfRemaining);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, path);
    }
}

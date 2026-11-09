package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BackupDataS2C(String json) implements CustomPacketPayload {
    public static final Type<BackupDataS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "backup_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BackupDataS2C> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value.json == null ? "" : value.json, LotteryNetwork.ADMIN_S2C_JSON_MAX_CHARS),
            buf -> new BackupDataS2C(buf.readUtf(LotteryNetwork.ADMIN_S2C_JSON_MAX_CHARS))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

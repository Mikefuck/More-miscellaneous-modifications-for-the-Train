package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record LoginStateS2C(
        long epochDay,
        int year,
        int month,
        int dayOfMonth,
        int streak,
        int loginDaysMask,
        String resetZoneId
) implements CustomPacketPayload {
    public static final Type<LoginStateS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "login_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LoginStateS2C> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeLong(value.epochDay);
                buf.writeVarInt(value.year);
                buf.writeVarInt(value.month);
                buf.writeVarInt(value.dayOfMonth);
                buf.writeVarInt(value.streak);
                buf.writeInt(value.loginDaysMask);
                buf.writeUtf(value.resetZoneId == null || value.resetZoneId.isBlank() ? "UTC" : value.resetZoneId, 64);
            },
            buf -> new LoginStateS2C(
                    buf.readLong(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readInt(),
                    buf.readableBytes() > 0 ? buf.readUtf(64) : "UTC"
            )
    );

    public LoginStateS2C(long epochDay, int year, int month, int dayOfMonth, int streak, int loginDaysMask) {
        this(epochDay, year, month, dayOfMonth, streak, loginDaysMask, "UTC");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

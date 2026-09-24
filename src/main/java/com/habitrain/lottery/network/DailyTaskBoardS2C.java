package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Snapshot plus an optional request to open the board. */
public record DailyTaskBoardS2C(String json, boolean open) implements CustomPacketPayload {
    public static final Type<DailyTaskBoardS2C> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "daily_task_board"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DailyTaskBoardS2C> CODEC = new StreamCodec<>() {
        public DailyTaskBoardS2C decode(RegistryFriendlyByteBuf buf) {
            return new DailyTaskBoardS2C(buf.readUtf(32767), buf.readBoolean());
        }
        public void encode(RegistryFriendlyByteBuf buf, DailyTaskBoardS2C value) {
            buf.writeUtf(value.json(), 32767);
            buf.writeBoolean(value.open());
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DailyTaskRequestC2S() implements CustomPacketPayload {
    public static final DailyTaskRequestC2S INSTANCE = new DailyTaskRequestC2S();
    public static final Type<DailyTaskRequestC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "daily_task_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DailyTaskRequestC2S> CODEC = new StreamCodec<>() {
        public DailyTaskRequestC2S decode(RegistryFriendlyByteBuf buf) { return INSTANCE; }
        public void encode(RegistryFriendlyByteBuf buf, DailyTaskRequestC2S value) { }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

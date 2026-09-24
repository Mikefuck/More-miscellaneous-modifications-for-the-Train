package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DailyTaskClaimC2S(String taskId) implements CustomPacketPayload {
    public static final Type<DailyTaskClaimC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "daily_task_claim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DailyTaskClaimC2S> CODEC = new StreamCodec<>() {
        public DailyTaskClaimC2S decode(RegistryFriendlyByteBuf buf) { return new DailyTaskClaimC2S(buf.readUtf(256)); }
        public void encode(RegistryFriendlyByteBuf buf, DailyTaskClaimC2S value) { buf.writeUtf(value.taskId(), 256); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

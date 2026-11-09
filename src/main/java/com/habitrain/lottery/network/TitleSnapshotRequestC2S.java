package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TitleSnapshotRequestC2S() implements CustomPacketPayload {
    public static final TitleSnapshotRequestC2S INSTANCE = new TitleSnapshotRequestC2S();
    public static final Type<TitleSnapshotRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "title_snapshot_req"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TitleSnapshotRequestC2S> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

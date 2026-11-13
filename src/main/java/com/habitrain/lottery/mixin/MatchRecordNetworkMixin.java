package com.habitrain.lottery.mixin;

import com.habitrain.lottery.network.MatchRecordNetwork;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.exmo.sre.record.MatchRecordService;
import net.exmo.sre.record.network.RecordReplayS2CPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MatchRecordService.class, remap = false)
public class MatchRecordNetworkMixin {
    @WrapOperation(method = "lambda$openReplayFor$3", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking;send(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"))
    private static void habi$sendCompleteRecord(ServerPlayer player, CustomPacketPayload payload, Operation<Void> original) {
        if (!(payload instanceof RecordReplayS2CPayload replay) || !MatchRecordNetwork.send(player, replay)) {
            original.call(player, payload);
        }
    }
}

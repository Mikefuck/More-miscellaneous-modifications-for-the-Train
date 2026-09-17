package com.habitrain.lottery.mixin;

import com.habitrain.lottery.card.CardUseService;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Upstream failure refunds share the same one-use receipt as final settlement. */
@Mixin(value = ProgressionDataManager.class, remap = false)
public class ProgressionDataManagerMixin {
    @Inject(
            method = "addFactionCard(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/progression/ProgressionState$FactionCardType;I)V",
            at = @At("HEAD"), cancellable = true)
    private static void habi$refundOnce(ServerPlayer player, FactionCardType type, int count, CallbackInfo ci) {
        if (player == null || type == null || type == FactionCardType.NONE || count <= 0) return;
        // This upstream entry is only used for assignment failures. Rewards use
        // BackpackManager.addCard directly. Ignore streak forces with no paid card.
        ci.cancel();
        CardUseService.refundForcedCard(player.getUUID());
    }
}

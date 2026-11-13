package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures minigame block task completions and coin rewards.
 */
@Mixin(value = SREPlayerMinigameTaskComponent.class, remap = false)
public class MinigameTaskRecordMixin {

    @Inject(method = "onMinigameBlockCompleted", at = @At("HEAD"))
    private void habi$beginMinigameScope(
            ServerPlayer sp,
            BlockPos pos,
            int reward,
            String blockMinigameId,
            CallbackInfoReturnable<Boolean> cir
    ) {
        MatchDetailRecorder.beginMinigameTaskScope(sp, blockMinigameId);
    }

    @Inject(method = "onMinigameBlockCompleted", at = @At("RETURN"))
    private void habi$endMinigameScope(
            ServerPlayer sp,
            BlockPos pos,
            int reward,
            String blockMinigameId,
            CallbackInfoReturnable<Boolean> cir
    ) {
        MatchDetailRecorder.endMinigameTaskScope(sp, blockMinigameId, cir.getReturnValueZ());
    }
}

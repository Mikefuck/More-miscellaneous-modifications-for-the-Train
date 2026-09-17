package com.habitrain.lottery.mixin;

import com.habitrain.lottery.grant.LotteryGrantService;
import com.habitrain.lottery.storage.LotteryHistoryStore;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.utils.lottery.LotteryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LotteryManager.class, remap = false)
public class LotteryManagerMixin {

    @Inject(method = "canRoll", at = @At("HEAD"), cancellable = true)
    private void habi$canRollUsesDrawCost(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            return;
        }
        cir.setReturnValue(player != null
                && PlayerEconomyManager.getLootChance(player) >= LotteryGrantService.computeDrawCost());
    }

    @ModifyVariable(method = "addOrDegreeLotteryChance", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int habi$scaleChanceDelta(int amount) {
        return LotteryGrantService.scaleChanceDelta(
                amount,
                PlayerLotteryStore.get().isTakeoverActive(),
                LotteryGrantService.computeDrawCost());
    }

    @Inject(method = "addOrDegreeLotteryChance", at = @At("TAIL"))
    private void habi$afterChanceChange(ServerPlayer player, int amount, CallbackInfo ci) {
        if (player == null
                || !PlayerLotteryStore.get().isTakeoverActive()
                || PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
            return;
        }
        PlayerLotteryStore.get().flush(player.getUUID());
        if (amount < 0) {
            LotteryHistoryStore.get().append(
                    player.getUUID(),
                    -1,
                    -1,
                    "",
                    "chance_delta",
                    0,
                    PlayerLotteryStore.get().getLootChance(player.getUUID())
            );
        }
    }
}

package com.habitrain.lottery.mixin;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disable SRE PlayerEconomyManager MySQL sync while lottery takeover is active
 * so world JSON under {@code {world}/habitrain_lottery/players/} is the only durable store.
 * When takeover is off, return without cancel so SRE MySQL stays writable.
 */
@Mixin(value = PlayerEconomyManager.class, remap = false)
public class PlayerEconomyMysqlMixin {

    @Inject(method = "isDatabaseEnabled", at = @At("HEAD"), cancellable = true)
    private static void habi$noMysql(CallbackInfoReturnable<Boolean> cir) {
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            return;
        }
        cir.setReturnValue(false);
    }
}

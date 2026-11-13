package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Attributes periodic passive coins granted during {@link SREMurderGameMode#tickServerGameLoop} to natural economy.
 */
@Mixin(value = SREMurderGameMode.class, remap = false)
public class NaturalEconomyRecordMixin {

    @WrapOperation(
            method = "tickServerGameLoop",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/cca/SREPlayerShopComponent;addToBalance(I)V"
            )
    )
    private void habi$attributeNaturalIncome(
            SREPlayerShopComponent shop,
            int amount,
            Operation<Void> original
    ) {
        MatchDetailRecorder.pushNaturalEconomy();
        try {
            original.call(shop, amount);
        } finally {
            MatchDetailRecorder.popReason();
        }
    }
}

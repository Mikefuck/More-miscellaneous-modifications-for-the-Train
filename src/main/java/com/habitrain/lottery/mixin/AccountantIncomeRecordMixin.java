package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import org.agmas.noellesroles.game.roles.innocence.accountant.AccountantPlayerComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes Accountant passive income ticks.
 */
@Mixin(value = AccountantPlayerComponent.class, remap = false)
public class AccountantIncomeRecordMixin {

    @Inject(method = "givePassiveIncome", at = @At("HEAD"))
    private void habi$pushAccountantIncome(CallbackInfo ci) {
        MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.PASSIVE_ACCOUNTANT);
    }

    @Inject(method = "givePassiveIncome", at = @At("RETURN"))
    private void habi$popAccountantIncome(CallbackInfo ci) {
        MatchDetailRecorder.popReason();
    }
}

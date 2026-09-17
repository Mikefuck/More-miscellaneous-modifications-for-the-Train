package com.habitrain.lottery.mixin;

import com.habitrain.lottery.backpack.FactionCardRoleTypes;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Corrects SRE faction-card type ids so activated cards force the intended faction next game.
 * See {@link FactionCardRoleTypes}.
 */
@Mixin(value = FactionCardType.class, remap = false)
public class FactionCardTypeRoleIdMixin {

    @Inject(method = "getTypeId", at = @At("HEAD"), cancellable = true)
    private void habi$correctTypeId(CallbackInfoReturnable<Integer> cir) {
        FactionCardType self = (FactionCardType) (Object) this;
        cir.setReturnValue(FactionCardRoleTypes.roleTypeId(self));
    }

    @Inject(method = "fromInt", at = @At("HEAD"), cancellable = true)
    private static void habi$correctFromInt(int typeValue, CallbackInfoReturnable<FactionCardType> cir) {
        cir.setReturnValue(FactionCardRoleTypes.fromRoleTypeId(typeValue));
    }
}

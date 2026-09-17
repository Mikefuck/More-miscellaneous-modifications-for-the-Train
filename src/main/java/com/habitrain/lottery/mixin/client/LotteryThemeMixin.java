package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.theme.LootThemeHooks;
import net.minecraft.resources.ResourceLocation;
import org.agmas.noellesroles.utils.lottery.LotteryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LotteryManager.class, remap = false)
public class LotteryThemeMixin {

    @Inject(method = "getQualityBgResourceLocation", at = @At("HEAD"), cancellable = true)
    private static void habi$themeBg(int quality, CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation rl = LootThemeHooks.qualityBackground(quality);
        if (rl != null) {
            cir.setReturnValue(rl);
        }
    }
}

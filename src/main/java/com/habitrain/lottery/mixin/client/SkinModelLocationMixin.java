package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import io.wifi.starrailexpress.client.model.GeneralModelLoadingPlugin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets API skins load item models from the contributing mod's namespace. */
@Mixin(value = GeneralModelLoadingPlugin.class, remap = false)
public class SkinModelLocationMixin {

    @Inject(method = "getModelLocation", at = @At("HEAD"), cancellable = true)
    private static void habi$apiModelLocation(
            String itemType,
            String skin,
            GeneralModelLoadingPlugin.Variant variant,
            CallbackInfoReturnable<ResourceLocation> cir) {
        boolean inHand = variant == GeneralModelLoadingPlugin.Variant.IN_HAND;
        HabiSkinApi.model(itemType, skin, inHand).ifPresent(cir::setReturnValue);
    }
}

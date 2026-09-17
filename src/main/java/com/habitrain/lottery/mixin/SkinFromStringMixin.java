package com.habitrain.lottery.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import io.wifi.starrailexpress.util.ItemSkinManager;

/**
 * SRE {@code Skin.fromString(type, name)} calls {@code name.toLowerCase()} without a null check.
 * Skin menu preview items often have no SKIN component / equipped skin → name=null → crash.
 */
@Mixin(value = ItemSkinManager.Skin.class, remap = false)
public class SkinFromStringMixin {

    @ModifyVariable(method = "fromString", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static String habi$nullSafeSkinName(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        return name;
    }
}

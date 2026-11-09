package com.habitrain.lottery.mixin;

import com.habitrain.lottery.bridge.SkinItemPolicy;
import io.wifi.starrailexpress.content.item.SkinableItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Stops the upstream inventory tick from assigning a revolver skin or skin tooltip to a Derringer. */
@Mixin(SkinableItem.class)
public abstract class DerringerSkinExclusionMixin {
    @Inject(method = "inventoryTick", at = @At("HEAD"), cancellable = true)
    private void habi$excludeDerringer(ItemStack stack, Level level, Entity entity,
                                     int slot, boolean selected, CallbackInfo ci) {
        if (SkinItemPolicy.excludesSkin(stack)) {
            SkinItemPolicy.clearSkin(stack);
            ci.cancel();
        }
    }

    @Inject(method = "appendHoverText", at = @At("HEAD"), cancellable = true, require = 0)
    private void habi$suppressDerringerSkinTooltip(ItemStack stack, Item.TooltipContext context,
                                                   List<Component> tooltipComponents, TooltipFlag tooltipFlag,
                                                   CallbackInfo ci) {
        if (SkinItemPolicy.excludesSkin(stack)) {
            ci.cancel();
        }
    }
}

package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.WarehouseHoldKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 把背包键的点击交给 {@link WarehouseHoldKey} 判定「轻按开背包 / 长按开仓库」。
 *
 * <p>只包住 {@code keyInventory.consumeClick()}，打开背包的 {@code setScreen} 原样保留，
 * 因此上游把背包换成受限背包的 WrapOperation 仍然生效。</p>
 */
@Mixin(Minecraft.class)
public abstract class WarehouseHoldKeyMixin {
    @Shadow @Final public Options options;

    @WrapOperation(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
    private boolean habi$holdInventoryForWarehouse(KeyMapping key, Operation<Boolean> original) {
        if (options == null || key != options.keyInventory) return original.call(key);
        return WarehouseHoldKey.filterInventoryClick(() -> original.call(key));
    }
}

package com.habitrain.lottery.mixin;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import io.wifi.starrailexpress.content.item.SkinableItem;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-authoritative inventory tick adjustment for SkinableItem.
 * Does not cancel upstream inventoryTick and never modifies ItemStack on client side.
 */
@Mixin(value = SkinableItem.class, remap = false)
public abstract class SkinableItemMixin {

    // The dependency JAR is intermediary-remapped in production (method_7888)
    // but exposed as inventoryTick in the named development runtime. Keep both
    // selectors because this project intentionally has no generated refmap.
    @Inject(method = {"inventoryTick", "method_7888"}, at = @At("TAIL"), remap = false)
    private void habi$applyServerAuthoritativeSkin(
            ItemStack stack, Level level, Entity entity, int slot, boolean selected,
            CallbackInfo ci) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer player) || stack == null || stack.isEmpty()) {
            return;
        }
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            return;
        }

        try {
            SkinableItem self = (SkinableItem) (Object) this;
            String rawType = self.getItemSkinType();
            String canonical = SkinTypeKeys.canonical(rawType);
            String equipped = PlayerLotteryStore.get().getEquipped(player.getUUID(), canonical);
            String wanted = (equipped == null || equipped.isBlank()) ? "default" : equipped;
            String current = stack.get(SREDataComponentTypes.SKIN);
            if (!wanted.equals(current)) {
                stack.set(SREDataComponentTypes.SKIN, wanted);
            }
        } catch (Throwable ignored) {
        }
    }
}

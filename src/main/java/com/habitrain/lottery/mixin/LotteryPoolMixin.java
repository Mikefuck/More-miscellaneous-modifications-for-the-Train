package com.habitrain.lottery.mixin;

import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.skin.SkinLotteryRewards;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.agmas.noellesroles.utils.Pair;
import org.agmas.noellesroles.utils.lottery.LotteryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lottery adapter: no upstream skin registry, item classes or skin rewards. */
@Mixin(value = LotteryManager.LotteryPool.class, remap = false)
public class LotteryPoolMixin {
    @Inject(method = "rollOnce", at = @At("HEAD"), cancellable = true)
    private void habi$localReward(ServerPlayer player, CallbackInfoReturnable<Pair<Integer, Integer>> cir) {
        cir.setReturnValue(com.habitrain.lottery.bridge.SkinLotteryBridge.roll(
                (LotteryManager.LotteryPool) (Object) this, player));
    }
    @Inject(method = "getSkinItemStack", at = @At("HEAD"), cancellable = true)
    private static void habi$localPreview(String entry, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack preview = SkinItems.preview(entry);
        cir.setReturnValue(preview.isEmpty() ? null : preview);
    }
}

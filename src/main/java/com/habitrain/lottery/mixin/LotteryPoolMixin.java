package com.habitrain.lottery.mixin;

import com.habitrain.lottery.grant.LotteryGrantService;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Scales only the duplicate-skin coin grant inside {@code LotteryPool.rollOnce}.
 * ordinal 0 = pure coin card (648 * pct * 1.1); ordinal 1 = duplicate skin (648 * pct).
 */
@Mixin(targets = "org.agmas.noellesroles.utils.lottery.LotteryManager$LotteryPool", remap = false)
public class LotteryPoolMixin {

    @Redirect(
            method = "rollOnce",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/util/ItemSkinManager;addCoinNum(Lnet/minecraft/world/entity/player/Player;Ljava/lang/Integer;)V",
                    ordinal = 1
            )
    )
    private void habi$scaleDuplicateCoin(Player player, Integer amount) {
        int base = amount == null ? 0 : amount;
        int scaled = LotteryGrantService.applyDuplicateCoin(base);
        io.wifi.starrailexpress.util.ItemSkinManager.addCoinNum(player, scaled);
    }
}

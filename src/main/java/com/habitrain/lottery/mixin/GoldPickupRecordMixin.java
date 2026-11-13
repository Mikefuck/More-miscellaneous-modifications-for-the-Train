package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.cca.MurderTimeEventComponent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Attributes murder gold item pickups to {@link MatchDetailRecorder.CoinReason#GOLD_PICKUP}.
 */
@Mixin(ItemEntity.class)
public abstract class GoldPickupRecordMixin {

    @Shadow
    public abstract ItemStack getItem();

    @WrapMethod(method = "playerTouch")
    private void habi$trackGoldPickup(Player player, Operation<Void> original) {
        boolean pushed = false;
        if (player != null && !player.level().isClientSide
                && MurderTimeEventComponent.getMurderGoldAmount(this.getItem()) > 0) {
            MatchDetailRecorder.pushReason(MatchDetailRecorder.CoinReason.GOLD_PICKUP);
            pushed = true;
        }
        try {
            original.call(player);
        } finally {
            if (pushed) {
                MatchDetailRecorder.popReason();
            }
        }
    }
}

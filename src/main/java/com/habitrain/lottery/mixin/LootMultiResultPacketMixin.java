package com.habitrain.lottery.mixin;

import com.habitrain.lottery.grant.LootBatchPolicy;
import org.agmas.noellesroles.packet.Loot.LootMultiResultS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = LootMultiResultS2CPacket.class, remap = false)
public abstract class LootMultiResultPacketMixin {
    @ModifyConstant(method = "read", constant = @Constant(intValue = 10), require = 1, allow = 1)
    private static int habi$batchResultLimit(int original) {
        return LootBatchPolicy.MAX_ROLLS;
    }
}

package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.PagedLootMultiScreen;
import org.agmas.noellesroles.client.screen.LootMultiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = LootMultiScreen.class, remap = false)
public abstract class LootBatchTitleMixin {
    @ModifyArg(method = "renderTitle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
            ordinal = 0, remap = true), index = 0)
    private String habi$batchTitle(String original) {
        return (Object) this instanceof PagedLootMultiScreen screen ? screen.batchHeading() : original;
    }
}

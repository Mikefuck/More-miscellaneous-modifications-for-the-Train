package com.habitrain.lottery.mixin;

import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.exmo.sre.sync.EquippedSkinsDatabaseSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses upstream {@link EquippedSkinsDatabaseSync} writes to the remote
 * {@code equipped_skins} MySQL partition while world JSON takeover is active ({@code I-16}).
 */
@Mixin(value = EquippedSkinsDatabaseSync.class, remap = false)
public abstract class EquippedSkinsDatabaseSyncMixin {

    @Inject(method = "isUploadEnabled()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void habi$disableUploadFlag(CallbackInfoReturnable<Boolean> cir) {
        if (PlayerLotteryStore.get().isTakeoverActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "queueUpload(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void habi$skipQueueUpload(Player player, CallbackInfo ci) {
        if (PlayerLotteryStore.get().isTakeoverActive()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "flushAllBlocking(Lnet/minecraft/server/MinecraftServer;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void habi$skipFlushAllBlocking(MinecraftServer server, CallbackInfo ci) {
        if (PlayerLotteryStore.get().isTakeoverActive()) {
            ci.cancel();
        }
    }
}

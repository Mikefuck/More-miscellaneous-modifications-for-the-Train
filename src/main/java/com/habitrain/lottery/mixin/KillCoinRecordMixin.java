package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.MatchDetailRecorder;
import io.wifi.starrailexpress.api.GameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes kill/death reward coins triggered inside {@link GameMode#killPlayer}.
 */
@Mixin(value = GameMode.class, remap = false)
public class KillCoinRecordMixin {

    @Inject(method = "killPlayer", at = @At("HEAD"))
    private void habi$beginKillScope(
            Player victim,
            boolean spawnBody,
            @Nullable Player killer,
            ResourceLocation deathReason,
            boolean forceDeath,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.beginKillScope(victim, killer);
    }

    @Inject(method = "killPlayer", at = @At("RETURN"))
    private void habi$endKillScope(
            Player victim,
            boolean spawnBody,
            @Nullable Player killer,
            ResourceLocation deathReason,
            boolean forceDeath,
            CallbackInfo ci
    ) {
        MatchDetailRecorder.endKillScope(victim, killer);
    }
}

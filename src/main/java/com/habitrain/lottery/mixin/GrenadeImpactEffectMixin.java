package com.habitrain.lottery.mixin;

import com.habitrain.lottery.bridge.SkinEffectRuntime;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The built-in trigger of the skin-effects API, for the plain grenade.
 *
 * <p>Two injections into {@code GrenadeEntity#onHit}, in this order:</p>
 * <ol>
 *   <li>{@code HEAD} — dispatch the skin's impact handler. The handler runs before the
 *       vanilla burst, so it can be self-contained and no ordering race exists between
 *       "our effect" and "the vanilla particles we are about to drop".</li>
 *   <li>every {@code ServerLevel.sendParticles} call in the method — for a projectile
 *       whose skin registered an impact handler, return without sending, which removes the
 *       big-explosion, smoke and item-debris particles of the vanilla burst and leaves the
 *       effect in sole ownership of the visuals. Damage, kills, kill rewards, cooldowns and
 *       the explosion sound are untouched.</li>
 * </ol>
 *
 * <p>Deliberately scoped to {@code GrenadeEntity}: sticky and timed grenades keep their
 * vanilla explosion visuals. {@code onHit} also runs on the client for prediction, but
 * both injections are inert there — the dispatch requires a {@link ServerLevel} and the
 * vanilla burst is itself server-gated.</p>
 */
@Mixin(targets = "io.wifi.starrailexpress.content.entity.GrenadeEntity")
public abstract class GrenadeImpactEffectMixin {

    @Inject(method = "onHit", at = @At("HEAD"))
    private void habitrain$dispatchSkinImpact(HitResult hitResult, CallbackInfo callbackInfo) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel level) {
            SkinEffectRuntime.dispatchImpact(level, self);
        }
    }

    @Redirect(method = "onHit", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;sendParticles("
                    + "Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private int habitrain$replaceVanillaBurst(ServerLevel level, ParticleOptions particle,
            double x, double y, double z, int count,
            double xOffset, double yOffset, double zOffset, double speed) {
        if (SkinEffectRuntime.replacesVanillaBurst((Entity) (Object) this)) {
            return 0;
        }
        return level.sendParticles(particle, x, y, z, count, xOffset, yOffset, zOffset, speed);
    }
}

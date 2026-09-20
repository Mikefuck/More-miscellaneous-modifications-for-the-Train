package com.habitrain.lottery.api.skin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side flight trail for a skinned projectile.
 *
 * <p>Called once per client tick for every projectile that visually carries the skin
 * (i.e. the same condition that makes the projectile render the skin's model). Spawn
 * your particles with {@code level.addParticle(...)}.</p>
 *
 * <p><b>Dedicated-server safety.</b> {@link Level} is the parameter type on purpose:
 * this interface is loaded on both sides, so a handler must not reference client-only
 * types in its own signature. Test the level and delegate to a client-only helper:</p>
 *
 * <pre>{@code
 * SkinEffects.registerTrail("grenade", "example_black_hole",
 *         (level, projectile, position, partialTick) -> MyClientTrail.spawn(level, position, partialTick));
 * }</pre>
 *
 * <p>Registration is safe on both sides; only the client calls it.</p>
 */
@FunctionalInterface
public interface SkinTrailHandler {

    /**
     * @param level       the level being rendered; a physical client always passes a
     *                    {@code ClientLevel}, but the parameter type stays {@link Level}
     *                    so this interface can be loaded on a dedicated server
     * @param projectile  the flying projectile
     * @param position    projectile position interpolated by {@code partialTick}
     * @param partialTick current frame's partial tick
     */
    void onTrail(Level level, Entity projectile, Vec3 position, float partialTick);
}

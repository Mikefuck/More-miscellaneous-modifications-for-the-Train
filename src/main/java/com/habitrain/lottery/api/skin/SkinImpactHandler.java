package com.habitrain.lottery.api.skin;

/**
 * Server-side reaction to a skinned projectile's impact (currently: a skinned
 * grenade exploding).
 *
 * <p><b>Contract.</b> The handler runs on the server thread, once, at the explosion
 * position, and it owns the visuals of that explosion: while a handler is registered
 * for the skin the projectile actually carries, the built-in grenade burst
 * (big explosion + smoke + item debris particles) is suppressed, and the handler is
 * expected to spawn its own particles/sounds. Damage, kills, kill rewards, cooldowns
 * and the explosion sound are <em>not</em> affected — this is a visual hook only.</p>
 *
 * <p>Keep it cheap: it runs inline in the explosion tick. Use
 * {@link SkinImpactContext#schedule(int, Runnable)} for anything that should happen
 * later (a second sound, a fading shockwave, ...) instead of blocking.</p>
 *
 * <p>Register with {@link SkinEffects#registerImpact(String, String, SkinImpactHandler)}.</p>
 */
@FunctionalInterface
public interface SkinImpactHandler {

    /** Called once per impacted projectile that carries this skin. */
    void onImpact(SkinImpactContext context);
}

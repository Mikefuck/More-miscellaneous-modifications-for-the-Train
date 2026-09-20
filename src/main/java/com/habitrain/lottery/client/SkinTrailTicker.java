package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.SkinEffects;
import com.habitrain.lottery.api.skin.SkinItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Drives {@link SkinEffects} flight trails: once per client tick it walks the entities
 * being rendered, keeps the ones whose synced item stack carries a skin with a trail
 * handler, and hands each of them its interpolated position.
 *
 * <p>Two properties keep this off the hot path in practice:</p>
 * <ul>
 *   <li>when no extension registered a trail at all (the common case, and every
 *       existing v2 extension), the whole scan is skipped by one map check;</li>
 *   <li>the scan itself is the vanilla render-entity walk — no reflection, no new
 *       tracking state, nothing to leak between worlds or respawns.</li>
 * </ul>
 *
 * <p>Trails are spawned locally by each client, so they cost no bandwidth and stay
 * perfectly smooth: no packet timing can make them stutter.</p>
 */
public final class SkinTrailTicker {

    private SkinTrailTicker() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SkinTrailTicker::tick);
    }

    private static void tick(Minecraft client) {
        if (!SkinEffects.hasTrails()) {
            return;
        }
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        float partialTick = client.getTimer().getGameTimeDeltaPartialTick(false);
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemSupplier supplier)) {
                continue;
            }
            ItemStack stack = supplier.getItem();
            String entry = SkinItems.entryOf(stack);
            if (entry == null || SkinEffects.trailByEntry(entry) == null) {
                continue;
            }
            Vec3 position = new Vec3(
                    Mth.lerp(partialTick, entity.xo, entity.getX()),
                    Mth.lerp(partialTick, entity.yo, entity.getY()),
                    Mth.lerp(partialTick, entity.zo, entity.getZ()));
            SkinEffects.dispatchTrail(level, entity, stack, position, partialTick);
        }
    }
}

package com.habitrain.lottery.api.skin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/**
 * Everything a {@link SkinImpactHandler} needs about one impact.
 *
 * <p>Only common (server-safe) types appear here on purpose: extensions register
 * their handlers from the common entrypoint, which also runs on a dedicated server.</p>
 *
 * @param type      canonical skin type, e.g. {@code grenade}
 * @param id        canonical skin id, e.g. {@code example_black_hole}
 * @param level     the server level the impact happened in
 * @param position  impact position (the projectile's position when it exploded)
 * @param stack     the projectile's item stack, i.e. the skinned stack that was thrown;
 *                  read-only, do not mutate it
 * @param owner     the thrower's UUID, or {@code null} when the projectile had no owner
 * @param gameTime  {@code level.getGameTime()} at impact
 * @param scheduler delayed-action queue of the server that owns {@code level}
 */
public record SkinImpactContext(
        String type,
        String id,
        ServerLevel level,
        Vec3 position,
        ItemStack stack,
        UUID owner,
        long gameTime,
        Scheduler scheduler) {

    public SkinImpactContext {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(scheduler, "scheduler");
    }

    /** {@code type/id}, the key this handler was registered under. */
    public String entry() {
        return type + "/" + id;
    }

    /**
     * Queues {@code action} to run on the server thread after {@code delayTicks}
     * server ticks. {@code delayTicks <= 0} runs it on the next server tick.
     *
     * <p>Queued actions are dropped when the server stops; they never outlive the
     * level they were scheduled from.</p>
     */
    public void schedule(int delayTicks, Runnable action) {
        scheduler.schedule(delayTicks, action);
    }

    /**
     * The queue implementation is supplied by 哈比列车抽奖补齐; extensions only ever
     * call {@link #schedule(int, Runnable)}.
     */
    @FunctionalInterface
    public interface Scheduler {

        void schedule(int delayTicks, Runnable action);
    }
}

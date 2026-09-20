package com.habitrain.lottery.bridge;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinEffects;
import com.habitrain.lottery.api.skin.SkinImpactContext;
import com.habitrain.lottery.api.skin.SkinImpactHandler;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.skin.SkinComponents;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.content.entity.no_water_influenced.NoHeavyWaterInfluencedThrowableItemProjectile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Server side of the skin-effects pipeline: it gives a thrown projectile the skin its
 * thrower has equipped, and it triggers {@link SkinEffects} impact handlers at the
 * moment a skinned grenade explodes.
 *
 * <p>Everything here is deliberately upstream-aware — this is the one place that knows
 * about SRE's throwable hierarchy, which is exactly why it lives in {@code bridge}
 * instead of in the independent {@code api}/{@code skin} packages (the
 * {@code verifyIndependentSkins} build check enforces that split).</p>
 */
public final class SkinEffectRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_lottery");

    /** Only the grenade family inherits a thrown skin; see {@link #tagThrownProjectile}. */
    private static final String GRENADE_TYPE = "grenade";

    private static final PriorityQueue<ScheduledTask> SCHEDULED = new PriorityQueue<>();
    private static long sequence;

    private SkinEffectRuntime() {
    }

    /** Called once from the mod initialiser, on both physical sides. */
    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register(SkinEffectRuntime::onEntityLoad);
        ServerTickEvents.END_SERVER_TICK.register(SkinEffectRuntime::runDueTasks);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> clearScheduled());
    }

    // ────────────────────────────────────────────────────────────────
    //  Thrown-projectile skin inheritance
    // ────────────────────────────────────────────────────────────────

    /**
     * Dresses a freshly tracked throwable with its thrower's equipped skin.
     *
     * <p>{@code ServerEntityEvents.ENTITY_LOAD} fires from the tracking pass, i.e. one
     * tick after the throw, and by then the thrown stack has already been consumed — so
     * the skin is resolved from the player's <em>equipped</em> skin rather than from the
     * hand. That is also the semantically right source: the inventory pass dresses every
     * matching stack with exactly that skin, so the shop stack and the equipped skin can
     * never disagree.</p>
     *
     * <p>Only the grenade family is touched, and only when the stack carries no skin yet,
     * so a default grenade, a knife or any other projectile behaves exactly as before.</p>
     */
    private static void onEntityLoad(Entity entity, ServerLevel world) {
        if (!(entity instanceof NoHeavyWaterInfluencedThrowableItemProjectile throwable)) {
            return;
        }
        tagThrownProjectile(throwable);
    }

    private static void tagThrownProjectile(NoHeavyWaterInfluencedThrowableItemProjectile throwable) {
        ItemStack current = throwable.getItem();
        if (current == null || current.isEmpty() || current.has(SkinComponents.SKIN)) {
            return;
        }
        String type = SkinItems.typeOf(current);
        if (!GRENADE_TYPE.equals(type)) {
            return;
        }
        if (!(throwable.getOwner() instanceof Player player)) {
            return;
        }
        String skin = equippedSkin(player, type);
        if (skin == null || !SkinEffects.allowsItem(type, skin, current.getItem())) {
            return;
        }
        // copyWithCount(1) mirrors vanilla's setItem contract; the component is the only
        // thing we add, so the projectile keeps rendering its own item.
        ItemStack styled = current.copyWithCount(1);
        styled.set(SkinComponents.SKIN, type + "/" + skin);
        throwable.setItem(styled);
    }

    private static String equippedSkin(Player player, String type) {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        UUID id = player.getUUID();
        if (!store.isTakeoverActive() || store.isLoadFailed(id)) {
            return null;
        }
        String skin = store.getEquipped(id, type);
        if (skin == null || skin.isBlank()) {
            return null;
        }
        if (HabiSkinApi.find(type, skin).isEmpty() || !store.isSkinUnlocked(id, type, skin)) {
            return null;
        }
        return skin;
    }

    // ────────────────────────────────────────────────────────────────
    //  Impact dispatch
    // ────────────────────────────────────────────────────────────────

    /**
     * Runs the impact handler of the skin {@code projectile} visually carries.
     *
     * <p>Called from the built-in grenade trigger before the vanilla burst, so the
     * effect's particles and the suppression decision below see the same state.</p>
     *
     * @return {@code true} when a handler ran
     */
    public static boolean dispatchImpact(ServerLevel level, Entity projectile) {
        ItemStack stack = itemStackOf(projectile);
        String entry = SkinItems.entryOf(stack);
        SkinImpactHandler handler = SkinEffects.impactByEntry(entry);
        if (handler == null || !SkinEffects.allowsItemEntry(entry, stack.getItem())) {
            return false;
        }
        Entity owner = projectile instanceof Projectile shot ? shot.getOwner() : null;
        SkinImpactContext context = new SkinImpactContext(
                typeOf(entry), idOf(entry), level, projectile.position(), stack,
                owner == null ? null : owner.getUUID(),
                level.getGameTime(),
                (delayTicks, action) -> schedule(level.getServer(), delayTicks, action));
        return SkinEffects.dispatchImpact(context);
    }

    /**
     * True when this projectile's explosion visuals are owned by a registered effect, in
     * which case the built-in grenade burst (big explosion, smoke, item debris) is
     * suppressed. Damage, kills, rewards and the explosion sound are unaffected.
     */
    public static boolean replacesVanillaBurst(Entity projectile) {
        ItemStack stack = itemStackOf(projectile);
        return SkinEffects.replacesVanillaBurst(SkinItems.entryOf(stack), stack);
    }

    private static ItemStack itemStackOf(Entity entity) {
        return entity instanceof ItemSupplier supplier && supplier.getItem() != null
                ? supplier.getItem()
                : ItemStack.EMPTY;
    }

    private static String typeOf(String entry) {
        int slash = entry.indexOf('/');
        return slash < 0 ? entry : entry.substring(0, slash);
    }

    private static String idOf(String entry) {
        int slash = entry.indexOf('/');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    // ────────────────────────────────────────────────────────────────
    //  Delayed actions (SkinImpactContext#schedule)
    // ────────────────────────────────────────────────────────────────

    /**
     * Queues a server-thread action. Delays are measured in server ticks, so an effect
     * that wants "collapse, then a second boom" does not have to own a ticker.
     */
    public static void schedule(MinecraftServer server, int delayTicks, Runnable action) {
        if (server == null || action == null) {
            return;
        }
        long due = server.getTickCount() + Math.max(0, delayTicks);
        synchronized (SCHEDULED) {
            SCHEDULED.add(new ScheduledTask(due, sequence++, action));
        }
    }

    private static void runDueTasks(MinecraftServer server) {
        long now = server.getTickCount();
        List<Runnable> due = null;
        synchronized (SCHEDULED) {
            if (SCHEDULED.isEmpty()) {
                return;
            }
            while (!SCHEDULED.isEmpty() && SCHEDULED.peek().dueTick() <= now) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(SCHEDULED.poll().action());
            }
        }
        if (due == null) {
            return;
        }
        for (Runnable action : due) {
            try {
                action.run();
            } catch (Throwable t) {
                // An extension's scheduled action must never take the server down with it.
                LOGGER.error("Scheduled skin effect action failed", t);
            }
        }
    }

    private static void clearScheduled() {
        int dropped;
        synchronized (SCHEDULED) {
            dropped = SCHEDULED.size();
            SCHEDULED.clear();
        }
        if (dropped > 0) {
            LOGGER.debug("Dropped {} pending skin effect action(s) at server stop", dropped);
        }
    }

    private record ScheduledTask(long dueTick, long order, Runnable action) implements Comparable<ScheduledTask> {
        @Override
        public int compareTo(ScheduledTask other) {
            int byTick = Long.compare(dueTick, other.dueTick);
            return byTick != 0 ? byTick : Long.compare(order, other.order);
        }
    }
}

package com.habitrain.lottery.api.skin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin <b>effects</b> API v1 — the behaviour half of a skin, next to the model half in
 * {@link HabiSkinApi}.
 *
 * <p>A skin describes an appearance; an effect describes what that appearance
 * <em>does</em>. Three independent hooks are available, all keyed by the same
 * {@code type/id} pair the skin is registered under, all optional, and all
 * registered from the same {@link HabiSkinApi#ENTRYPOINT} entrypoint (so an extension
 * needs exactly one entrypoint for both halves):</p>
 *
 * <table border="1">
 *   <caption>Hooks</caption>
 *   <tr><th>Method</th><th>Side</th><th>When it runs</th></tr>
 *   <tr><td>{@link #registerImpact}</td><td>server</td>
 *       <td>a projectile carrying the skin impacts; takes over the explosion visuals</td></tr>
 *   <tr><td>{@link #registerTrail}</td><td>client</td>
 *       <td>every client tick, for every flying projectile carrying the skin</td></tr>
 *   <tr><td>{@link #registerAnimation}</td><td>client</td>
 *       <td>every frame the skin's item model is rendered</td></tr>
 * </table>
 *
 * <p>Plus one optional modifier: {@link #restrictToItems} narrows which items a skin
 * may be equipped on (for example "only the plain grenade, not the sticky or timed
 * one").</p>
 *
 * <p><b>Built-in triggers.</b> 哈比列车抽奖补齐 wires the hooks for the grenade
 * family: a thrown grenade inherits the thrower's equipped {@code grenade} skin, the
 * three grenade projectiles dispatch {@link #dispatchImpact}, the client dispatches
 * {@link #dispatchTrail}, and any registered impact handler replaces the vanilla
 * explosion burst for the plain grenade. Damage, kills, rewards and cooldowns are
 * never touched by this API. See {@code docs/skin-effects-api.md}.</p>
 *
 * <p><b>Registration order.</b> Register the {@link SkinDefinition} first and the
 * effect second: an effect for an unknown skin id is not an error (another extension
 * may own that skin) but it is logged, because it usually means a typo.</p>
 *
 * <p>Every registry is thread-safe and registration is idempotent, so a registrar can
 * be replayed by {@code /hlt skins reregister}.</p>
 */
public final class SkinEffects {

    /** Version of this sub-API. Bumped only on a breaking change of the types above. */
    public static final int API_VERSION = 1;
    /** Hard version gate an extension may {@code depends} on. */
    public static final String PROVIDES_ID = "habitrain_lottery_skin_effects";

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_lottery_skin_effects");

    private static final Map<String, SkinImpactHandler> IMPACTS = new ConcurrentHashMap<>();
    private static final Map<String, SkinTrailHandler> TRAILS = new ConcurrentHashMap<>();
    private static final Map<String, SkinAnimation> ANIMATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Set<ResourceLocation>> ITEM_FILTERS = new ConcurrentHashMap<>();
    /** Which entrypoint provider contributed each key, for transactional rollback. */
    private static final Map<String, String> PROVIDERS = new ConcurrentHashMap<>();
    /** Keys whose "no such skin" warning was already printed, so a replay stays quiet. */
    private static final Set<String> WARNED_UNKNOWN_SKINS = ConcurrentHashMap.newKeySet();
    /** Keys whose handler already threw once; further failures are counted, not spammed. */
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private SkinEffects() {
    }

    // ────────────────────────────────────────────────────────────────
    //  Registration
    // ────────────────────────────────────────────────────────────────

    /**
     * Installs the impact handler for one skin.
     *
     * <p>While a handler is registered for the skin a projectile actually carries, the
     * built-in explosion burst of a plain grenade is suppressed and the handler owns the
     * visuals. Re-registering the same key (a replayed registrar) replaces the previous
     * handler.</p>
     */
    public static void registerImpact(String type, String id, SkinImpactHandler handler) {
        Objects.requireNonNull(handler, "handler");
        String key = key(type, id);
        SkinImpactHandler previous = IMPACTS.put(key, handler);
        attribute(key);
        if (previous != null && previous != handler) {
            LOGGER.info("Skin impact handler for {} replaced ({})", key, providerName());
        }
        warnIfSkinUnknown(key);
    }

    /**
     * Installs the client-side flight trail for one skin.
     *
     * <p>Registration happens on both sides (it is part of the shared entrypoint), but
     * only a physical client ever invokes the handler.</p>
     */
    public static void registerTrail(String type, String id, SkinTrailHandler handler) {
        Objects.requireNonNull(handler, "handler");
        String key = key(type, id);
        SkinTrailHandler previous = TRAILS.put(key, handler);
        attribute(key);
        if (previous != null && previous != handler) {
            LOGGER.info("Skin trail handler for {} replaced ({})", key, providerName());
        }
        warnIfSkinUnknown(key);
    }

    /**
     * Installs the client-side item-model animation for one skin.
     *
     * @see SkinAnimation for the continuity guarantees of the animation itself
     */
    public static void registerAnimation(String type, String id, SkinAnimation animation) {
        Objects.requireNonNull(animation, "animation");
        String key = key(type, id);
        SkinAnimation previous = ANIMATIONS.put(key, animation);
        attribute(key);
        if (previous != null && !previous.equals(animation)) {
            LOGGER.info("Skin animation for {} replaced ({})", key, providerName());
        }
        if (!animation.isAnimated()) {
            LOGGER.debug("Skin animation for {} is inert (all amplitudes are zero)", key);
        }
        warnIfSkinUnknown(key);
    }

    /**
     * Narrows a skin to a fixed set of items; a stack of any other item bound to the same
     * skin type is not dressed with this skin (and is actively cleaned up on the next
     * inventory pass).
     *
     * <p>This exists because a skin <em>type</em> can cover several items — the
     * {@code grenade} type covers the plain, sticky and timed grenade through
     * {@code habitrain_lottery:skin_items/grenade} — while an extension may only want
     * one of them. Passing no items (or {@code null}) clears the restriction, which is
     * the default state.</p>
     *
     * <p>Ids that are not installed are harmless: they simply never match.</p>
     */
    public static void restrictToItems(String type, String id, ResourceLocation... items) {
        String key = key(type, id);
        if (items == null || items.length == 0) {
            ITEM_FILTERS.remove(key);
            if (!IMPACTS.containsKey(key) && !TRAILS.containsKey(key) && !ANIMATIONS.containsKey(key)) {
                PROVIDERS.remove(key);
            }
            return;
        }
        Set<ResourceLocation> allowed = new LinkedHashSet<>(Arrays.asList(items));
        if (allowed.contains(null)) {
            throw new IllegalArgumentException("Item restriction must not contain null");
        }
        ITEM_FILTERS.put(key, Collections.unmodifiableSet(allowed));
        attribute(key);
        LOGGER.debug("Skin {} restricted to {} item(s)", key, allowed.size());
    }

    /**
     * Removes every effect entry contributed by one entrypoint provider. Used to roll a
     * registrar back when it throws halfway through, mirroring
     * {@link HabiSkinApi#removeProvider(String)}.
     *
     * @return the number of removed entries
     */
    public static synchronized int removeProvider(String providerId) {
        if (providerId == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(PROVIDERS).entrySet()) {
            if (!providerId.equals(entry.getValue())) {
                continue;
            }
            String key = entry.getKey();
            if (IMPACTS.remove(key) != null) {
                removed++;
            }
            if (TRAILS.remove(key) != null) {
                removed++;
            }
            if (ANIMATIONS.remove(key) != null) {
                removed++;
            }
            if (ITEM_FILTERS.remove(key) != null) {
                removed++;
            }
            PROVIDERS.remove(key);
        }
        return removed;
    }

    // ────────────────────────────────────────────────────────────────
    //  Queries
    // ────────────────────────────────────────────────────────────────

    public static Optional<SkinImpactHandler> impact(String type, String id) {
        return lookup(IMPACTS, keyOrNull(type, id));
    }

    public static Optional<SkinTrailHandler> trail(String type, String id) {
        return lookup(TRAILS, keyOrNull(type, id));
    }

    public static Optional<SkinAnimation> animation(String type, String id) {
        return lookup(ANIMATIONS, keyOrNull(type, id));
    }

    /** Null-key tolerant lookup: {@link ConcurrentHashMap} rejects {@code null}. */
    private static <T> Optional<T> lookup(Map<String, T> registry, String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(registry.get(key));
    }

    /** Entry ({@code type/id}) form of {@link #animation(String, String)}. */
    public static Optional<SkinAnimation> animationByEntry(String entry) {
        return entry == null ? Optional.empty() : Optional.ofNullable(ANIMATIONS.get(entry));
    }

    /** Entry ({@code type/id}) form of {@link #trail(String, String)}; {@code null} when none. */
    public static SkinTrailHandler trailByEntry(String entry) {
        return entry == null ? null : TRAILS.get(entry);
    }

    /** Entry ({@code type/id}) form of {@link #impact(String, String)}; {@code null} when none. */
    public static SkinImpactHandler impactByEntry(String entry) {
        return entry == null ? null : IMPACTS.get(entry);
    }

    /**
     * True when {@code entry} has an impact handler that would run for {@code stack},
     * i.e. when the built-in trigger replaces the vanilla explosion burst with it.
     */
    public static boolean replacesVanillaBurst(String entry, ItemStack stack) {
        return resolveImpact(entry, stack) != null;
    }

    /** True when an impact handler is registered under {@code entry} (ignoring item filters). */
    public static boolean hasImpact(String entry) {
        return entry != null && IMPACTS.containsKey(entry);
    }

    /** True when any extension registered a flight trail; lets the client skip its scan. */
    public static boolean hasTrails() {
        return !TRAILS.isEmpty();
    }

    /**
     * True when a skin may be equipped on {@code item}. Unrestricted skins — the default —
     * always allow it.
     */
    public static boolean allowsItem(String type, String id, Item item) {
        String key = keyOrNull(type, id);
        return key == null || allowsItemEntry(key, item);
    }

    /** Entry form of {@link #allowsItem(String, String, Item)}. */
    public static boolean allowsItemEntry(String entry, Item item) {
        Set<ResourceLocation> allowed = entry == null ? null : ITEM_FILTERS.get(entry);
        if (allowed == null) {
            return true;
        }
        return item != null && allowed.contains(BuiltInRegistries.ITEM.getKey(item));
    }

    /** The item restriction of a skin, if any (entry form). */
    public static Optional<Set<ResourceLocation>> itemFilter(String entry) {
        Set<ResourceLocation> allowed = entry == null ? null : ITEM_FILTERS.get(entry);
        return allowed == null ? Optional.empty() : Optional.of(allowed);
    }

    /** Every key that has at least one effect, for diagnostics. */
    public static Set<String> entries() {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(IMPACTS.keySet());
        keys.addAll(TRAILS.keySet());
        keys.addAll(ANIMATIONS.keySet());
        keys.addAll(ITEM_FILTERS.keySet());
        return Collections.unmodifiableSet(keys);
    }

    /** Total number of registered effect entries (a key with three hooks counts three times). */
    public static int size() {
        return IMPACTS.size() + TRAILS.size() + ANIMATIONS.size() + ITEM_FILTERS.size();
    }

    // ────────────────────────────────────────────────────────────────
    //  Dispatch (called by the mod's own triggers; public for integrations)
    // ────────────────────────────────────────────────────────────────

    /**
     * Runs the impact handler of the skin {@code context} describes.
     *
     * <p>Called by the built-in grenade trigger. Other mods that add their own
     * explosives may call it with a hand-built {@link SkinImpactContext} to reuse the
     * same visual pipeline; no-op when nothing is registered.</p>
     *
     * @return {@code true} when a handler ran
     */
    public static boolean dispatchImpact(SkinImpactContext context) {
        Objects.requireNonNull(context, "context");
        SkinImpactHandler handler = resolveImpact(context.entry(), context.stack());
        if (handler == null) {
            return false;
        }
        try {
            handler.onImpact(context);
        } catch (Throwable t) {
            reportFailure(context.entry(), "impact", t);
        }
        return true;
    }

    /**
     * Runs the flight-trail handler carried by {@code stack}. No-op when the stack has no
     * skin, no trail is registered, or the skin's item restriction excludes the stack's
     * item.
     */
    public static void dispatchTrail(Level level, Entity projectile, ItemStack stack, Vec3 position, float partialTick) {
        String entry = SkinItems.entryOf(stack);
        SkinTrailHandler handler = trailByEntry(entry);
        if (handler == null) {
            return;
        }
        if (stack != null && !allowsItemEntry(entry, stack.getItem())) {
            return;
        }
        try {
            handler.onTrail(level, projectile, position, partialTick);
        } catch (Throwable t) {
            reportFailure(entry, "trail", t);
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Internals
    // ────────────────────────────────────────────────────────────────

    private static SkinImpactHandler resolveImpact(String entry, ItemStack stack) {
        if (entry == null) {
            return null;
        }
        SkinImpactHandler handler = IMPACTS.get(entry);
        if (handler == null) {
            return null;
        }
        if (stack != null && !allowsItemEntry(entry, stack.getItem())) {
            return null;
        }
        return handler;
    }

    private static void attribute(String key) {
        String provider = HabiSkinApi.currentProvider();
        if (provider != null) {
            PROVIDERS.put(key, provider);
        }
    }

    private static String providerName() {
        String provider = HabiSkinApi.currentProvider();
        return provider == null ? "direct registration" : provider;
    }

    private static void warnIfSkinUnknown(String key) {
        if (HabiSkinApi.find(key.substring(0, key.indexOf('/')), key.substring(key.indexOf('/') + 1)).isPresent()) {
            return;
        }
        if (WARNED_UNKNOWN_SKINS.add(key)) {
            LOGGER.warn(
                    "Skin effect registered for {} but no such skin is registered (yet). "
                            + "Register the SkinDefinition first; another extension may also own that skin",
                    key);
        }
    }

    private static void reportFailure(String entry, String hook, Throwable t) {
        if (REPORTED_FAILURES.add(entry + "#" + hook)) {
            LOGGER.error("Skin {} handler for {} threw; further failures of this handler are not logged",
                    hook, entry, t);
        }
    }

    private static String key(String type, String id) {
        String canonicalType = SkinDefinition.normalizeType(type, false);
        String canonicalId = SkinDefinition.normalizeSkinId(id);
        if (canonicalId == null) {
            throw new IllegalArgumentException("Skin id must not be blank");
        }
        return canonicalType + "/" + canonicalId;
    }

    private static String keyOrNull(String type, String id) {
        try {
            return key(type, id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

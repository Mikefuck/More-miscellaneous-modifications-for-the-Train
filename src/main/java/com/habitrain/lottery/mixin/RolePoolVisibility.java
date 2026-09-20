package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import io.wifi.starrailexpress.api.SRERole;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Applies habitrain_core's role-override visibility rule to a candidate pool that
 * the lottery rebuilds itself.
 *
 * <p>Core filters hidden / replaced roles out of {@code SingleSelectDraftState.rolePool}
 * and {@code currentCandidates} in its own mixin, but
 * {@code RoleRotationCardMixin.habi$prepareCandidates} HEAD-cancels
 * {@code prepareCandidatesForPlayer} and rebuilds the candidate list from
 * {@code rolePool}. If that pool ever still holds an unfiltered role (ordering of
 * the two mixins, a pool built before the override snapshot was published, or any
 * other write path), the hidden role would be offered again. {@link #filterVisible}
 * applies the same "resolve, then check visibility" rule before the pool is used.
 *
 * <p>The probe is reflective on purpose: this mod must not take a compile-time
 * dependency on core's implementation package
 * ({@code com.habitrain.core.game.sre.roleoverride}), only on its public API.
 * The resolver class is loaded once with {@link Class#forName(String)} and the two
 * static method handles are cached; every failure mode is swallowed.
 *
 * <p><b>Availability fallback (deliberate).</b> When the core resolver cannot be
 * reached — class absent, signature drift, or anything throwing while filtering —
 * this class logs a WARN once and returns the source list unchanged. Candidate
 * filtering is a presentation / fairness concern: a missing core would otherwise
 * empty every candidate list and break the rotation mode outright. The fail-closed
 * choice belongs to the security gates ({@code MenuGateServerBridge},
 * {@code PlayerStateGate}), which refuse an operation when core state is unknown;
 * it does not belong to candidate filtering, where refusing means the game cannot
 * be played at all.
 */
public final class RolePoolVisibility {

    /** Core implementation class, resolved by reflection only. */
    private static final String RESOLVER_CLASS_NAME =
            "com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver";

    /** Cached {@code public static SRERole resolve(SRERole)} handle, or {@code null}. */
    private static final Method RESOLVE;
    /** Cached {@code public static boolean isVisible(SRERole)} handle, or {@code null}. */
    private static final Method IS_VISIBLE;

    /** Guards the one-shot availability WARN. */
    private static boolean warnedUnavailable;

    static {
        Method resolve = null;
        Method isVisible = null;
        try {
            Class<?> resolver = Class.forName(RESOLVER_CLASS_NAME);
            resolve = resolver.getMethod("resolve", SRERole.class);
            isVisible = resolver.getMethod("isVisible", SRERole.class);
        } catch (Throwable t) {
            resolve = null;
            isVisible = null;
            warnUnavailable(t);
        }
        RESOLVE = resolve;
        IS_VISIBLE = isVisible;
    }

    private RolePoolVisibility() {
    }

    /**
     * Diagnostics accessor: {@code true} only when the reflective probe reached core
     * and both method handles are cached.
     */
    public static boolean isCoreVisibilityFilterAvailable() {
        return RESOLVE != null && IS_VISIBLE != null;
    }

    /**
     * Returns a <b>new</b> list containing every visible role of {@code source},
     * each replaced by its effective (resolved) instance.
     *
     * <p>A role is dropped when {@code resolve} returns {@code null}, when the
     * resolved role has a {@code null} identifier, or when
     * {@code isVisible(resolved)} is {@code false}. Otherwise the resolved role is
     * added (not the role that came in), so downstream consumers only ever see the
     * effective object.
     *
     * <p>Never returns {@code null}. When the core probe is unavailable — or any
     * throw happens while filtering — the source list is returned unchanged as a
     * fail-open availability fallback (see the class javadoc).
     */
    public static ArrayList<SRERole> filterVisible(Collection<SRERole> source) {
        ArrayList<SRERole> filtered = new ArrayList<>(source == null ? 0 : source.size());
        if (source == null || source.isEmpty()) {
            return filtered;
        }
        if (!isCoreVisibilityFilterAvailable()) {
            filtered.addAll(source);
            return filtered;
        }
        try {
            for (SRERole role : source) {
                SRERole resolved = (SRERole) RESOLVE.invoke(null, role);
                if (resolved == null || resolved.identifier() == null) {
                    continue;
                }
                Object visible = IS_VISIBLE.invoke(null, resolved);
                if (!(visible instanceof Boolean) || !((Boolean) visible)) {
                    continue;
                }
                filtered.add(resolved);
            }
            return filtered;
        } catch (Throwable t) {
            // Fail-open: a broken probe must not empty the candidate pool.
            warnUnavailable(t);
            ArrayList<SRERole> unfiltered = new ArrayList<>(source.size());
            unfiltered.addAll(source);
            return unfiltered;
        }
    }

    private static void warnUnavailable(Throwable cause) {
        if (warnedUnavailable) {
            return;
        }
        warnedUnavailable = true;
        try {
            HabiLotteryMod.LOGGER.warn(
                    "Core role-visibility resolver {} is unavailable ({}); "
                            + "role-rotation candidate pools fall back to the unfiltered rolePool",
                    RESOLVER_CLASS_NAME,
                    cause == null ? "unknown cause" : cause.toString());
        } catch (Throwable ignored) {
            // Logging must never break the fallback path.
        }
    }
}

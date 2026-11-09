package com.habitrain.lottery.card;

import com.habitrain.core.api.role.v2.RoleVisibilityApi;
import com.habitrain.lottery.HabiLotteryMod;
import io.wifi.starrailexpress.api.SRERole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Applies {@code habitrain_core}'s public {@link RoleVisibilityApi} visibility rule to a
 * candidate pool that the lottery rebuilds itself ({@code B-11}).
 *
 * <p>Core filters hidden / replaced roles out of {@code SingleSelectDraftState.rolePool}
 * and {@code currentCandidates} in its own mixin, but
 * {@code RoleRotationCardMixin.habi$prepareCandidates} HEAD-cancels
 * {@code prepareCandidatesForPlayer} and rebuilds the candidate list from
 * {@code rolePool}. Filtering through {@link RoleVisibilityApi#filterVisible(Collection)}
 * ensures hidden or replaced baseline roles never re-enter the candidate pool while
 * living outside Mixin's managed {@code com.habitrain.lottery.mixin} package.
 *
 * <p><b>Availability fallback (deliberate).</b> When the core visibility bridge is not
 * installed (e.g. standalone unit tests) or throws unexpectedly, this helper logs a
 * one-shot WARN and returns a copy of the source list unchanged so rotation draft never
 * degrades into an empty candidate pool.
 */
public final class RolePoolVisibility {

    /** Guards the one-shot availability WARN. */
    private static volatile boolean warnedUnavailable;

    private RolePoolVisibility() {
    }

    /**
     * Diagnostics accessor: {@code true} when {@link RoleVisibilityApi#isAvailable()}
     * reports that core's visibility bridge is installed.
     */
    public static boolean isCoreVisibilityFilterAvailable() {
        try {
            return RoleVisibilityApi.isAvailable();
        } catch (RuntimeException | LinkageError t) {
            warnUnavailable(t);
            return false;
        }
    }

    /**
     * Returns a <b>new</b> mutable list containing every visible role of {@code source},
     * each replaced by its effective (resolved) instance and deduplicated by identifier.
     *
     * <p>Never returns {@code null}. When the core visibility bridge is unavailable or
     * throws, returns a copy of {@code source} as a fail-open availability fallback.
     */
    public static ArrayList<SRERole> filterVisible(Collection<SRERole> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            if (!RoleVisibilityApi.isAvailable()) {
                return new ArrayList<>(source);
            }
            List<SRERole> visible = RoleVisibilityApi.filterVisible(source);
            return new ArrayList<>(visible);
        } catch (RuntimeException | LinkageError t) {
            warnUnavailable(t);
            return new ArrayList<>(source);
        }
    }

    private static void warnUnavailable(Throwable cause) {
        if (warnedUnavailable) {
            return;
        }
        warnedUnavailable = true;
        try {
            HabiLotteryMod.LOGGER.warn(
                    "Core RoleVisibilityApi is unavailable ({}); "
                            + "role-rotation candidate pools fall back to the unfiltered rolePool",
                    cause == null ? "unknown cause" : cause.toString());
        } catch (Throwable ignored) {
            // Logging must never break the fallback path.
        }
    }
}

package com.habitrain.lottery.grant;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import io.wifi.starrailexpress.api.SRERole;

import java.util.Optional;

/**
 * Ended-round catalog lookup for grants.
 *
 * <p>{@link EffectiveRole#profile()} is the frozen authority. {@link EffectiveRole#role()}
 * is a live handle and may be null on archives — grants must not read mutable
 * SRERole flags from it.
 */
public final class GrantRoleSnapshot {
    private GrantRoleSnapshot() {
    }

    /**
     * Assigned match-role identity. Never returns {@code EffectiveRole.role()}
     * (archived snapshots strip it; live handles can be overlay-mutated).
     */
    public static SRERole remap(SRERole raw) {
        return raw;
    }

    /**
     * Prefer {@link RoleCatalogApi#lastEndedSnapshot()}, then
     * {@link RoleCatalogApi#currentSnapshot()}. Does not call
     * {@code RoleCatalogApi.resolve(raw)} (that can be the next-lobby overlay).
     */
    static Optional<RoleSnapshot> endedSnapshot() {
        try {
            RoleCatalogApi api = RoleCatalogApi.instance();
            Optional<RoleSnapshot> snap = api.lastEndedSnapshot();
            if (snap == null || snap.isEmpty()) {
                snap = api.currentSnapshot();
            }
            return snap == null ? Optional.empty() : snap;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    static Optional<EffectiveRole> find(RoleSnapshot snapshot, SRERole raw) {
        if (raw == null || raw.identifier() == null) {
            return Optional.empty();
        }
        return find(snapshot, RoleKey.of(raw.identifier()));
    }

    static Optional<EffectiveRole> find(RoleSnapshot snapshot, RoleKey key) {
        if (snapshot == null || key == null) {
            return Optional.empty();
        }
        try {
            Optional<EffectiveRole> found = snapshot.find(key);
            return found == null ? Optional.empty() : found;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    static Optional<EffectiveRole> endedEffective(SRERole raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return endedSnapshot().flatMap(s -> find(s, raw));
    }

    static Optional<EffectiveRoleProfile> endedProfile(SRERole raw) {
        return endedEffective(raw).map(EffectiveRole::profile);
    }
}

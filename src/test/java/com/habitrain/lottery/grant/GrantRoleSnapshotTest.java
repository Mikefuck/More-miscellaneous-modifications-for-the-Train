package com.habitrain.lottery.grant;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import io.wifi.starrailexpress.api.SRERole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrantRoleSnapshotTest {

    @Test
    void remapFindAndEndedSnapshotAreNullSafe() {
        assertNull(GrantRoleSnapshot.remap(null));
        assertTrue(GrantRoleSnapshot.find(null, (SRERole) null).isEmpty());
        assertTrue(GrantRoleSnapshot.find(null, (RoleKey) null).isEmpty());
        try {
            Optional<RoleSnapshot> snap = GrantRoleSnapshot.endedSnapshot();
            assertTrue(snap != null);
            assertTrue(GrantRoleSnapshot.find(snap.orElse(null), (SRERole) null).isEmpty());
        } catch (Throwable t) {
            throw new AssertionError("endedSnapshot must not throw", t);
        }
    }

    @Test
    void findOnEmptySnapshotIsEmpty() {
        RoleSnapshot snap = new RoleSnapshot(new RoleSnapshotId(1), Map.of(), Map.of(), Set.of());
        assertTrue(GrantRoleSnapshot.find(snap, (SRERole) null).isEmpty());
        assertTrue(GrantRoleSnapshot.find(snap, RoleKey.of("starrailexpress", "civilian")).isEmpty());
    }

    @Test
    void snapshotProfileWinsOverMissingLiveHandle() {
        RoleKey key = RoleKey.of("starrailexpress", "jester");
        EffectiveRoleProfile profile = frozenNeutral(key);
        EffectiveRole stored = new EffectiveRole(profile, null);
        RoleSnapshot snap = new RoleSnapshot(
                new RoleSnapshotId(7),
                Map.of(key.location(), stored),
                Map.of(),
                Set.of());

        EffectiveRole found = GrantRoleSnapshot.find(snap, key).orElseThrow();
        assertNull(found.role(), "archives strip the live handle");
        assertTrue(found.profile().neutral());
        assertEquals(
                GrantEventHooks.WinFaction.NEUTRAL,
                WinFactionRules.fromProfile(found.profile(), false));
        assertEquals(
                GrantEventHooks.WinFaction.NEUTRAL,
                GrantEventHooks.classifyFromCatalog(null, found.profile()));
        // Mutated live killer bits would have classified as killer; profile still wins.
        assertEquals(
                GrantEventHooks.WinFaction.KILLER,
                WinFactionRules.fromRoleFlags(
                        false, false, false, false, true, true, false, false));
    }

    private static EffectiveRoleProfile frozenNeutral(RoleKey key) {
        return new EffectiveRoleProfile(
                key, EffectiveRole.Source.MODIFIED, 0xFFFFFF, SRERole.MoodType.REAL,
                false, false, true, false,
                1, 100, 0, 0,
                false, false, true, 20, false,
                false, false, false, false, false, false,
                false, false, 0, SRERole.SpecialMapRoleMap.ALL,
                List.of(), List.of(), List.of(),
                false,
                List.of(), false,
                null, null, null, null, null, null, null, null, null, null);
    }
}

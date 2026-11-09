package com.habitrain.lottery.grant;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.ActiveForcesStore;
import com.habitrain.lottery.card.SelfSelectForces;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@code I-14} ({@link RoleSlotAllocator} deterministic unprotected
 * holder swap) and {@code I-12} ({@link SelfSelectRoleHook} two-phase confirmation,
 * safeTimeCooldown abort refund, and {@link ActiveForcesStore} crash recovery).
 */
class RoleSlotAllocatorAndSelfSelectPrepTest {

    private record DummyRole(String id, int faction) { }

    @TempDir
    Path temp;

    private final PlayerLotteryStore store = PlayerLotteryStore.get();

    @BeforeEach
    void setUp() {
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));
        store.reset();
        ActiveCardForces.clear();
        SelfSelectForces.clear();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach
    void tearDown() {
        ActiveCardForces.clear();
        SelfSelectForces.clear();
        store.reset();
        WorldLotteryPaths.clear();
        LotteryDayClock.resetTestClock();
    }

    @Test
    void allocatorSkipsFirstProtectedHolderAndSwapsWithDeterministicUnprotectedHolder() {
        UUID protectedFirst = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID unprotectedSecond = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID unprotectedThird = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID requester = UUID.fromString("00000000-0000-0000-0000-000000000009");

        DummyRole killer = new DummyRole("sre:killer", 4);
        DummyRole civilian = new DummyRole("sre:civilian", 1);

        Map<UUID, DummyRole> assignments = new LinkedHashMap<>();
        // Insert protected holder first so naive iteration would hit it immediately.
        assignments.put(protectedFirst, killer);
        assignments.put(unprotectedThird, killer);
        assignments.put(unprotectedSecond, killer);
        assignments.put(requester, civilian);

        Set<UUID> protectedSet = Set.of(protectedFirst, requester);
        boolean ok = RoleSlotAllocator.assign(
                assignments,
                requester,
                killer,
                DummyRole::id,
                DummyRole::faction,
                protectedSet::contains);

        assertTrue(ok, "Must swap with unprotected holder instead of aborting on first protected holder");
        assertEquals(killer, assignments.get(protectedFirst), "Protected holder must keep their role");
        assertEquals(killer, assignments.get(requester), "Requester must receive desired role");
        assertEquals(civilian, assignments.get(unprotectedSecond),
                "Lowest-UUID unprotected holder must receive requester's old role deterministically");
        assertEquals(killer, assignments.get(unprotectedThird));
    }

    @Test
    void allocatorFailsWhenAllHoldersOfDesiredRoleAreProtected() {
        UUID protectedHolder = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bystanderSameFaction = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID requester = UUID.fromString("00000000-0000-0000-0000-000000000003");

        DummyRole uniqueKiller = new DummyRole("sre:morphling", 4);
        DummyRole otherKiller = new DummyRole("sre:stalker", 4);
        DummyRole civilian = new DummyRole("sre:civilian", 1);

        Map<UUID, DummyRole> assignments = new LinkedHashMap<>();
        assignments.put(protectedHolder, uniqueKiller);
        assignments.put(bystanderSameFaction, otherKiller);
        assignments.put(requester, civilian);

        boolean ok = RoleSlotAllocator.assign(
                assignments,
                requester,
                uniqueKiller,
                DummyRole::id,
                DummyRole::faction,
                Set.of(protectedHolder, requester)::contains);

        assertFalse(ok, "Must not duplicate an already-held role when its only holder is protected");
        assertEquals(uniqueKiller, assignments.get(protectedHolder));
        assertEquals(otherKiller, assignments.get(bystanderSameFaction));
        assertEquals(civilian, assignments.get(requester));
    }

    @Test
    void selfSelectAbortedDuringSafeTimeCooldownRefundsCardAndQuotaIdempotently() {
        UUID uid = UUID.randomUUID();
        ResourceLocation targetRole = ResourceLocation.fromNamespaceAndPath("starrailexpress", "vigilante");

        assertTrue(LocalBackpackStore.setSelfSelectCards(uid, 1));
        assertTrue(LocalBackpackStore.addSelfSelectCards(uid, -1));
        assertTrue(DailySelfSelectService.recordSuccessfulUse(uid));
        assertEquals(0, LocalBackpackStore.selfSelectCards(uid));
        assertEquals(DailySelfSelectService.MAX_DAILY_USES - 1, DailySelfSelectService.remaining(uid));

        SelfSelectForces.record(uid, targetRole);
        SelfSelectRoleHook.recordPrepAssignmentForTest(uid, targetRole);

        // Match aborted during 30s safeTimeCooldown before OnGameTrueStarted!
        SelfSelectRoleHook.finishSelections();
        SelfSelectRoleHook.finishSelections(); // idempotent second call

        assertFalse(SelfSelectForces.has(uid));
        assertEquals(1, LocalBackpackStore.selfSelectCards(uid), "Self-select card must be refunded on prep abort");
        assertEquals(DailySelfSelectService.MAX_DAILY_USES, DailySelfSelectService.remaining(uid),
                "Daily self-select quota must be refunded on prep abort");
    }

    @Test
    void selfSelectDownstreamOverwriteRefundsWhileTrueStartConfirmationConsumes() {
        UUID overwrittenPlayer = UUID.randomUUID();
        UUID confirmedPlayer = UUID.randomUUID();
        ResourceLocation roleA = ResourceLocation.fromNamespaceAndPath("starrailexpress", "killer");
        ResourceLocation roleB = ResourceLocation.fromNamespaceAndPath("starrailexpress", "civilian");
        ResourceLocation roleC = ResourceLocation.fromNamespaceAndPath("starrailexpress", "vigilante");

        assertTrue(LocalBackpackStore.setSelfSelectCards(overwrittenPlayer, 1));
        assertTrue(LocalBackpackStore.addSelfSelectCards(overwrittenPlayer, -1));
        assertTrue(DailySelfSelectService.recordSuccessfulUse(overwrittenPlayer));

        assertTrue(LocalBackpackStore.setSelfSelectCards(confirmedPlayer, 1));
        assertTrue(LocalBackpackStore.addSelfSelectCards(confirmedPlayer, -1));
        assertTrue(DailySelfSelectService.recordSuccessfulUse(confirmedPlayer));

        SelfSelectForces.record(overwrittenPlayer, roleA);
        SelfSelectForces.record(confirmedPlayer, roleC);
        SelfSelectRoleHook.recordPrepAssignmentForTest(overwrittenPlayer, roleA);
        SelfSelectRoleHook.recordPrepAssignmentForTest(confirmedPlayer, roleC);

        // Downstream listener overwrote overwrittenPlayer -> roleB, while confirmedPlayer kept roleC.
        SelfSelectRoleHook.confirmStartedRoles(
                Map.of(overwrittenPlayer, roleB, confirmedPlayer, roleC),
                u -> null);

        assertFalse(SelfSelectForces.has(overwrittenPlayer));
        assertFalse(SelfSelectForces.has(confirmedPlayer));
        assertEquals(1, LocalBackpackStore.selfSelectCards(overwrittenPlayer),
                "Overwritten self-select player must receive card refund");
        assertEquals(DailySelfSelectService.MAX_DAILY_USES, DailySelfSelectService.remaining(overwrittenPlayer));
        assertEquals(0, LocalBackpackStore.selfSelectCards(confirmedPlayer),
                "Confirmed self-select player must consume card");
        assertEquals(DailySelfSelectService.MAX_DAILY_USES - 1, DailySelfSelectService.remaining(confirmedPlayer));

        // Subsequent end-of-match cleanup must not refund confirmedPlayer.
        SelfSelectRoleHook.finishSelections();
        assertEquals(0, LocalBackpackStore.selfSelectCards(confirmedPlayer));
    }

    @Test
    void activeForcesStoreRecoversBothFactionAndSelfSelectAfterHardCrash() {
        UUID factionPlayer = UUID.randomUUID();
        UUID selfSelectPlayer = UUID.randomUUID();
        ResourceLocation roleId = ResourceLocation.fromNamespaceAndPath("starrailexpress", "killer");

        assertTrue(store.applyToPlayerWithRollback(factionPlayer, d -> {
            d.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            d.factionCardUsesToday = 1;
        }));
        assertTrue(LocalBackpackStore.setSelfSelectCards(selfSelectPlayer, 1));
        assertTrue(LocalBackpackStore.addSelfSelectCards(selfSelectPlayer, -1));
        assertTrue(DailySelfSelectService.recordSuccessfulUse(selfSelectPlayer));

        ActiveCardForces.record(factionPlayer, FactionCardType.KILLER);
        SelfSelectForces.record(selfSelectPlayer, roleId);
        assertTrue(Files.isRegularFile(ActiveForcesStore.filePath()),
                "active_forces.json must be persisted immediately on lobby reservation");

        // Simulate hard process crash (kill -9): clear RAM without calling shutdown hooks.
        ActiveCardForces.clear();
        SelfSelectForces.clear();
        store.reset();

        ActiveForcesStore.recoverOnServerStarted();
        ActiveForcesStore.recoverOnServerStarted(); // idempotent second run

        assertNull(ActiveCardForces.get(factionPlayer));
        assertFalse(SelfSelectForces.has(selfSelectPlayer));
        assertEquals(1, LocalBackpackStore.load(factionPlayer).getOrDefault(FactionCardType.KILLER.questKey, 0));
        assertEquals(0, store.getOrLoad(factionPlayer).factionCardUsesToday);
        assertEquals(1, LocalBackpackStore.selfSelectCards(selfSelectPlayer));
        assertEquals(DailySelfSelectService.MAX_DAILY_USES, DailySelfSelectService.remaining(selfSelectPlayer));
    }

    @Test
    void prePityForceSnapshotDistinguishesUnpaidStreakPityFromGenuinePreMatchForces() {
        UUID preQueuedForce = UUID.randomUUID();
        UUID streakPitySelfSelect = UUID.randomUUID();
        org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager.ForcePlayerTeam.clear();
        try {
            // Before assignRolesToPlayers: only preQueuedForce is genuinely queued via RoleForceApi / ForcePlayerTeam
            org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager.ForcePlayerTeam.put(preQueuedForce, 4);
            SelfSelectRoleHook.capturePrePityForceUuids(
                    java.util.List.of(preQueuedForce, streakPitySelfSelect),
                    org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager.ForcePlayerTeam::containsKey);

            // Inside assignRolesToPlayers: upstream streak-pity inserts unpaid ForcePlayerTeam entry for streakPitySelfSelect
            org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager.ForcePlayerTeam.put(streakPitySelfSelect, 4);

            assertTrue(SelfSelectRoleHook.isProtectedExternalForce(preQueuedForce),
                    "Genuine pre-match RoleForceApi force must remain protected");
            assertFalse(SelfSelectRoleHook.isProtectedExternalForce(streakPitySelfSelect),
                    "Unpaid streak-pity ForcePlayerTeam insertion must NOT block paid self-select card");
        } finally {
            SelfSelectRoleHook.clearPrePityForces();
            org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager.ForcePlayerTeam.clear();
        }
    }
}

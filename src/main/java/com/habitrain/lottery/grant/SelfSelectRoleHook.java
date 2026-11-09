package com.habitrain.lottery.grant;

import com.habitrain.core.api.role.v2.RoleVisibilityApi;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.card.SelfSelectForces;
import com.habitrain.lottery.daily.config.DailyTaskTracker;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.exmo.sre.repair.role.RepairRole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.SREDisableManager;
import org.agmas.harpymodloader.events.OnGamePlayerRolesConfirm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Applies self-selections and companions using only the round's existing faction slots. */
public final class SelfSelectRoleHook {
    private static final Random RANDOM = new Random();
    /** Staged preparation assignments awaiting verification at {@code OnGameTrueStarted} ({@code I-12}). */
    private static final Map<UUID, ResourceLocation> ASSIGNED_PREP = new HashMap<>();
    private static final Set<UUID> BOUND_HOLDERS = new HashSet<>();
    /**
     * Genuine pre-match {@code RoleForceApi} UUIDs captured before upstream
     * {@code SREMurderGameMode#assignRolesToPlayers} inserts unpaid streak-pity forces ({@code I-15}).
     */
    private static volatile Set<UUID> prePityQueuedForces;
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private SelfSelectRoleHook() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            HabiLotteryMod.LOGGER.debug("SelfSelectRoleHook.register skipped (already registered)");
            return;
        }
        try {
            OnGamePlayerRolesConfirm.EVENT.register(SelfSelectRoleHook::beforeAssignRole);
            OnGameTrueStarted.EVENT.register(SelfSelectRoleHook::onGameTrueStarted);
            OnGameEnd.EVENT.register((level, game) -> finishSelections());
            HabiLotteryMod.LOGGER.info("Registered self-select role hook (OnGamePlayerRolesConfirm + OnGameTrueStarted)");
        } catch (Throwable t) {
            REGISTERED.set(false);
            HabiLotteryMod.LOGGER.warn("Self-select role hook unavailable: {}", t.toString());
        }
    }

    public static void capturePrePityForces(List<ServerPlayer> players) {
        if (players == null || players.isEmpty()) {
            prePityQueuedForces = Set.of();
            return;
        }
        List<UUID> uuids = new java.util.ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            if (player != null) {
                uuids.add(player.getUUID());
            }
        }
        capturePrePityForceUuids(uuids, CardUseService::isRoleForceQueued);
    }

    static void capturePrePityForceUuids(java.util.Collection<UUID> uuids, java.util.function.Predicate<UUID> isQueued) {
        if (uuids == null || uuids.isEmpty() || isQueued == null) {
            prePityQueuedForces = Set.of();
            return;
        }
        Set<UUID> snapshot = new HashSet<>();
        for (UUID uuid : uuids) {
            if (uuid != null && isQueued.test(uuid)) {
                snapshot.add(uuid);
            }
        }
        prePityQueuedForces = Set.copyOf(snapshot);
    }

    public static void clearPrePityForces() {
        prePityQueuedForces = null;
    }

    static boolean isProtectedExternalForce(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Set<UUID> snapshot = prePityQueuedForces;
        if (snapshot != null) {
            return snapshot.contains(uuid);
        }
        return CardUseService.isRoleForceQueued(uuid);
    }

    private static void beforeAssignRole(ServerLevel serverWorld, Map<Player, SRERole> roleAssignments) {
        ASSIGNED_PREP.clear();
        BOUND_HOLDERS.clear();
        if (SelfSelectForces.isEmpty() || roleAssignments == null || roleAssignments.isEmpty()) {
            clearPrePityForces();
            return;
        }
        try {
            Map<UUID, ResourceLocation> forces = SelfSelectForces.snapshot();

            // Resolve the chosen roles.
            Map<UUID, SRERole> chosen = new HashMap<>();
            for (Map.Entry<UUID, ResourceLocation> e : forces.entrySet()) {
                if (SelfSelectForces.refundPending(e.getKey())) {
                    refund(e.getKey());
                    continue;
                }
                // Settings can change between reservation and the next match.
                SRERole role = CardUseService.resolveCandidate(FactionCardType.NONE, e.getValue().toString());
                if (role != null) {
                    chosen.put(e.getKey(), role);
                } else {
                    refund(e.getKey());
                }
            }
            if (chosen.isEmpty()) {
                return;
            }

            // ---- 互斥冲突：两个玩家自选互为 opposing 的角色时，随机保留一个，另一个反卡 ----
            resolveOpposingConflicts(serverWorld, chosen);

            Set<UUID> protectedUids = new HashSet<>(chosen.keySet());
            protectedUids.addAll(com.habitrain.lottery.backpack.ActiveCardForces.snapshot().keySet());
            for (Player participant : roleAssignments.keySet()) {
                if (participant != null && isProtectedExternalForce(participant.getUUID())) {
                    protectedUids.add(participant.getUUID());
                }
            }

            // Commit the exact role and its companions as one transaction.
            List<UUID> order = new ArrayList<>(chosen.keySet());
            java.util.Collections.shuffle(order, RANDOM);
            for (UUID uid : order) {
                SRERole role = chosen.get(uid);
                Player p = serverWorld.getPlayerByUUID(uid);
                ServerPlayer sp = p instanceof ServerPlayer s ? s : null;
                Map<Player, SRERole> trial = new HashMap<>(roleAssignments);
                Set<UUID> trialProtected = new HashSet<>(protectedUids);

                // Stage the exact role and its companions in one isolated map. If any
                // companion cannot be paired, nothing is merged back and the card is
                // correctly refunded (the player did not receive the role).
                boolean accepted = sp != null && assignWithinSlots(trial, sp, role, trialProtected);
                if (accepted && role.getoccupationRoles() != null) {
                    for (SRERole rawCompanion : role.getoccupationRoles()) {
                        if (rawCompanion == null) continue;
                        SRERole companion = resolveCompanion(rawCompanion);
                        if (companion == null) {
                            accepted = false;
                            break;
                        }
                        if (isAssigned(trial, companion)) {
                            trial.forEach((holder, assigned) -> {
                                if (assigned != null && assigned.identifier().equals(companion.identifier()))
                                    trialProtected.add(holder.getUUID());
                            });
                            continue;
                        }
                        List<Player> candidates = new ArrayList<>(trial.keySet());
                        candidates.sort(Comparator.comparing(Player::getUUID));
                        boolean paired = false;
                        for (Player candidate : candidates) {
                            if (!(candidate instanceof ServerPlayer) || trialProtected.contains(candidate.getUUID())) continue;
                            if (assignWithinSlots(trial, candidate, companion, trialProtected)) {
                                trialProtected.add(candidate.getUUID());
                                paired = true;
                                break;
                            }
                        }
                        if (!paired) {
                            accepted = false;
                            break;
                        }
                    }
                }
                if (!accepted) {
                    // trial was never merged, so roleAssignments is untouched.
                    refund(uid);
                    protectedUids.remove(uid);
                    continue;
                }
                roleAssignments.putAll(trial);
                protectedUids.addAll(trialProtected);
                BOUND_HOLDERS.addAll(trialProtected);
                ASSIGNED_PREP.put(uid, SelfSelectForces.canonicalize(role.identifier()));
                HabiLotteryMod.LOGGER.info("Self-select staged in prep within existing slots for {}: {}", uid, role.identifier());
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Self-select assignment failed: {}", t.toString());
        } finally {
            clearPrePityForces();
        }
    }

    private static void onGameTrueStarted(ServerLevel level) {
        if (SelfSelectForces.isEmpty()) {
            ASSIGNED_PREP.clear();
            BOUND_HOLDERS.clear();
            return;
        }
        Map<UUID, ResourceLocation> finalRoleIds = new HashMap<>();
        try {
            var roles = SREGameWorldComponent.KEY.get(level).getRoles();
            if (roles != null) {
                for (UUID uid : SelfSelectForces.snapshot().keySet()) {
                    if (level.getPlayerByUUID(uid) != null) {
                        SRERole assigned = roles.get(uid);
                        if (assigned != null && assigned.identifier() != null) {
                            finalRoleIds.put(uid, assigned.identifier());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            HabiLotteryMod.LOGGER.error("Failed reading final roles at OnGameTrueStarted for self-select", e);
        }
        confirmStartedRoles(finalRoleIds, uid ->
                level.getPlayerByUUID(uid) instanceof ServerPlayer sp ? sp : null);
    }

    static void confirmStartedRoles(Map<UUID, ResourceLocation> finalRoleIds,
                                    Function<UUID, ServerPlayer> onlineLookup) {
        try {
            for (UUID uid : SelfSelectForces.snapshot().keySet()) {
                ResourceLocation expected = ASSIGNED_PREP.get(uid);
                ResourceLocation actual = finalRoleIds == null ? null : finalRoleIds.get(uid);
                boolean matched = !SelfSelectForces.refundPending(uid)
                        && expected != null
                        && actual != null
                        && expected.equals(SelfSelectForces.canonicalize(actual));
                if (matched) {
                    SelfSelectForces.remove(uid);
                    ServerPlayer online = onlineLookup == null ? null : onlineLookup.apply(uid);
                    if (online != null) {
                        DailyTaskTracker.onCardUsed(online, CardUseService.SELF_SELECT_KEY);
                    }
                    HabiLotteryMod.LOGGER.info("Self-select confirmed at OnGameTrueStarted for {}: {}", uid, actual);
                } else {
                    refund(uid);
                }
            }
        } finally {
            ASSIGNED_PREP.clear();
            BOUND_HOLDERS.clear();
        }
    }

    static void recordPrepAssignmentForTest(UUID uid, ResourceLocation roleId) {
        if (uid != null && roleId != null) {
            ASSIGNED_PREP.put(uid, SelfSelectForces.canonicalize(roleId));
        }
    }

    static SRERole resolveCompanion(SRERole companion) {
        if (companion == null || companion.identifier() == null) {
            return null;
        }
        SRERole resolved = companion;
        try {
            if (RoleVisibilityApi.isAvailable()) {
                resolved = RoleVisibilityApi.resolve(companion);
                if (resolved == null || resolved.identifier() == null || !RoleVisibilityApi.isVisible(resolved)) {
                    return null;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fallback when visibility SPI is not installed in headless tests.
        }
        if (resolved instanceof RepairRole) {
            return null;
        }
        try {
            if (SREDisableManager.isRoleDisabled(resolved)) {
                return null;
            }
        } catch (Throwable ignored) {
            // Treat as enabled when roster is unreachable.
        }
        return resolved;
    }

    /**
     * If two (or more) self-selected roles are mutually exclusive (each lists the
     * other in {@code opposingRoles}), keep only one randomly and refund the losers.
     */
    private static void resolveOpposingConflicts(ServerLevel serverWorld, Map<UUID, SRERole> chosen) {
        List<UUID> ids = new ArrayList<>(chosen.keySet());
        List<UUID> dropped = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            UUID a = ids.get(i);
            if (dropped.contains(a) || !chosen.containsKey(a)) {
                continue;
            }
            for (int j = i + 1; j < ids.size(); j++) {
                UUID b = ids.get(j);
                if (dropped.contains(b) || !chosen.containsKey(b)) {
                    continue;
                }
                SRERole ra = chosen.get(a);
                SRERole rb = chosen.get(b);
                if (ra == null || rb == null) {
                    continue;
                }
                boolean aOpposesB = containsRole(ra.getOpposingRoles(), rb);
                boolean bOpposesA = containsRole(rb.getOpposingRoles(), ra);
                if (!aOpposesB && !bOpposesA) {
                    continue;
                }
                // Mutually exclusive pair found — keep one at random, refund the other.
                UUID keep = RANDOM.nextBoolean() ? a : b;
                UUID drop = keep.equals(a) ? b : a;
                dropped.add(drop);
                chosen.remove(drop);
                refund(drop);
            }
        }
    }

    private static boolean containsRole(Set<SRERole> roles, SRERole target) {
        if (roles == null || target == null) {
            return false;
        }
        for (SRERole r : roles) {
            if (r != null && r.identifier().equals(target.identifier())) {
                return true;
            }
        }
        return false;
    }

    /** Whether any player in the assignment map already has the given role. */
    private static boolean isAssigned(Map<Player, SRERole> roleAssignments, SRERole role) {
        if (role == null) {
            return false;
        }
        for (SRERole r : roleAssignments.values()) {
            if (r != null && r.identifier().equals(role.identifier())) {
                return true;
            }
        }
        return false;
    }

    static boolean assignWithinSlots(Map<Player, SRERole> assignments, Player player,
                                     SRERole role, Set<UUID> protectedUids) {
        return RoleSlotAllocator.assign(assignments, player, role, SRERole::identifier,
                SelfSelectRoleHook::slotGroup, p -> protectedUids.contains(p.getUUID()));
    }

    // Match SRE's special-role budgets (I-15); both neutral card types share one budget.
    static int slotGroup(SRERole role) {
        if (role.canUseKiller() || role.isKillerTeam() || role.isMafiaTeam()) return 4;
        if (role.isVigilanteTeam()) return 5;
        return role.isInnocent() ? 1 : 2;
    }

    public static void finishSelections() {
        // Modes without OnGamePlayerRolesConfirm or matches aborted before OnGameTrueStarted
        // refund all remaining unconfirmed self-selections (I-12).
        for (UUID uid : SelfSelectForces.snapshot().keySet()) {
            refund(uid);
        }
        ASSIGNED_PREP.clear();
        BOUND_HOLDERS.clear();
        clearPrePityForces();
    }

    static Set<UUID> protectedHolders() {
        return Set.copyOf(BOUND_HOLDERS);
    }

    /** Refund a dropped self-select: {@code SELF_SELECT_COST} cards + one daily use (offline OK). */
    private static void refund(UUID uid) {
        if (uid == null) {
            return;
        }
        if (SelfSelectForces.has(uid)) {
            if (!CardUseService.refundSelfSelect(uid, FactionCardType.NONE)) return;
        }
        SelfSelectForces.remove(uid);
    }
}

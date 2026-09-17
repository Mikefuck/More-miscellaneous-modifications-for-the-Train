package com.habitrain.lottery.grant;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.card.SelfSelectForces;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.events.OnGamePlayerRolesConfirm;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Applies self-selections and companions using only the round's existing faction slots. */
public final class SelfSelectRoleHook {
    private static final Random RANDOM = new Random();
    private static final Set<UUID> HONORED = new HashSet<>();
    private static final Set<UUID> BOUND_HOLDERS = new HashSet<>();
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
            OnGameEnd.EVENT.register((level, game) -> finishSelections());
            OnGameTrueStarted.EVENT.register(level -> finishSelections());
            HabiLotteryMod.LOGGER.info("Registered self-select role hook (OnGamePlayerRolesConfirm)");
        } catch (Throwable t) {
            REGISTERED.set(false);
            HabiLotteryMod.LOGGER.warn("Self-select role hook unavailable: {}", t.toString());
        }
    }

    private static void beforeAssignRole(ServerLevel serverWorld, Map<Player, SRERole> roleAssignments) {
        if (SelfSelectForces.isEmpty() || roleAssignments == null || roleAssignments.isEmpty()) {
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

            // Commit the exact role and its companions as one transaction.
            List<UUID> order = new ArrayList<>(chosen.keySet());
            java.util.Collections.shuffle(order, RANDOM);
            for (UUID uid : order) {
                SRERole role = chosen.get(uid);
                ServerPlayer sp = (ServerPlayer) serverWorld.getPlayerByUUID(uid);
                Map<Player, SRERole> trial = new HashMap<>(roleAssignments);
                Set<UUID> trialProtected = new HashSet<>(protectedUids);

                // Stage the exact role and its companions in one isolated map. If any
                // companion cannot be paired, nothing is merged back and the card is
                // correctly refunded (the player did not receive the role).
                boolean accepted = sp != null && assignWithinSlots(trial, sp, role, trialProtected);
                if (accepted) {
                    for (SRERole companion : role.getoccupationRoles()) {
                        if (companion == null) continue;
                        if (CardUseService.resolveCandidate(FactionCardType.NONE, companion.identifier().toString()) == null) {
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
                        boolean paired = false;
                        for (Player candidate : new ArrayList<>(trial.keySet())) {
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
                HONORED.add(uid);
                HabiLotteryMod.LOGGER.info("Self-select honored within existing slots for {}: {}", uid, role.identifier());
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Self-select assignment failed: {}", t.toString());
        }
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

    // Match SRE's three special-role budgets; both neutral card types share one budget.
    private static int slotGroup(SRERole role) {
        if (role.canUseKiller()) return 4;
        if (role.isVigilanteTeam()) return 5;
        return role.isInnocent() ? 1 : 2;
    }
    public static void finishSelections() {
        // Modes without the confirmation event must not silently consume exact-role cards.
        for (UUID uid : SelfSelectForces.snapshot().keySet()) {
            if (HONORED.contains(uid)) SelfSelectForces.remove(uid);
            else refund(uid);
        }
        HONORED.clear();
        BOUND_HOLDERS.clear();
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

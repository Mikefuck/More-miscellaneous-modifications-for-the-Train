package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.card.RotationCardGroups;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.game.modes.funny.rotation.SingleSelectDraftState;
import org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Fixes faction-card handling in the new single-select role-rotation mode
 * ({@link SingleSelectDraftState}).
 *
 * <p>New SRE moved rotation state from the removed
 * {@code RoleRotationWorldComponent} into the per-game draft state, but the
 * card-group bug survived the move:
 * <ul>
 *   <li>{@code getPlayerCardType} returns raw role-type ids (1=civilian,
 *       2=neutral, 4=killer) while the consumers expect card-group ids
 *       (0=killer, 1=neutral, 2=civilian, 3=neutral-for-killer). Remapped via
 *       {@link RotationCardGroups}.</li>
 *   <li>{@code assignRotationOrder} only switches 0/1/2, so group 3
 *       (杀手中立) fell into {@code noCardUsers}. HEAD-reimplemented so group 3
 *       shares the killer slot band (40%–50%) with overflow to nearest.</li>
 *   <li>{@code prepareCandidatesForPlayer} only gives candidate priority to
 *       card groups 0/1, so a neutral-for-killer card (3) and a civilian card
 *       (2) get no priority. The method is reimplemented with correct priority
 *       per card group.</li>
 * </ul>
 *
 * <h2>Role pool visibility</h2>
 * <p>{@code rolePool} is now filtered through habitrain_core's role-override
 * visibility resolver ({@link RolePoolVisibility}, reflective, no compile-time
 * core-implementation dependency) every time candidates are prepared. Candidate
 * selection therefore no longer assumes an unfiltered pool: a role that core
 * considers hidden or replaced is dropped, and every surviving entry is the
 * resolved (effective) role instance. If the resolver cannot be reached the filter
 * fails open and logs once, because an empty candidate list would break the mode
 * outright — see {@link RolePoolVisibility} for why fail-closed is reserved for the
 * security gates.
 *
 * <h2>Failure policy</h2>
 * <p>Every injection here is declared {@code require = 0} and lives in a mixin
 * config with {@code "required": false}. These injections are therefore
 * <em>non-fatal</em>: if upstream renames or removes a method, or an injection
 * point disappears, the operator sees an error from
 * {@code HabiLotteryMixinPlugin} and this mode degrades to upstream behaviour
 * instead of preventing the game from starting. The price is that upstream
 * behaviour is what a player gets in that case, so a release build should be
 * re-verified against a new SRE version — the fingerprint check below is there to
 * say loudly when that re-verification is due.
 */
@Mixin(value = SingleSelectDraftState.class, remap = false)
public abstract class RoleRotationCardMixin {

    @Shadow
    @Final
    private ArrayList<SRERole> rolePool;

    @Shadow
    @Final
    private Map<UUID, SRERole> selectedRoles;

    @Shadow
    @Final
    private Map<UUID, Integer> playerOrder;

    @Shadow
    private ArrayList<SRERole> currentCandidates;

    @Shadow
    private int finalPhaseThreshold;

    @Shadow
    private int totalPlayers;

    @Shadow
    private boolean isSpecialCivilianRole(SRERole role) {
        return false;
    }

    /** One-shot guard for {@link #habi$assertUpstreamFingerprint()}. */
    private static boolean habi$fingerprintChecked;

    /**
     * Remaps the raw forced role-type id to the card-group id the rotation consumers
     * expect (see the class javadoc).
     *
     * <p>This is the one place where a HEAD cancel is kept because it is a pure
     * mapping, but it is deliberately <em>narrow</em>: the remap only happens when
     * {@link PlayerRoleWeightManager#ForcePlayerTeam} actually holds an entry for the
     * uuid. Without that guard an unforced player would be rewritten from upstream's
     * value to {@code -1}, i.e. this mixin would silently change behaviour it does
     * not own. With the guard an absent uuid falls through to the upstream body, so
     * upstream stays the authority for "no card".
     *
     * <p>{@code require = 0}: see the class javadoc — a renamed upstream method is
     * reported and skipped, it never blocks startup.
     */
    @Inject(method = "getPlayerCardType", at = @At("HEAD"), cancellable = true, require = 0)
    private void habi$rotationCardGroup(UUID uuid, CallbackInfoReturnable<Integer> cir) {
        if (uuid == null || !PlayerRoleWeightManager.ForcePlayerTeam.containsKey(uuid)) {
            // Not ours: fall through to the upstream implementation.
            return;
        }
        cir.setReturnValue(RotationCardGroups.fromForcedType(PlayerRoleWeightManager.ForcePlayerTeam.get(uuid)));
    }

    /**
     * Deliberate full replacement of a small, pure ordering method. The upstream
     * switch is 0/1/2 only; group 3 shares the killer 40%–50% band and leftover
     * players use the nearest empty slot, so group 3 is never dumped into no-card.
     *
     * <p>Because this cancels the upstream body, any later SRE change to
     * {@code assignRotationOrder} will <b>not</b> take effect while this mixin
     * applies: the upstream body must be re-checked whenever SRE changes.
     * {@code require = 0} keeps that situation non-fatal (report + upstream fallback,
     * never a boot failure).
     */
    @Inject(method = "assignRotationOrder", at = @At("HEAD"), cancellable = true, require = 0)
    private void habi$assignRotationOrderWithKillerNeutral(CallbackInfo ci) {
        RotationCardGroups.assignRotationOrder(
                playerOrder,
                uuid -> PlayerRoleWeightManager.ForcePlayerTeam.get(uuid));
        ci.cancel();
    }

    /**
     * Reimplementation of {@code prepareCandidatesForPlayer} (adapted from the
     * SRE 4.3.0 card-priority fix): every card group now biases candidates
     * toward its own faction's roles, with a neutral-for-killer card (group 3)
     * prioritizing type-3 roles and a civilian card (group 2) prioritizing
     * type-1 roles.
     *
     * <p><b>This is a deliberate full replacement of an upstream method.</b> Because
     * the whole body is HEAD-cancelled and rebuilt here, upstream balance changes
     * made inside {@code prepareCandidatesForPlayer} (quota tweaks, new candidate
     * sources, extra filters) will <b>not</b> take effect while this mixin applies.
     * Re-check the upstream body on every SRE update; the fingerprint assertion
     * below reports a rename loudly, but it cannot detect a behavioural change.
     *
     * <p>The candidate pool is taken from {@link RolePoolVisibility#filterVisible}
     * rather than from the raw {@code rolePool}, so core's hidden / replaced role
     * filtering still holds for this rebuilt list.
     *
     * <p>{@code require = 0}: non-fatal, see the class javadoc.
     */
    @Inject(method = "prepareCandidatesForPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void habi$prepareCandidates(UUID playerUuid, CallbackInfo ci) {
        if (!habi$fingerprintChecked) {
            habi$fingerprintChecked = true;
            habi$assertUpstreamFingerprint();
        }

        currentCandidates.clear();

        // 检查是否处于最后阶段
        int remainingPlayers = totalPlayers - selectedRoles.size();
        boolean isFinalPhase = remainingPlayers <= finalPhaseThreshold;

        // 仅使用核心可见性过滤后的池：隐藏/被替换的角色不会重新进入候选。
        ArrayList<SRERole> poolCopy = RolePoolVisibility.filterVisible(rolePool);
        Random random = new Random();

        // 获取玩家卡片分组（0=杀手 1=中立 2=平民 3=杀手中立，-1=无卡）
        int cardType = RotationCardGroups.fromForcedType(PlayerRoleWeightManager.ForcePlayerTeam.get(playerUuid));

        // 卡片用户优先候选处理
        boolean cardPriorityHandled = false;
        if (cardType >= 0 && cardType <= 3) {
            ArrayList<SRERole> priorityRoles = new ArrayList<>();
            ArrayList<SRERole> otherRoles = new ArrayList<>();

            for (SRERole role : poolCopy) {
                int type = PlayerRoleWeightManager.getRoleType(role);
                if (RotationCardGroups.matchesRoleType(cardType, type)) {
                    priorityRoles.add(role);
                } else {
                    otherRoles.add(role);
                }
            }

            if (!priorityRoles.isEmpty() && priorityRoles.size() + otherRoles.size() >= 3) {
                // 从优先池中抽最多2个
                Collections.shuffle(priorityRoles, random);
                int priorityCount = Math.min(2, priorityRoles.size());
                for (int i = 0; i < priorityCount; i++) {
                    SRERole pri = priorityRoles.get(i);
                    currentCandidates.add(pri);
                    poolCopy.remove(pri); // consumed
                }
                // 剩余从普通池补
                Collections.shuffle(poolCopy, random);
                for (int i = 0; currentCandidates.size() < 3 && i < poolCopy.size(); i++) {
                    currentCandidates.add(poolCopy.get(i));
                }
                cardPriorityHandled = true;
            } else if (!otherRoles.isEmpty() && otherRoles.size() >= 3) {
                // 优先级不够但备用池够
                Collections.shuffle(otherRoles, random);
                for (int i = 0; i < 3; i++) {
                    currentCandidates.add(otherRoles.get(i));
                }
                cardPriorityHandled = true;
            }
        }

        if (!cardPriorityHandled) {
            if (isFinalPhase) {
                // 最后阶段：优先从杀手/警长/中立阵营和特殊平民职业抽取
                ArrayList<SRERole> priorityRoles = new ArrayList<>();
                for (SRERole role : poolCopy) {
                    int type = PlayerRoleWeightManager.getRoleType(role);
                    if (type == 4 || type == 5 || type == 2 || type == 3) {
                        priorityRoles.add(role);
                    } else if (isSpecialCivilianRole(role)) {
                        priorityRoles.add(role);
                    }
                }

                if (priorityRoles.size() >= 3) {
                    Collections.shuffle(priorityRoles, random);
                    for (int i = 0; i < 3; i++) {
                        currentCandidates.add(priorityRoles.get(i));
                    }
                } else {
                    Collections.shuffle(poolCopy, random);
                    for (int i = 0; i < 3 && i < poolCopy.size(); i++) {
                        currentCandidates.add(poolCopy.get(i));
                    }
                }
            } else {
                Collections.shuffle(poolCopy, random);
                for (int i = 0; i < 3 && i < poolCopy.size(); i++) {
                    currentCandidates.add(poolCopy.get(i));
                }
            }
        }
        ci.cancel();
    }

    /**
     * Upstream fingerprint assertion for the members this mixin injects into.
     *
     * <p>{@code require = 0} means a rename upstream degrades silently to upstream
     * behaviour; this helper is what turns that silence into an error naming the
     * missing member, so a release build can be re-verified after an SRE update. It
     * checks that {@link SingleSelectDraftState} still declares the three injected
     * methods and the four fields this mixin shadows — nothing else.
     *
     * <p>Cheap and side-effect free: it only reflects over the already-loaded target
     * class, never initialises or invokes anything, and is called once per JVM from
     * {@link #habi$prepareCandidates}.
     */
    private static void habi$assertUpstreamFingerprint() {
        try {
            Class<?> target = SingleSelectDraftState.class;

            Set<String> declaredMethods = new HashSet<>();
            for (Method method : target.getDeclaredMethods()) {
                declaredMethods.add(method.getName());
            }
            Set<String> declaredFields = new HashSet<>();
            for (Field field : target.getDeclaredFields()) {
                declaredFields.add(field.getName());
            }

            for (String name : new String[]{
                    "getPlayerCardType", "assignRotationOrder", "prepareCandidatesForPlayer"}) {
                if (!declaredMethods.contains(name)) {
                    HabiLotteryMod.LOGGER.error(
                            "habitrain_lottery upstream fingerprint: {} no longer declares method {}() — "
                                    + "the role-rotation card fix cannot inject there and the mode now runs on "
                                    + "upstream behaviour; re-check RoleRotationCardMixin against this SRE version",
                            target.getName(), name);
                }
            }
            for (String name : new String[]{
                    "rolePool", "selectedRoles", "playerOrder", "currentCandidates"}) {
                if (!declaredFields.contains(name)) {
                    HabiLotteryMod.LOGGER.error(
                            "habitrain_lottery upstream fingerprint: {} no longer declares field {} — "
                                    + "RoleRotationCardMixin shadows a member that disappeared; "
                                    + "re-check it against this SRE version",
                            target.getName(), name);
                }
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.error("habitrain_lottery upstream fingerprint check failed", t);
        }
    }
}

package com.habitrain.lottery.mixin;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
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

    @Inject(method = "getPlayerCardType", at = @At("HEAD"), cancellable = true)
    private void habi$rotationCardGroup(UUID uuid, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(RotationCardGroups.fromForcedType(PlayerRoleWeightManager.ForcePlayerTeam.get(uuid)));
    }

    /**
     * Upstream switch is 0/1/2 only. Group 3 shares the killer 40%–50% band;
     * leftover uses nearest empty slot. Never dumped into no-card.
     */
    @Inject(method = "assignRotationOrder", at = @At("HEAD"), cancellable = true)
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
     */
    @Inject(method = "prepareCandidatesForPlayer", at = @At("HEAD"), cancellable = true)
    private void habi$prepareCandidates(UUID playerUuid, CallbackInfo ci) {
        currentCandidates.clear();

        // 检查是否处于最后阶段
        int remainingPlayers = totalPlayers - selectedRoles.size();
        boolean isFinalPhase = remainingPlayers <= finalPhaseThreshold;

        ArrayList<SRERole> poolCopy = new ArrayList<>(rolePool);
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
}

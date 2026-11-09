package com.habitrain.lottery.mixin;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.card.CardUseService;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.game.modes.funny.rotation.LightningDraftState;
import io.wifi.starrailexpress.game.utils.RoleInstance;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Restores exact raw-type card matching and deduplicates card settlement in the
 * lightning role-rotation draft ({@code B-12}).
 *
 * <p>New SRE's {@link LightningDraftState#normalizeCardType(int)} collapses
 * type 5 (vigilante weight) → 1 and type 3 (neutral-for-killer) → 2 when
 * pre-assigning forced-faction candidates, so a 杀手中立卡 (type 3) user gets
 * neutral (type 2) candidates instead of type-3 roles. Redirecting
 * {@code normalizeCardType} to identity restores exact matching: card 3 users
 * get type-3 (杀手中立) candidates; the quota pooling in
 * {@code initializeCardTracking} keeps its upstream caps (call sites there are
 * intentionally not redirected).
 *
 * <p><b>Failure policy: non-fatal.</b> {@code require = 0} together with
 * {@code "required": false} in {@code habitrain_lottery.mixins.json} means this
 * redirect no longer holds the game hostage. If upstream renames
 * {@code normalizeCardType} or the {@code startNextRound} /
 * {@code roleMatchesFaction} call sites move, the mixin logs an error (via
 * {@link com.habitrain.lottery.mixinplugin.HabiLotteryMixinPlugin}) and is skipped: the mode then degrades to
 * upstream behaviour — type 3 is collapsed into type 2 again — instead of
 * preventing startup. That degradation is silent to players, so a release build
 * must be re-verified against a new SRE version.
 */
@Mixin(value = LightningDraftState.class, remap = false)
public abstract class RoleRotationLightningCardMixin {

    @Shadow
    public Map<UUID, List<RoleInstance>> roundCandidates;

    @Shadow
    @Final
    public Map<UUID, SRERole> selectedRoles;

    /**
     * Redirects {@code normalizeCardType} to identity at the two call sites that
     * pre-assign forced-faction candidates.
     *
     * <p>{@code require = 0}: a renamed {@code normalizeCardType} must not fail
     * startup. It is reported as an error and skipped, and the draft falls back to
     * upstream's type collapsing.
     */
    @Redirect(
            method = {
                    "startNextRound",
                    "roleMatchesFaction"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/game/modes/funny/rotation/LightningDraftState;normalizeCardType(I)I"
            ),
            require = 0
    )
    private static int habi$identityNormalizeCardType(int rawType) {
        return rawType;
    }

    /**
     * Routes upstream quota-cap and empty-pool refunds directly into idempotent
     * {@link CardUseService#refundForcedCard(UUID)} so the player receives their exact
     * original card type (including {@code type = 3} killer-neutral) and is never
     * refunded a second time at {@code OnGameTrueStarted}.
     */
    @Redirect(
            method = {
                    "initializeCardTracking",
                    "startNextRound"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/progression/ProgressionDataManager;addFactionCard(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/progression/ProgressionState$FactionCardType;I)V"
            ),
            require = 0
    )
    private static void habi$dedupeLightningCardRefund(
            ServerPlayer player,
            FactionCardType cardType,
            int amount) {
        if (player != null && ActiveCardForces.get(player.getUUID()) != null) {
            CardUseService.refundForcedCard(player.getUUID());
            return;
        }
        ProgressionDataManager.addFactionCard(player, cardType, amount);
    }

    /**
     * Marks a forced faction card as offered once the player's draft round presents at
     * least one candidate matching their forced role type, preventing voluntary skips
     * from claiming a refund at match start ({@code B-12}).
     */
    @Inject(method = "startNextRound", at = @At("RETURN"), require = 0)
    private void habi$markMatchingCandidatesOffered(ServerLevel world, CallbackInfo ci) {
        if (roundCandidates == null || roundCandidates.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, List<RoleInstance>> entry : roundCandidates.entrySet()) {
            UUID uid = entry.getKey();
            Integer forcedRoleType = ActiveCardForces.get(uid);
            List<RoleInstance> offered = entry.getValue();
            if (uid == null || forcedRoleType == null || offered == null || offered.isEmpty()) {
                continue;
            }
            for (RoleInstance instance : offered) {
                if (instance != null && instance.role() != null
                        && PlayerRoleWeightManager.getRoleType(instance.role()) == forcedRoleType) {
                    ActiveCardForces.markCandidateOffered(uid);
                    break;
                }
            }
        }
    }

    /**
     * Clears {@code CANDIDATE_OFFERED} for any card holder who genuinely picked a role matching
     * their forced faction before {@code adjustRoles} rebalances Civilian slots ({@code B-12}).
     */
    @Inject(method = "adjustRoles", at = @At("HEAD"), require = 0)
    private void habi$protectMatchedDraftPicksBeforeAdjust(ServerLevel serverWorld, CallbackInfo ci) {
        if (selectedRoles == null || selectedRoles.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, SRERole> entry : selectedRoles.entrySet()) {
            UUID uid = entry.getKey();
            SRERole picked = entry.getValue();
            Integer forcedRoleType = ActiveCardForces.get(uid);
            if (uid != null && picked != null && forcedRoleType != null
                    && PlayerRoleWeightManager.getRoleType(picked) == forcedRoleType) {
                ActiveCardForces.clearCandidateOffered(uid);
            }
        }
    }
}

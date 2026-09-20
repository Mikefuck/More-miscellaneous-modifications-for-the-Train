package com.habitrain.lottery.mixin;

import io.wifi.starrailexpress.game.modes.funny.rotation.LightningDraftState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Restores exact raw-type card matching in the lightning role-rotation draft.
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
 * {@link HabiLotteryMixinPlugin}) and is skipped: the mode then degrades to
 * upstream behaviour — type 3 is collapsed into type 2 again — instead of
 * preventing startup. That degradation is silent to players, so a release build
 * must be re-verified against a new SRE version.
 */
@Mixin(value = LightningDraftState.class, remap = false)
public abstract class RoleRotationLightningCardMixin {

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
}

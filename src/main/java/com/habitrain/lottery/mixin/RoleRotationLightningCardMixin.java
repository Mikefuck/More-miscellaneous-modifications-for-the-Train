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
 * <p>require=1 (json {@code required:true}): miss = boot fail, not a silent
 * collapse of type 3 → 2.
 */
@Mixin(value = LightningDraftState.class, remap = false)
public abstract class RoleRotationLightningCardMixin {

    /**
     * miss = boot fail. Do not set require=0: a renamed {@code normalizeCardType}
     * must fail startup rather than collapse type 3 → 2.
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
            require = 1
    )
    private static int habi$identityNormalizeCardType(int rawType) {
        return rawType;
    }
}

package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrateAnimationSettlementTest {
    @Test void interruptionRefundsUntilExactlyOneSecondBeforeStop() {
        CrateStage stage = CrateStage.opened(1000).result(1100);
        assertFalse(CrateAnimationSettlement.succeeds(stage, stage.stopAt() - 1001, false));
        assertTrue(CrateAnimationSettlement.succeeds(stage, stage.stopAt() - 1000, false));
        assertTrue(CrateAnimationSettlement.succeeds(stage, stage.stopAt(), false));
        assertTrue(CrateAnimationSettlement.succeeds(stage, stage.finishAt() + 1000, false));
    }

    @Test void delayedPreviewUsesActualReelStopInsteadOfTimeSinceClick() {
        CrateStage stage = CrateStage.opened(0).result(6000);
        assertFalse(CrateAnimationSettlement.succeeds(stage, 7000, false));
        assertTrue(CrateAnimationSettlement.succeeds(stage, stage.stopAt() - 1000, false));
    }

    @Test void explicitSkipSucceedsEvenBeforePreviewAndDoesNotTreatOtherRemovalAsSkip() {
        CrateStage stage = CrateStage.opened(0);
        assertFalse(CrateAnimationSettlement.succeeds(stage, 100, false));
        assertTrue(CrateAnimationSettlement.succeeds(stage, 100, true));
        assertFalse(CrateAnimationSettlement.succeeds(CrateStage.idle(), 100, true));
        assertFalse(CrateAnimationSettlement.succeeds(stage, 50_000, false));
    }
}

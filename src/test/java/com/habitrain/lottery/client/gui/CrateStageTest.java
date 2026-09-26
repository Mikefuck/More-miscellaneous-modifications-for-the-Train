package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 开箱时间轴的逐帧约束：相位顺序、参考视频的实测时长、曲线单调、与帧率无关、转盘坐标有界。
 */
class CrateStageTest {

    @Test void idleStageNeverMoves() {
        CrateStage stage = CrateStage.idle();
        assertFalse(stage.active());
        assertEquals(CrateStage.Phase.IDLE, stage.phase(0));
        assertEquals(0.0F, stage.lidAngle(5000));
        assertEquals(0.0F, stage.wipe(5000));
        assertEquals(0.0F, stage.dismiss(5000));
        assertEquals(0.0F, stage.hold(5000));
        assertEquals(0.0F, stage.bridge(5000));
        assertEquals(0.0F, stage.reelPosition(5000));
        // 未开始时转盘停在第 0 张，第 3 张就停在光标右侧 3 张卡片处
        assertEquals(3.0F, stage.reelOffset(5000, 3), 1e-6F);
        assertEquals(0.0F, stage.reelOffset(5000, 0), 1e-6F);
        assertFalse(stage.cardsGone(5000));
        assertFalse(stage.finished(1_000_000));
        // 入场动作不依赖 openedAt，界面一出现就能播
        assertEquals(1.0F, stage.crateScale(0L, 0L), 1e-6F);
    }

    /**
     * 参考视频的实测节奏必须逐项对上：确认后 333ms 撤 UI、再持握 1634ms 开盖、
     * 6033ms 三次曲线减速、100ms 停在黄线上、266ms 暗场、1533ms 放大登场。
     */
    @Test void timelineMatchesTheMeasuredReference() {
        CrateStage stage = CrateStage.opened(0L).result(0L);
        assertEquals(333L, CrateStage.DISMISS_MS);
        assertEquals(1634L, CrateStage.HOLD_MS);
        assertEquals(6033L, CrateStage.SPIN_MS);
        assertEquals(100L, CrateStage.STOP_HOLD_MS);
        assertEquals(266L, CrateStage.BRIDGE_MS);
        assertEquals(1533L, CrateStage.REVEAL_MS);
        assertEquals(0L, stage.openedAt());
        assertEquals(333L, stage.holdAt());
        assertEquals(1967L, stage.spinAt());
        assertEquals(1967L, stage.decayAt());
        assertEquals(8000L, stage.stopAt());
        assertEquals(8100L, stage.swapAt());
        // The item arrives while the world fades back, not after that fade has finished.
        assertEquals(8233L, stage.revealAt());
        assertEquals(9766L, stage.finishAt());
    }

    @Test void phasesFollowTheReferenceOrder() {
        CrateStage stage = CrateStage.opened(0L).result(0L);
        assertEquals(CrateStage.Phase.DISMISS, stage.phase(0));
        assertEquals(CrateStage.Phase.DISMISS, stage.phase(CrateStage.DISMISS_MS - 1));
        assertEquals(CrateStage.Phase.HOLD, stage.phase(CrateStage.DISMISS_MS));
        assertEquals(CrateStage.Phase.HOLD, stage.phase(stage.spinAt() - 1));
        assertEquals(CrateStage.Phase.CAROUSEL, stage.phase(stage.spinAt()));
        assertEquals(CrateStage.Phase.CAROUSEL, stage.phase(stage.swapAt() - 1));
        assertEquals(CrateStage.Phase.BRIDGE, stage.phase(stage.swapAt()));
        assertEquals(CrateStage.Phase.REVEAL, stage.phase(stage.revealAt()));
        assertTrue(stage.finished(stage.finishAt()));
        assertFalse(stage.finished(stage.finishAt() - 1));
    }

    @Test void aSlowServerSpinsAtFullSpeedUntilTheResultArrives() {
        // 结果没到之前保持 7 件/秒匀速，不会出现「停住等结果」
        long late = CrateStage.opened(0L).spinAt() + 4000L;
        CrateStage stage = CrateStage.opened(0L).result(late);
        assertEquals(late, stage.decayAt());
        assertEquals(late + CrateStage.SPIN_MS, stage.stopAt());
        // 匀速段：4 秒正好滚 7×4 = 28 张卡片
        assertEquals(CrateStage.REEL_SPEED * 4000.0F, stage.reelPosition(late), 1e-3F);
        assertEquals(0.0F, stage.reelPosition(stage.spinAt()), 1e-6F);
        assertEquals(0.0F, stage.reelPosition(stage.spinAt() - 500L), 1e-6F);
        // 结果早到也不会缩短转盘：完整 6.033s 减速照播
        CrateStage early = CrateStage.opened(0L).result(0L);
        assertEquals(early.spinAt(), early.decayAt());
    }

    @Test void aFailureStillPlaysTheDismissalThenReports() {
        CrateStage stage = CrateStage.opened(0L).failure();
        assertEquals(CrateStage.Phase.DISMISS, stage.phase(0));
        assertEquals(CrateStage.Phase.ERROR, stage.phase(CrateStage.DISMISS_MS));
        assertFalse(stage.failedVisible(CrateStage.DISMISS_MS - 1));
        assertTrue(stage.failedVisible(CrateStage.DISMISS_MS));
        assertEquals(0.0F, stage.settling(9999));
        assertEquals(0.0F, stage.revealing(9999));
    }

    @Test void entryDropsLinearlyAndRightsItselfWithEaseOut() {
        long entered = 1000L;
        assertEquals(0.0F, CrateStage.dropProgress(entered, entered), 1e-6F);
        assertEquals(0.0F, CrateStage.dropProgress(entered + CrateStage.DROP_DELAY_MS - 1, entered), 1e-6F);
        assertEquals(1.0F, CrateStage.dropProgress(entered + CrateStage.DROP_DELAY_MS + CrateStage.DROP_MS,
                entered), 1e-6F);
        // 参考视频的下落是线性的（无过冲），中点应正好落在一半
        assertEquals(0.5F, CrateStage.dropProgress(
                entered + CrateStage.DROP_DELAY_MS + CrateStage.DROP_MS / 2L, entered), 1e-3F);
        assertEquals(CrateStage.START_ROLL, CrateStage.entryRoll(entered, entered), 1e-4F);
        assertEquals(0.0F, CrateStage.entryRoll(entered + CrateStage.RIGHT_DELAY_MS + CrateStage.RIGHT_MS,
                entered), 1e-3F);
        assertEquals(CrateStage.START_YAW, CrateStage.entryYaw(entered, entered), 1e-4F);
        assertEquals(0.0F, CrateStage.entryYaw(entered + CrateStage.RIGHT_DELAY_MS + CrateStage.RIGHT_MS,
                entered), 1e-3F);
        float previous = Float.MAX_VALUE;
        for (long t = entered; t <= entered + CrateStage.RIGHT_DELAY_MS + CrateStage.RIGHT_MS + 200; t += 5) {
            float roll = CrateStage.entryRoll(t, entered);
            assertTrue(roll <= previous + 1e-4F, "righting must not tip back over");
            assertTrue(roll >= -1e-3F, "the crate must never roll past upright");
            previous = roll;
        }
    }

    @Test void stripAndModalFadeInOnTheReferenceSchedule() {
        long entered = 500L;
        assertEquals(0.0F, CrateStage.stripIn(entered, entered), 1e-6F);
        assertEquals(0.0F, CrateStage.stripIn(entered + CrateStage.STRIP_DELAY_MS - 1, entered), 1e-6F);
        assertEquals(1.0F, CrateStage.stripIn(entered + CrateStage.STRIP_DELAY_MS + CrateStage.STRIP_MS,
                entered), 1e-6F);
        // 上滑 28px：开始时整段位移，结束时归零
        assertEquals(CrateStage.STRIP_RISE_PX, CrateStage.stripOffset(entered, entered), 1e-4F);
        assertEquals(0.0F, CrateStage.stripOffset(
                entered + CrateStage.STRIP_DELAY_MS + CrateStage.STRIP_MS, entered), 1e-4F);

        assertEquals(0.0F, CrateStage.modalIn(entered, entered), 1e-6F);
        assertEquals(0.0F, CrateStage.modalIn(entered + CrateStage.MODAL_DELAY_MS - 1, entered), 1e-6F);
        assertEquals(1.0F, CrateStage.modalIn(entered + CrateStage.MODAL_DELAY_MS + CrateStage.MODAL_MS,
                entered), 1e-6F);
    }

    @Test void lidOpensExactlyWhenTheCarouselStarts() {
        CrateStage stage = CrateStage.opened(0L);
        assertEquals(0.0F, stage.lidAngle(stage.spinAt() - 1), 1e-4F);
        assertEquals(0.0F, stage.lidAngle(0), 1e-4F);
        float end = stage.lidAngle(stage.spinAt() + CrateStage.LID_MS);
        assertTrue(end >= CrateStage.LID_OPEN_DEGREES, "lid must reach the reference angle");
        assertEquals(end, stage.lidAngle(stage.spinAt() + 5000), 1e-3F);
        // 过冲后回落：中途必须出现过比终值更大的角度
        float max = 0.0F;
        for (long t = stage.spinAt(); t <= stage.spinAt() + CrateStage.LID_MS + 400; t += 8) {
            max = Math.max(max, stage.lidAngle(t));
        }
        assertTrue(max > end, "easeOutBack should overshoot before settling");
    }

    @Test void crateDolliesToTheReferenceSizeThenFallsBack() {
        long entered = 0L;
        // 玩家总在弹窗淡入之后才可能确认，所以 spinAt 一定晚于推镜结束
        CrateStage stage = CrateStage.opened(1500L);
        assertEquals(1.0F, CrateStage.idle().crateScale(entered, entered), 1e-6F);
        assertTrue(stage.spinAt() > entered + CrateStage.DOLLY_DELAY_MS + CrateStage.DOLLY_MS);
        // 参考视频把箱子从 327px 推到 571px（1.75×）
        assertEquals(CrateStage.DOLLY_TO,
                stage.crateScale(entered + CrateStage.DOLLY_DELAY_MS + CrateStage.DOLLY_MS, entered), 1e-3F);
        // 转盘开始后箱子退成背景
        assertEquals(CrateStage.BACKDROP_SCALE,
                stage.crateScale(stage.spinAt() + CrateStage.BACKDROP_MS, entered), 1e-3F);
        float previous = 0.0F;
        for (long t = entered; t <= stage.spinAt() + CrateStage.BACKDROP_MS + 100; t += 7) {
            float v = stage.crateScale(t, entered);
            assertTrue(v >= 0.2F && v <= CrateStage.BACKDROP_SCALE + 1e-3F);
            previous = v;
        }
        assertTrue(previous > 0.0F);
    }

    @Test void reelTravelMatchesTheFittedVelocityCurve() {
        // ∫₀¹ V0(1−u)³ + V1 du · T = T·(V0/4 + V1)
        float expected = CrateStage.SPIN_MS * ((CrateStage.REEL_SPEED - CrateStage.REEL_TAIL_SPEED) * 0.25F + CrateStage.REEL_TAIL_SPEED);
        assertEquals(expected, CrateStage.reelTravel(1.0F), 1e-2F);
        assertEquals(0.0F, CrateStage.reelTravel(0.0F), 1e-4F);
        // 起手速度就是参考视频实测的 7 件/秒，并且全程单调、逐渐放慢
        float previousStep = Float.MAX_VALUE;
        for (float u = 0.0F; u < 0.999F; u += 0.01F) {
            float step = CrateStage.reelTravel(u + 0.01F) - CrateStage.reelTravel(u);
            assertTrue(step <= previousStep + 1e-4F, "the reel must decelerate monotonically");
            previousStep = step;
        }
        assertEquals(CrateStage.REEL_SPEED * 1000.0F, 7.0F, 0.11F);
    }

    @Test void reelPositionIsContinuousAndStopsAfterSettling() {
        CrateStage stage = CrateStage.opened(0L).result(300L);
        float previous = Float.NaN;
        for (long t = stage.spinAt(); t <= stage.stopAt() + 500L; t += 7) {
            float position = stage.reelPosition(t);
            if (!Float.isNaN(previous)) {
                assertTrue(position >= previous - 1e-3F, "reel must not rewind");
                // 滚动速度有上界：任何一帧的位移都不该超过匀速段的理论值
                assertTrue(position - previous < CrateStage.REEL_SPEED * 8.0F + 1e-3F, "no reel jumps");
            }
            previous = position;
        }
        float settled = stage.reelPosition(stage.stopAt());
        assertEquals(settled, stage.reelPosition(stage.stopAt() + 10_000L), 1e-3F);
        assertTrue(settled > 1.0F, "the reel must actually scroll a few cards");
    }

    @Test void reelLandsExactlyOnACardBoundary() {
        CrateStage stage = CrateStage.opened(0L).result(600L);
        float position = stage.reelPosition(stage.stopAt());
        assertEquals((float) Math.round(position), position, 1e-3F, "the reel must stop on a card slot");
        assertEquals(0.0F, stage.reelOffset(stage.stopAt(), stage.cursorSlot(stage.stopAt())), 1e-3F);
    }

    @Test void integerLandingCorrectionDoesNotAccelerateIntoTheStop() {
        for (long received : new long[]{0, 2400, 4000, 7000}) {
            CrateStage stage = CrateStage.opened(0).result(received);
            float previous = Float.MAX_VALUE;
            for (long t = stage.decayAt() + 50; t <= stage.stopAt(); t += 50) {
                float step = stage.reelPosition(t) - stage.reelPosition(t - 50);
                assertTrue(step >= -0.00001F);
                assertTrue(step <= previous + .00002F, "landing correction must not speed up the tail");
                previous = step;
            }
            assertTrue(stage.reelPosition(stage.stopAt()) - stage.reelPosition(stage.stopAt() - 50) < .003F);
        }
    }

    @Test void cursorSlotIsTheOneUnderTheCursor() {
        CrateStage stage = CrateStage.opened(0L).result(2400L);
        long frozen = stage.stopAt();
        int slot = stage.cursorSlot(frozen);
        assertTrue(slot >= 0 && slot < CrateStage.SLOTS);
        // 结果物品就放在这个槽位，因此它必须落在光标半张卡片以内
        assertTrue(Math.abs(stage.reelOffset(frozen, slot)) <= 0.5F + 1e-3F);
    }

    @Test void reelOffsetsWrapInsideHalfTheReel() {
        CrateStage stage = CrateStage.opened(0L).result(900L);
        for (long t = stage.spinAt(); t < stage.finishAt() + 200; t += 11) {
            float[] offsets = new float[CrateStage.SLOTS];
            for (int i = 0; i < CrateStage.SLOTS; i++) {
                float offset = stage.reelOffset(t, i);
                assertTrue(Math.abs(offset) <= CrateStage.SLOTS * 0.5F + 1e-3F,
                        "a slot must stay within half a reel of the cursor");
                offsets[i] = offset;
            }
            // 相邻卡片必须落在互不相同的位置上，否则会出现重叠的幽灵卡片
            for (int i = 0; i < offsets.length; i++) {
                for (int j = i + 1; j < offsets.length; j++) {
                    assertTrue(Math.abs(offsets[i] - offsets[j]) > 0.5F, "slots must not overlap");
                }
            }
        }
    }

    @Test void bridgeHoldsBlackThenLiftsOnce() {
        CrateStage stage = CrateStage.opened(0L).result(0L);
        long swap = stage.swapAt();
        assertFalse(stage.cardsGone(swap - 1));
        assertTrue(stage.cardsGone(swap));
        assertTrue(stage.cardsGone(stage.revealAt()));
        assertEquals(1.0F, stage.bridge(swap), 1e-6F);
        assertEquals(1.0F, stage.bridge(swap + ScreenSwap.SWAP_BLACK_MS - 1), 1e-6F);
        assertEquals(1.0F, stage.bridge(stage.revealAt()), 1e-6F);
        assertEquals(0.0F, stage.bridge(stage.swapAt() + CrateStage.BRIDGE_MS), 1e-6F);
        float previous = 2.0F;
        for (long t = swap; t <= stage.revealAt(); t += 13) {
            float v = stage.bridge(t);
            assertTrue(v <= previous + 1e-6F, "the bridge must only fade out");
            previous = v;
        }
        assertEquals(0.0F, CrateStage.idle().bridge(swap), 1e-6F);
    }

    @Test void curvesAreMonotonicAtEverySamplingRate() {
        CrateStage stage = CrateStage.opened(0L).result(500L);
        for (int step : new int[]{5, 16, 33}) {
            float previousSettle = -1.0F, previousReveal = -1.0F, previousWipe = -1.0F;
            for (long t = 0; t < stage.finishAt() + 200; t += step) {
                float settle = stage.settling(t), reveal = stage.revealing(t), wipe = stage.wipe(t);
                assertTrue(settle >= previousSettle && settle >= 0.0F && settle <= 1.0F);
                assertTrue(reveal >= previousReveal && reveal >= 0.0F && reveal <= 1.0F);
                assertTrue(wipe >= previousWipe && wipe >= 0.0F && wipe <= 1.0F);
                previousSettle = settle;
                previousReveal = reveal;
                previousWipe = wipe;
            }
            assertEquals(1.0F, previousSettle, 1e-6F);
            assertEquals(1.0F, previousReveal, 1e-6F);
            assertEquals(1.0F, previousWipe, 1e-6F);
        }
        assertEquals(0.0F, stage.settling(stage.decayAt() - 1));
        assertEquals(1.0F, stage.settling(stage.stopAt()));
        assertEquals(0.0F, stage.revealing(stage.revealAt() - 1));
        assertEquals(1.0F, stage.revealing(stage.revealAt() + CrateStage.REVEAL_MS));
    }

    @Test void cardsShrinkAndFadeWithDistance() {
        assertEquals(1.0F, CrateStage.cardScale(0.0F), 1e-6F);
        assertEquals(1.0F, CrateStage.cardTint(0.0F), 1e-6F);
        assertEquals(0.0F, CrateStage.cardHaze(0.0F), 1e-6F);
        float previousScale = Float.MAX_VALUE, previousTint = Float.MAX_VALUE, previousHaze = -1.0F;
        for (float d = 0.0F; d <= CrateStage.VISIBLE_SPAN; d += 0.1F) {
            float scale = CrateStage.cardScale(d);
            float tint = CrateStage.cardTint(d);
            float haze = CrateStage.cardHaze(d);
            assertTrue(scale <= previousScale + 1e-6F && scale > 0.0F);
            assertTrue(tint <= previousTint + 1e-6F && tint > 0.0F && tint <= 1.0F);
            assertTrue(haze >= previousHaze - 1e-6F && haze >= 0.0F && haze <= 1.0F);
            previousScale = scale;
            previousTint = tint;
            previousHaze = haze;
        }
        assertEquals(CrateStage.cardScale(-1.5F), CrateStage.cardScale(1.5F), 1e-6F);
        assertEquals(CrateStage.cardHaze(-2.0F), CrateStage.cardHaze(2.0F), 1e-6F);
    }

    @Test void easingEndpointsAreExact() {
        assertEquals(0.0F, CrateStage.easeOutCubic(0.0F), 1e-6F);
        assertEquals(1.0F, CrateStage.easeOutCubic(1.0F), 1e-6F);
        assertEquals(0.0F, CrateStage.easeInCubic(0.0F), 1e-6F);
        assertEquals(1.0F, CrateStage.easeInCubic(1.0F), 1e-6F);
        assertEquals(0.0F, CrateStage.easeInOutCubic(0.0F), 1e-6F);
        assertEquals(1.0F, CrateStage.easeInOutCubic(1.0F), 1e-6F);
        assertEquals(0.5F, CrateStage.easeInOutCubic(0.5F), 1e-6F);
        assertEquals(0.0F, CrateStage.easeOutBack(0.0F, 1.35F), 1e-6F);
        assertEquals(1.0F, CrateStage.easeOutBack(1.0F, 1.35F), 1e-6F);
        assertEquals(0.0F, CrateStage.lerp(0.0F, 10.0F, -3.0F), 1e-6F);
        assertEquals(10.0F, CrateStage.lerp(0.0F, 10.0F, 4.0F), 1e-6F);
    }
}

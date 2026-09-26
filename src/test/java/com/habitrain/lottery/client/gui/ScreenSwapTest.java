package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 转场语汇的逐帧约束：屏幕之间是硬切、到达是 533ms 合焦、内容互换是
 * 「一帧撤 → 100ms 近黑 → 133ms 淡回」，两个方向共用同一组曲线。
 */
class ScreenSwapTest {

    @Test void arriveIsMonotonicAndClamped() {
        for (int step : new int[]{3, 7, 16, 33, 100}) {
            float previous = -1.0F;
            for (long t = 0; t < ScreenSwap.ARRIVE_MS + 200; t += step) {
                float v = ScreenSwap.arrive(t, 0L);
                assertTrue(v >= previous && v >= 0.0F && v <= 1.0F);
                previous = v;
            }
            assertEquals(1.0F, previous, 1e-6F);
        }
    }

    @Test void screenSwitchIsAHardCut() {
        // 参考视频 f041→f042 是一帧硬切：没有离场窗口、没有缩放、没有位移、没有暗场。
        assertEquals(1.0F, ScreenSwap.depart(0L, 0L));
        assertEquals(1.0F, ScreenSwap.depart(0L, 12345L));
        assertFalse(ScreenSwap.departing(0L, 0L));
        assertFalse(ScreenSwap.departing(999L, 0L));
        assertTrue(ScreenSwap.departed(0L, 0L));
        for (float p = 0.0F; p <= 1.0F; p += 0.05F) {
            assertEquals(1.0F, ScreenSwap.departZoom(p), 1e-6F);
            assertEquals(0.0F, ScreenSwap.departShift(p), 1e-6F);
            assertEquals(0.0F, ScreenSwap.bridge(0L, 0L, -1L), 1e-6F);
            assertEquals(0.0F, ScreenSwap.bridge(500L, -1L, 0L), 1e-6F);
            assertEquals(0.0F, ScreenSwap.bridge(900L, -1L, -1L), 1e-6F);
        }
        assertEquals(0L, ScreenSwap.DEPART_MS);
    }

    @Test void missingTransitionMeansSettled() {
        // at < 0：没有进行中的转场，入场视为已完成、离场视为未开始。
        assertEquals(0.0F, ScreenSwap.depart(1234L, -1L));
        assertEquals(1.0F, ScreenSwap.arrive(1234L, -1L));
        assertFalse(ScreenSwap.departing(1234L, -1L));
        assertFalse(ScreenSwap.arriving(1234L, -1L));
        assertTrue(ScreenSwap.departed(1234L, -1L));
    }

    @Test void arrivalFocusWindowEndsExactlyAtTheDuration() {
        assertTrue(ScreenSwap.arriving(0L, 0L));
        assertTrue(ScreenSwap.arriving(ScreenSwap.ARRIVE_MS - 1, 0L));
        assertFalse(ScreenSwap.arriving(ScreenSwap.ARRIVE_MS, 0L));
        assertEquals(533L, ScreenSwap.ARRIVE_MS);
    }

    @Test void arrivalPullsFocusFromBlurredAndSlightlyLarger() {
        // 硬切之后的世界是失焦且略大的，533ms 里收回清晰与 1.0
        assertEquals(ScreenSwap.ARRIVE_SCALE_FROM, ScreenSwap.arriveZoom(0.0F), 1e-6F);
        assertEquals(1.0F, ScreenSwap.arriveZoom(1.0F), 1e-6F);
        assertEquals(ScreenSwap.DEFOCUS_PEAK, ScreenSwap.defocus(0.0F), 1e-6F);
        assertEquals(0.0F, ScreenSwap.defocus(1.0F), 1e-6F);
        float previousZoom = Float.MAX_VALUE, previousDefocus = Float.MAX_VALUE;
        for (float p = 0.0F; p <= 1.0F; p += 0.02F) {
            float zoom = ScreenSwap.arriveZoom(p);
            float defocus = ScreenSwap.defocus(p);
            assertTrue(zoom <= previousZoom + 1e-6F, "the pull-in must only shrink toward 1.0");
            assertTrue(defocus <= previousDefocus + 1e-6F, "defocus must only clear");
            assertTrue(zoom >= 0.99F && zoom <= ScreenSwap.ARRIVE_SCALE_FROM + 1e-6F);
            assertTrue(defocus >= 0.0F && defocus <= ScreenSwap.DEFOCUS_PEAK + 1e-6F);
            previousZoom = zoom;
            previousDefocus = defocus;
        }
    }

    @Test void hudFadesInOnlyAfterTheHold() {
        // HUD 在合焦窗口的后 333ms 才淡入（参考 f050→f060）
        assertEquals(333L, ScreenSwap.HUD_MS);
        assertEquals(200L, ScreenSwap.HOLD_MS);
        assertEquals(0.0F, ScreenSwap.arriveFade(0.0F), 1e-6F);
        assertEquals(0.0F, ScreenSwap.arriveFade(ScreenSwap.HOLD_MS / (float) ScreenSwap.ARRIVE_MS), 1e-6F);
        assertEquals(1.0F, ScreenSwap.arriveFade(1.0F), 1e-6F);
        float previous = -1.0F;
        for (float p = 0.0F; p <= 1.0F; p += 0.01F) {
            float v = ScreenSwap.arriveFade(p);
            assertTrue(v >= previous - 1e-6F && v >= 0.0F && v <= 1.0F);
            previous = v;
        }
        assertEquals(1.0F, previous, 1e-6F);
    }

    @Test void contentBridgeMatchesTheReferenceBlackFrame() {
        // 一帧撤掉旧内容 + 100ms 近黑 + 133ms 淡回，全程没有任何白光
        assertEquals(33L, ScreenSwap.SWAP_REMOVE_MS);
        assertEquals(100L, ScreenSwap.SWAP_HOLD_MS);
        assertEquals(133L, ScreenSwap.SWAP_FADE_MS);
        assertEquals(266L, ScreenSwap.SWAP_MS);
        assertEquals(133L, ScreenSwap.SWAP_BLACK_MS);
        assertEquals(0.0F, ScreenSwap.contentBridge(999L, -1L), 1e-6F);
        assertEquals(0.0F, ScreenSwap.contentBridge(999L, 1000L), 1e-6F);
        assertEquals(1.0F, ScreenSwap.contentBridge(1000L, 1000L), 1e-6F);
        assertEquals(1.0F, ScreenSwap.contentBridge(1000L + ScreenSwap.SWAP_BLACK_MS - 1, 1000L), 1e-6F);
        assertEquals(1.0F, ScreenSwap.contentBridge(1000L + ScreenSwap.SWAP_BLACK_MS, 1000L), 1e-6F);
        assertEquals(0.0F, ScreenSwap.contentBridge(1000L + ScreenSwap.SWAP_MS, 1000L), 1e-6F);
        assertEquals(1000L + ScreenSwap.SWAP_BLACK_MS, ScreenSwap.contentBridgeFadeAt(1000L));
        // 淡回段必须单向下滑
        float previous = 2.0F;
        for (long t = 1000L; t <= 1000L + ScreenSwap.SWAP_MS; t += 7L) {
            float v = ScreenSwap.contentBridge(t, 1000L);
            assertTrue(v <= previous + 1e-6F, "the bridge must only fade out");
            previous = v;
        }
        assertEquals(0.0F, previous, 1e-6F);
    }
}

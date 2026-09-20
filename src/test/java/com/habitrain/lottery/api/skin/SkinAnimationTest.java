package com.habitrain.lottery.api.skin;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Value semantics and validation of {@link SkinAnimation}. */
class SkinAnimationTest {

    @Test
    void defaultBuilderProducesAnInertAnimation() {
        SkinAnimation none = SkinAnimation.builder().build();

        assertFalse(none.isAnimated());
        assertFalse(none.spins());
        for (SkinAnimation.Slot slot : SkinAnimation.Slot.values()) {
            assertFalse(none.animates(slot));
        }
        assertFalse(SkinAnimation.none().isAnimated());
    }

    @Test
    void spinEnablesEverySlotByDefaultAndRespectsAnExplicitScope() {
        SkinAnimation all = SkinAnimation.builder()
                .spin(SkinAnimation.SpinAxis.Y, 4.0F)
                .build();
        assertTrue(all.isAnimated());
        assertTrue(all.spins());
        assertTrue(all.animates(SkinAnimation.Slot.GUI));
        assertTrue(all.animates(SkinAnimation.Slot.THROWN));
        assertTrue(all.animates(SkinAnimation.Slot.HELD));
        assertFalse(all.animates(null));

        SkinAnimation thrownOnly = SkinAnimation.builder()
                .spin(SkinAnimation.SpinAxis.Y, 4.0F)
                .thrownSpinMultiplier(3.0F)
                .slots(SkinAnimation.Slot.THROWN)
                .build();
        assertTrue(thrownOnly.animates(SkinAnimation.Slot.THROWN));
        assertFalse(thrownOnly.animates(SkinAnimation.Slot.GUI));
        assertEquals(3.0F, thrownOnly.thrownSpinMultiplier());
    }

    @Test
    void slotsAreDefensivelyCopiedAndUnmodifiable() {
        Set<SkinAnimation.Slot> mutable = EnumSet.of(SkinAnimation.Slot.GUI);
        SkinAnimation animation = new SkinAnimation(SkinAnimation.SpinAxis.Y, 1.0F, 1.0F,
                0.0F, 90.0F, 0.0F, 100.0F, 0.0F, 60.0F, mutable);
        mutable.add(SkinAnimation.Slot.THROWN);

        assertEquals(EnumSet.of(SkinAnimation.Slot.GUI), animation.slots());
        assertThrows(UnsupportedOperationException.class,
                () -> animation.slots().add(SkinAnimation.Slot.THROWN));

        // A null axis and empty slot set fall back to their defaults instead of throwing.
        SkinAnimation defaults = new SkinAnimation(null, 0.0F, 1.0F, 0.0F, 90.0F,
                0.0F, 100.0F, 0.0F, 60.0F, EnumSet.noneOf(SkinAnimation.Slot.class));
        assertEquals(SkinAnimation.SpinAxis.Y, defaults.spinAxis());
        assertEquals(EnumSet.allOf(SkinAnimation.Slot.class), defaults.slots());
    }

    @Test
    void amplitudesAndPeriodsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().spin(SkinAnimation.SpinAxis.Y, 200.0F).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().spin(SkinAnimation.SpinAxis.Y, Float.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().thrownSpinMultiplier(100.0F).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().wobble(90.0F, 90.0F).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().bob(1.0F, 0.0F).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().pulse(10.0F, 60.0F).build());
        assertThrows(IllegalArgumentException.class,
                () -> SkinAnimation.builder().pulse(0.1F, SkinAnimation.MAX_PERIOD_TICKS + 1.0F).build());
    }

    @Test
    void equalityIsValueBasedSoARegistrarReplayIsIdempotent() {
        SkinAnimation first = SkinAnimation.builder()
                .spin(SkinAnimation.SpinAxis.Y, 4.0F)
                .pulse(0.08F, 45.0F)
                .build();
        SkinAnimation second = SkinAnimation.builder()
                .spin(SkinAnimation.SpinAxis.Y, 4.0F)
                .pulse(0.08F, 45.0F)
                .build();

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}

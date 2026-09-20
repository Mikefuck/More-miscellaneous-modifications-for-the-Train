package com.habitrain.lottery.api.skin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative client-side animation for one skin's item model.
 *
 * <p>This is the "motion" half of a skin: {@link HabiSkinApi} says which model to
 * bake, this says how that model moves. It is registered with
 * {@link SkinEffects#registerAnimation(String, String, SkinAnimation)} and is applied
 * by the client wherever the skin's model is rendered — inventory, hotbar, hand,
 * item frames/ground and the projectile renderer of a thrown skinned item.</p>
 *
 * <p><b>Every quantity is a continuous, periodic function of one monotonic
 * clock</b> (wall-clock ticks, 50 per second), which is what makes the animation
 * seam-free by construction: there are no keyframes to interpolate between and no
 * state to reset, so pausing, opening a screen, a resource reload, a dimension
 * change or a respawn cannot make it stutter or jump.</p>
 *
 * <ul>
 *   <li>{@link #spinDegreesPerTick()} — constant angular speed about
 *       {@link #spinAxis()}, multiplied by {@link #thrownSpinMultiplier()} in the
 *       {@link Slot#THROWN} context;</li>
 *   <li>{@link #wobbleDegrees()} / {@link #wobblePeriodTicks()} — sinusoidal tilt
 *       about the X axis (a slow precession);</li>
 *   <li>{@link #bobUnits()} / {@link #bobPeriodTicks()} — sinusoidal vertical float,
 *       in model units (16 units = 1 block, i.e. 1/16 block per unit);</li>
 *   <li>{@link #pulseAmount()} / {@link #pulsePeriodTicks()} — sinusoidal uniform
 *       scale around {@code 1.0}.</li>
 * </ul>
 *
 * <p>Rotations are applied about the <em>model centre</em> (not the model origin),
 * so a spinning model spins in place instead of orbiting its corner. All three
 * oscillations pass through their neutral value at {@code t = 0}.</p>
 *
 * <p>Instances are immutable value objects; the client caches one wrapped model per
 * (skin, baked model), so building a new instance is only a registration-time cost.</p>
 */
public record SkinAnimation(
        SpinAxis spinAxis,
        float spinDegreesPerTick,
        float thrownSpinMultiplier,
        float wobbleDegrees,
        float wobblePeriodTicks,
        float bobUnits,
        float bobPeriodTicks,
        float pulseAmount,
        float pulsePeriodTicks,
        Set<Slot> slots) {

    /** Rotation axis of {@link #spinDegreesPerTick()}; {@code Y} spins in place like a turntable. */
    public enum SpinAxis {
        X,
        Y,
        Z
    }

    /**
     * Where the animation runs. These are the multi-slot buckets of
     * {@code ItemDisplayContext}: {@code HELD} covers both first- and third-person
     * hands, {@code THROWN} is the flying-projectile context of
     * {@code ThrownItemRenderer}, {@code GUI} covers inventory, hotbar and the
     * wardrobe's flat preview.
     */
    public enum Slot {
        GUI,
        HELD,
        THROWN,
        FIXED,
        HEAD
    }

    /** Refused above this: 180 deg/tick is a full turn per tick, i.e. already strobing. */
    public static final float MAX_SPIN_DEGREES_PER_TICK = 180.0F;
    public static final float MAX_THROWN_SPIN_MULTIPLIER = 16.0F;
    public static final float MAX_WOBBLE_DEGREES = 45.0F;
    public static final float MAX_BOB_UNITS = 8.0F;
    public static final float MIN_PULSE_AMOUNT = -0.9F;
    public static final float MAX_PULSE_AMOUNT = 4.0F;
    /** Refused above this: a 2-minute period is already below any useful animation. */
    public static final float MAX_PERIOD_TICKS = 2400.0F;

    public SkinAnimation {
        spinAxis = spinAxis == null ? SpinAxis.Y : spinAxis;
        slots = slots == null || slots.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.allOf(Slot.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(slots));
        spinDegreesPerTick = bounded("spinDegreesPerTick", spinDegreesPerTick,
                -MAX_SPIN_DEGREES_PER_TICK, MAX_SPIN_DEGREES_PER_TICK);
        thrownSpinMultiplier = bounded("thrownSpinMultiplier", thrownSpinMultiplier,
                0.0F, MAX_THROWN_SPIN_MULTIPLIER);
        wobbleDegrees = bounded("wobbleDegrees", wobbleDegrees, -MAX_WOBBLE_DEGREES, MAX_WOBBLE_DEGREES);
        bobUnits = bounded("bobUnits", bobUnits, -MAX_BOB_UNITS, MAX_BOB_UNITS);
        pulseAmount = bounded("pulseAmount", pulseAmount, MIN_PULSE_AMOUNT, MAX_PULSE_AMOUNT);
        wobblePeriodTicks = period("wobblePeriodTicks", wobblePeriodTicks);
        bobPeriodTicks = period("bobPeriodTicks", bobPeriodTicks);
        pulsePeriodTicks = period("pulsePeriodTicks", pulsePeriodTicks);
    }

    /**
     * A static animation: nothing to apply. Registering it is legal and simply leaves
     * the model exactly as it was baked.
     */
    public static SkinAnimation none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** True when at least one of the four motions is configured with a non-zero amplitude. */
    public boolean isAnimated() {
        return spinDegreesPerTick != 0.0F
                || wobbleDegrees != 0.0F
                || bobUnits != 0.0F
                || pulseAmount != 0.0F;
    }

    /** True when this animation should run in {@code slot} and actually moves something. */
    public boolean animates(Slot slot) {
        return slot != null && slots.contains(slot) && isAnimated();
    }

    /** True when the model can spin in place (used for the fast in-flight spin). */
    public boolean spins() {
        return spinDegreesPerTick != 0.0F;
    }

    private static float bounded(String name, float value, float min, float max) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be within [" + min + ", " + max + "], got " + value);
        }
        return value;
    }

    private static float period(String name, float value) {
        if (!Float.isFinite(value) || value <= 0.0F || value > MAX_PERIOD_TICKS) {
            throw new IllegalArgumentException(
                    name + " must be within (0, " + MAX_PERIOD_TICKS + "], got " + value);
        }
        return value;
    }

    /** Fluent builder with neutral defaults; every amplitude starts disabled. */
    public static final class Builder {
        private SpinAxis spinAxis = SpinAxis.Y;
        private float spinDegreesPerTick;
        private float thrownSpinMultiplier = 1.0F;
        private float wobbleDegrees;
        private float wobblePeriodTicks = 90.0F;
        private float bobUnits;
        private float bobPeriodTicks = 100.0F;
        private float pulseAmount;
        private float pulsePeriodTicks = 60.0F;
        private Set<Slot> slots = EnumSet.allOf(Slot.class);

        private Builder() {
        }

        /** Constant spin about {@code axis}; negative values spin the other way. */
        public Builder spin(SpinAxis axis, float degreesPerTick) {
            this.spinAxis = Objects.requireNonNull(axis, "axis");
            this.spinDegreesPerTick = degreesPerTick;
            return this;
        }

        /** Spin speed multiplier used while the item is a flying projectile; {@code 0} freezes it. */
        public Builder thrownSpinMultiplier(float multiplier) {
            this.thrownSpinMultiplier = multiplier;
            return this;
        }

        /** Sinusoidal tilt about X, in degrees, over {@code periodTicks}. */
        public Builder wobble(float degrees, float periodTicks) {
            this.wobbleDegrees = degrees;
            this.wobblePeriodTicks = periodTicks;
            return this;
        }

        /** Sinusoidal vertical float, in model units (16 = 1 block), over {@code periodTicks}. */
        public Builder bob(float units, float periodTicks) {
            this.bobUnits = units;
            this.bobPeriodTicks = periodTicks;
            return this;
        }

        /** Sinusoidal uniform scale around 1.0, over {@code periodTicks}. */
        public Builder pulse(float amount, float periodTicks) {
            this.pulseAmount = amount;
            this.pulsePeriodTicks = periodTicks;
            return this;
        }

        /** Restricts the animation to the listed contexts; empty or unset means all of them. */
        public Builder slots(Slot... slots) {
            this.slots = slots == null || slots.length == 0
                    ? EnumSet.allOf(Slot.class)
                    : EnumSet.copyOf(java.util.Arrays.asList(slots));
            return this;
        }

        public SkinAnimation build() {
            return new SkinAnimation(spinAxis, spinDegreesPerTick, thrownSpinMultiplier,
                    wobbleDegrees, wobblePeriodTicks, bobUnits, bobPeriodTicks,
                    pulseAmount, pulsePeriodTicks, slots);
        }
    }
}

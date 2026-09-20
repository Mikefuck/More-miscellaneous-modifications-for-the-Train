package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.SkinAnimation;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinEffects;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a registered {@link SkinAnimation} into an actually moving model.
 *
 * <p>Vanilla has no notion of an animated item model transform, so the skin's baked
 * model is wrapped once (and cached per skin + baked model) in a model whose
 * {@link ItemTransforms} hand out time-driven {@link ItemTransform} subclasses.
 * Because {@code ItemRenderer} resolves the transform through the model it is about to
 * draw, this single hook covers every place a skinned stack is rendered — inventory,
 * hotbar, wardrobe cards, first/third person hands, item frames and the flying
 * projectile renderer.</p>
 *
 * <h2>Why the animation cannot break</h2>
 * <ul>
 *   <li>It is a pure function of one monotonic wall clock (50 ticks/s, wrapped to stay
 *       precise), never of stored state, so pausing, reloading resources, changing
 *       dimension or respawning cannot desynchronise it.</li>
 *   <li>Every motion is continuous and periodic: constant-rate spin (modulo a full
 *       turn), and sines that pass through zero at {@code t = 0}. There is no
 *       keyframe, so there is no seam to interpolate across.</li>
 *   <li>Rotations are inserted <em>after</em> the base display transform, i.e. about
 *       the model centre — the same frame {@code ItemRenderer}'s trailing
 *       {@code translate(-0.5, -0.5, -0.5)} centres — so a spinning model spins in
 *       place, and the extra uniform scale is centre-anchored as well.</li>
 *   <li>The vertical float is inserted <em>before</em> the base transform, so it
 *       offsets the item in its parent frame instead of inside its own rotation.</li>
 * </ul>
 */
public final class SkinAnimationModels {

    private static final float TAU = (float) (Math.PI * 2.0);
    /** 1e9 ms ≈ 231 days of uptime before the clock wraps; the wrap itself is seamless. */
    private static final long CLOCK_WRAP_MILLIS = 1_000_000_000L;

    /** Wrapper cache. Client-thread only in practice; the map is concurrent for safety. */
    private static final Map<CacheKey, BakedModel> CACHE = new ConcurrentHashMap<>();

    private SkinAnimationModels() {
    }

    /**
     * The animated view of {@code model} for {@code definition}, or {@code model} itself
     * when the skin registered no animation.
     */
    public static BakedModel animate(SkinDefinition definition, BakedModel model) {
        if (definition == null || model == null) {
            return model;
        }
        SkinAnimation animation = SkinEffects.animation(definition.type(), definition.id()).orElse(null);
        if (animation == null || !animation.isAnimated()) {
            return model;
        }
        // The cached wrapper is immutable and reads the clock per frame, so one instance
        // can be shared by every render of the same (skin, baked model) pair.
        return CACHE.computeIfAbsent(new CacheKey(definition.type(), definition.id(), model),
                key -> new AnimatedBakedModel(model, animation));
    }

    /** Drops every cached wrapper; must run when models are (re)baked. */
    public static void invalidate() {
        CACHE.clear();
    }

    /** Animation clock in ticks, wrapped so it stays exact in a {@code float}. */
    public static float clockTicks() {
        return (float) ((Util.getMillis() % CLOCK_WRAP_MILLIS) / 50.0);
    }

    private static ItemTransforms wrapTransforms(ItemTransforms base, SkinAnimation animation) {
        return new ItemTransforms(
                wrap(base.thirdPersonLeftHand, animation, SkinAnimation.Slot.HELD, 1.0F),
                wrap(base.thirdPersonRightHand, animation, SkinAnimation.Slot.HELD, 1.0F),
                wrap(base.firstPersonLeftHand, animation, SkinAnimation.Slot.HELD, 1.0F),
                wrap(base.firstPersonRightHand, animation, SkinAnimation.Slot.HELD, 1.0F),
                wrap(base.head, animation, SkinAnimation.Slot.HEAD, 1.0F),
                wrap(base.gui, animation, SkinAnimation.Slot.GUI, 1.0F),
                wrap(base.ground, animation, SkinAnimation.Slot.THROWN, animation.thrownSpinMultiplier()),
                wrap(base.fixed, animation, SkinAnimation.Slot.FIXED, 1.0F));
    }

    private static ItemTransform wrap(ItemTransform base, SkinAnimation animation,
            SkinAnimation.Slot slot, float spinMultiplier) {
        if (base == null || !animation.animates(slot)) {
            // Untouched contexts keep the exact vanilla transform object, so an animation
            // scoped to e.g. THROWN cannot perturb hands or the GUI by a rounding error.
            return base;
        }
        return new AnimatedItemTransform(base, animation, spinMultiplier);
    }

    /** Identity for the same skin + baked model; {@link BakedModel} does not override equals. */
    private record CacheKey(String type, String id, BakedModel model) {
    }

    private static final class AnimatedBakedModel implements BakedModel {
        private final BakedModel delegate;
        private final ItemTransforms transforms;

        AnimatedBakedModel(BakedModel delegate, SkinAnimation animation) {
            this.delegate = delegate;
            this.transforms = wrapTransforms(delegate.getTransforms(), animation);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
            return delegate.getQuads(state, direction, random);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return delegate.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return delegate.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return delegate.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return delegate.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return delegate.getParticleIcon();
        }

        @Override
        public ItemTransforms getTransforms() {
            return transforms;
        }

        @Override
        public ItemOverrides getOverrides() {
            return delegate.getOverrides();
        }

        @Override
        public String toString() {
            return "AnimatedSkinModel[" + delegate + "]";
        }
    }

    /**
     * The base display transform plus the skin's continuous motion.
     *
     * <p>{@link ItemTransform#apply(boolean, PoseStack)} applies translate → rotate →
     * scale in the frame whose origin is the model centre, so this override adds the
     * float before {@code super.apply} (parent frame) and the spin/wobble/pulse after it
     * (model-centre frame).</p>
     */
    private static final class AnimatedItemTransform extends ItemTransform {
        private final SkinAnimation animation;
        private final float spinMultiplier;
        private final boolean spin;
        private final boolean wobble;
        private final boolean bob;
        private final boolean pulse;

        AnimatedItemTransform(ItemTransform base, SkinAnimation animation, float spinMultiplier) {
            super(base.rotation, base.translation, base.scale);
            this.animation = animation;
            this.spinMultiplier = spinMultiplier;
            this.spin = animation.spinDegreesPerTick() != 0.0F && spinMultiplier != 0.0F;
            this.wobble = animation.wobbleDegrees() != 0.0F;
            this.bob = animation.bobUnits() != 0.0F;
            this.pulse = animation.pulseAmount() != 0.0F;
        }

        @Override
        public void apply(boolean leftHand, PoseStack poseStack) {
            float ticks = SkinAnimationModels.clockTicks();
            if (bob) {
                float offset = (float) (animation.bobUnits()
                        * Math.sin(TAU * ticks / animation.bobPeriodTicks()));
                if (offset != 0.0F) {
                    poseStack.translate(0.0F, offset, 0.0F);
                }
            }
            super.apply(leftHand, poseStack);
            if (spin) {
                float degrees = (float) (animation.spinDegreesPerTick() * (double) spinMultiplier
                        * ticks % 360.0);
                if (degrees != 0.0F) {
                    poseStack.mulPose(rotation(animation.spinAxis(), degrees));
                }
            }
            if (wobble) {
                float degrees = (float) (animation.wobbleDegrees()
                        * Math.sin(TAU * ticks / animation.wobblePeriodTicks()));
                if (degrees != 0.0F) {
                    poseStack.mulPose(Axis.XP.rotationDegrees(degrees));
                }
            }
            if (pulse) {
                float scale = 1.0F + (float) (animation.pulseAmount()
                        * Math.sin(TAU * ticks / animation.pulsePeriodTicks()));
                if (scale != 1.0F) {
                    poseStack.scale(scale, scale, scale);
                }
            }
        }

        private static Quaternionf rotation(SkinAnimation.SpinAxis axis, float degrees) {
            return switch (axis) {
                case X -> Axis.XP.rotationDegrees(degrees);
                case Z -> Axis.ZP.rotationDegrees(degrees);
                default -> Axis.YP.rotationDegrees(degrees);
            };
        }
    }
}

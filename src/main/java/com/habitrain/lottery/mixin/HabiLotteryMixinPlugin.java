package com.habitrain.lottery.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Error handler for habitrain_lottery's mixin configs.
 *
 * <p><b>Unified failure policy: our own mixins degrade, foreign mixins are
 * untouched.</b> Both lottery mixin configs declare {@code "required": false}, and
 * this handler makes a failure of one of <em>our own</em> mixins non-fatal: the
 * failing mixin is skipped, the operator gets a full-throwable ERROR log from
 * {@link #LOGGER}, the mixin processor logs the skipped mixin as well, and the game
 * still starts on upstream behaviour. A mixin that cannot be applied is not a reason
 * to keep players out of the game.
 *
 * <p><b>Foreign failures pass through untouched.</b> The handler is registered
 * globally, so it also receives failures from every other mod's configs. Those are
 * returned with the incoming {@code action} unchanged: we never change another
 * mod's fatality (a mod that chose {@code REQUIRED} keeps its hard failure, and a
 * mod that chose to continue keeps continuing).
 *
 * <p>Attribution is by name because a mixin apply failure carries no config:
 * {@link #onPrepareError} filters on {@code IMixinConfig#getName()} starting with
 * {@value #OWN_CONFIG_PREFIX}, and {@link #onApplyError} filters on the mixin class
 * name starting with {@value #OWN_MIXIN_PACKAGE_PREFIX}.
 *
 * <p><b>Why {@link ErrorAction#WARN} and not {@link ErrorAction#ERROR}.</b> In
 * Sponge Mixin, {@code ErrorAction.ERROR} is the <em>fatal</em> action, not an
 * "error-level log" action:
 * <ul>
 *   <li>{@code IMixinErrorHandler.ErrorAction.ERROR} is declared as
 *       {@code ERROR(Level.FATAL)} with the javadoc "Throw a
 *       {@code MixinApplyError} to halt further processing if possible".</li>
 *   <li>{@code MixinProcessor.handleMixinError(...)} ends with
 *       {@code if (action == ErrorAction.ERROR) throw new MixinApplyError(...);}.</li>
 * </ul>
 * So returning {@code ERROR} here would crash startup — exactly the defected
 * behaviour (M-02/M-06) this policy exists to remove, and it would make our own
 * mixins harsher than the {@code "required": false} default (which is
 * {@code WARN}). {@code WARN} continues processing while our own
 * {@code LOGGER.error(...)} call still records the failure at ERROR level with the
 * full throwable, so the failure is loud and non-fatal.
 */
public final class HabiLotteryMixinPlugin implements IMixinConfigPlugin, IMixinErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_lottery");

    /** Prefix of this mod's mixin config names ({@code habitrain_lottery.mixins.json}, {@code ...client.mixins.json}). */
    private static final String OWN_CONFIG_PREFIX = "habitrain_lottery";
    /** Prefix of this mod's mixin class names. */
    private static final String OWN_MIXIN_PACKAGE_PREFIX = "com.habitrain.lottery.";

    @Override
    public void onLoad(String mixinPackage) {
        Mixins.registerErrorHandlerClass(HabiLotteryMixinPlugin.class.getName());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (config != null && config.getName() != null && !config.getName().startsWith(OWN_CONFIG_PREFIX)) {
            // Not one of our configs: leave the other mod's fatality exactly as it is.
            return action;
        }
        String mixinName = mixin != null ? mixin.getClassName() : "?";
        String target = mixin != null ? String.valueOf(mixin.getTargetClasses()) : "?";
        LOGGER.error(
                "Mixin prepare failed: mixin={} target={} config={}",
                mixinName,
                target,
                config != null ? config.getName() : "?",
                th);
        // Our own mixin: skip it and continue. WARN (= continue processing) is the
        // non-fatal action; ERROR would throw MixinApplyError and kill startup.
        return ErrorAction.WARN;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (mixin != null && mixin.getClassName() != null
                && !mixin.getClassName().startsWith(OWN_MIXIN_PACKAGE_PREFIX)) {
            // Not one of our mixins: pass the other mod's fatality through untouched.
            return action;
        }
        String mixinName = mixin != null ? mixin.getClassName() : "?";
        LOGGER.error("Mixin apply failed: mixin={} target={}", mixinName, targetClassName, th);
        // Non-fatal, see onPrepareError: our own mixin degrades, the game starts.
        return ErrorAction.WARN;
    }
}

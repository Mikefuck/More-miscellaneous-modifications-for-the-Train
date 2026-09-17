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

public final class HabiLotteryMixinPlugin implements IMixinConfigPlugin, IMixinErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_lottery");
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
        String mixinName = mixin != null ? mixin.getClassName() : "?";
        String target = mixin != null ? String.valueOf(mixin.getTargetClasses()) : "?";
        LOGGER.error(
                "Mixin prepare failed: mixin={} target={} config={}",
                mixinName,
                target,
                config != null ? config.getName() : "?",
                th);
        return action;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        String mixinName = mixin != null ? mixin.getClassName() : "?";
        LOGGER.error("Mixin apply failed: mixin={} target={}", mixinName, targetClassName, th);
        return action;
    }
}

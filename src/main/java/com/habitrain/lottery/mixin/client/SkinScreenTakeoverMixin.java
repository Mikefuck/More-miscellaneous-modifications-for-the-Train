package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.gui.SkinWardrobeScreen;
import io.wifi.starrailexpress.client.gui.screen.SkinManagementScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Replace before Screen.init: covers menus, hotkeys and the upstream open packet. */
@Mixin(Minecraft.class)
public abstract class SkinScreenTakeoverMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen habi$replaceSkinScreen(Screen screen) {
        if (screen instanceof SkinManagementScreen upstream) {
            Screen parent = upstream.parentScreen;
            while (parent instanceof SkinManagementScreen nested) parent = nested.parentScreen;
            return new SkinWardrobeScreen(parent);
        }
        return screen;
    }
}

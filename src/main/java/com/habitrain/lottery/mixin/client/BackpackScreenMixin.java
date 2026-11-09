package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.network.CardUseRequestC2S;
import io.wifi.starrailexpress.client.gui.screen.BackpackScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.lang.reflect.Field;

/**
 * Replaces {@link BackpackScreen} with {@link WarehouseScreen} at the {@link Minecraft#setScreen}
 * entry point before {@link Screen#init()} runs, avoiding re-entrant screen replacement during
 * {@code init(TAIL)} while leaving upstream {@link BackpackScreen} intact when connected to a server
 * without {@code habitrain_lottery}.
 */
@Mixin(Minecraft.class)
public abstract class BackpackScreenMixin {
    @Unique
    private static Field habi$parentField;
    @Unique
    private static boolean habi$parentFieldResolved;

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen habi$replaceBackpackScreen(Screen screen) {
        if (!(screen instanceof BackpackScreen upstream)) {
            return screen;
        }
        Minecraft self = (Minecraft) (Object) this;
        if (self.getConnection() == null || !ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) {
            return screen;
        }
        Screen parent = habi$extractParent(upstream, self.screen);
        int depth = 0;
        while (parent instanceof BackpackScreen nested && depth++ < 8) {
            parent = habi$extractParent(nested, null);
        }
        if (parent instanceof WarehouseScreen) {
            return parent;
        }
        return new WarehouseScreen(parent);
    }

    @Unique
    private static Screen habi$extractParent(BackpackScreen screen, Screen fallback) {
        try {
            Field f = habi$parentField;
            if (!habi$parentFieldResolved) {
                try {
                    f = BackpackScreen.class.getDeclaredField("parent");
                    f.setAccessible(true);
                } catch (NoSuchFieldException ignored) {
                    for (Field candidate : BackpackScreen.class.getDeclaredFields()) {
                        if (Screen.class.isAssignableFrom(candidate.getType())) {
                            candidate.setAccessible(true);
                            f = candidate;
                            break;
                        }
                    }
                }
                habi$parentField = f;
                habi$parentFieldResolved = true;
            }
            if (f != null) {
                Object val = f.get(screen);
                if (val instanceof Screen parentScreen) {
                    return parentScreen;
                }
                if (val == null) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}

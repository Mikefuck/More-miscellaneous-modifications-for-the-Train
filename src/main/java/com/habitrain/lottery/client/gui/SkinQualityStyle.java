package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinQuality;
import net.minecraft.network.chat.Component;

/** One quality palette for both appearance browsers; text stays readable on dark surfaces. */
public final class SkinQualityStyle {
    private SkinQualityStyle() {}

    public static int top(SkinQuality quality, float emphasis) {
        return GuiFx.mix(0xFF252B35, quality.color(), .38f + Math.clamp(emphasis, 0, 1) * .12f);
    }

    public static int bottom(SkinQuality quality) {
        return GuiFx.mix(0xFF1D232C, quality.color(), .18f);
    }

    public static Component label(SkinQuality quality) {
        return Component.translatable("skin.habitrain_lottery.quality.label",
                Component.translatable(quality.translationKey())).withColor(quality.color() & 0xFFFFFF);
    }
}

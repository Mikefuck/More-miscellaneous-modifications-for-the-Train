package com.habitrain.lottery.client.theme;

import com.habitrain.lottery.config.ThemeConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class LootThemeHooks {
    private static ThemeConfig clientTheme = new ThemeConfig();
    private static final List<ResourceLocation> DEFAULTS = List.of(
            ResourceLocation.fromNamespaceAndPath("noellesroles", "textures/gui/loot/common_skin.png"),
            ResourceLocation.fromNamespaceAndPath("noellesroles", "textures/gui/loot/uncommon_skin.png"),
            ResourceLocation.fromNamespaceAndPath("noellesroles", "textures/gui/loot/rare_skin.png"),
            ResourceLocation.fromNamespaceAndPath("noellesroles", "textures/gui/loot/epic_skin.png"),
            ResourceLocation.fromNamespaceAndPath("noellesroles", "textures/gui/loot/legendary_skin.png"),
            ResourceLocation.fromNamespaceAndPath("noellesroles", "textures/gui/loot/unbelievable.png")
    );

    private LootThemeHooks() {
    }

    public static void initClient() {
        // defaults until server snapshot arrives
        clientTheme = new ThemeConfig();
        clientTheme.qualityBackgrounds = new ArrayList<>();
        for (ResourceLocation rl : DEFAULTS) {
            clientTheme.qualityBackgrounds.add(rl.toString());
        }
    }

    public static void applyTheme(ThemeConfig theme) {
        if (theme != null) {
            clientTheme = theme;
        }
    }

    public static ResourceLocation qualityBackground(int quality) {
        try {
            if (clientTheme != null && clientTheme.qualityBackgrounds != null
                    && quality >= 0 && quality < clientTheme.qualityBackgrounds.size()) {
                String raw = clientTheme.qualityBackgrounds.get(quality);
                ResourceLocation parsed = parse(raw);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Throwable ignored) {
        }
        if (quality < 0) {
            return DEFAULTS.get(0);
        }
        if (quality >= DEFAULTS.size()) {
            return DEFAULTS.get(DEFAULTS.size() - 1);
        }
        return DEFAULTS.get(quality);
    }

    private static ResourceLocation parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ResourceLocation.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}

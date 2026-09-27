package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Full-size preview of the current unsaved crate appearance draft. */
public final class CrateAppearancePreviewScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.crate_manage.";
    private final Screen parent;
    private final String name, preset, badge, crateIcon, keyIcon;
    private final int color;
    private boolean suppressNestedBackground;

    public CrateAppearancePreviewScreen(Screen parent, String name, String preset, String badge,
                                        int color, String crateIcon, String keyIcon) {
        super(Component.translatable(KEY + "preview"));
        this.parent = parent;
        this.name = name;
        this.preset = preset;
        this.badge = badge;
        this.color = color;
        this.crateIcon = crateIcon;
        this.keyIcon = keyIcon;
    }

    @Override protected void init() {
        int margin = width < 360 ? 8 : 16;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(margin, height - 24, width - margin * 2, 20).build());
    }

    private static ItemStack icon(String id) {
        try {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            return new ItemStack(item == Items.AIR ? Items.BARRIER : item);
        } catch (RuntimeException error) {
            return new ItemStack(Items.BARRIER);
        }
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        int margin = width < 360 ? 8 : 16;
        g.fill(margin, 4, width - margin, height - 29, 0xE51B242C);
        g.fill(margin, 4, width - margin, 7, color);
        g.drawCenteredString(font, getTitle(), width / 2, 13, 0xFFFFFFFF);
        g.drawCenteredString(font, font.plainSubstrByWidth(name, width - margin * 2 - 16),
                width / 2, 31, 0xFFE4E9E8);
        int iconY = Math.min(82, Math.max(54, height / 3));
        int left = Math.max(margin + 12, width / 4 - 8);
        int right = Math.min(width - margin - 28, width * 3 / 4 - 8);
        g.renderItem(icon(crateIcon), left, iconY);
        g.renderItem(icon(keyIcon), right, iconY);
        g.drawCenteredString(font, Component.translatable(KEY + "warehouse_icon"), left + 8, iconY + 23, 0xFFB9C5C8);
        g.drawCenteredString(font, Component.translatable(KEY + "key_preview"), right + 8, iconY + 23, 0xFFB9C5C8);
        float crateWidth = Math.min(150, Math.min(width * 0.36F, height * 0.48F));
        CrateArt.crateStyled(g, width / 2.0F, height - 34, crateWidth, 0, color, 0, preset, badge);
        suppressNestedBackground = true;
        try { super.render(g, mx, my, delta); }
        finally { suppressNestedBackground = false; }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (!suppressNestedBackground) super.renderBackground(g, mx, my, delta);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

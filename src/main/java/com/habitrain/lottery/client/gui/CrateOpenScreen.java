package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.CrateNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

/** Player-facing crate page with key selection, spin, reveal and result animation. */
public final class CrateOpenScreen extends Screen {
    private final Screen parent;
    private final String crateId;
    private String selectedKey = "";
    private Map<String, Double> inventory = Map.of();
    private Button keyButton;
    private Button openButton;
    private int phase;
    private int animationTick;
    private String message = "";
    private CrateNetwork.OpenResultS2C result;
    private int inventoryVersion = -1;
    private boolean inventoryKnown;

    public CrateOpenScreen(Screen parent, String crateId) {
        super(Component.translatable("screen.habitrain_lottery.crate.title"));
        this.parent = parent;
        this.crateId = crateId;
    }

    @Override protected void init() {
        clearWidgets();
        if (inventoryVersion < 0) inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
        CrateClientNetwork.requestInventory();
        buildButtons();
    }

    private void buildButtons() {
        keyButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            selectedKey = matchingKeyId();
            updateButtons();
        }).bounds(width / 2 - 90, height - 68, 180, 20).build());
        openButton = addRenderableWidget(Button.builder(Component.translatable("screen.habitrain_lottery.crate.open"), b -> beginOpen())
                .bounds(width / 2 - 70, height - 22, 140, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(8, height - 22, 55, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        String matchingKey = matchingKeyId();
        if (keyButton != null) {
            keyButton.setMessage(Component.translatable(selectedKey.equals(matchingKey)
                            ? "screen.habitrain_lottery.crate.key_selected" : "screen.habitrain_lottery.crate.key_option",
                    Component.translatable("screen.habitrain_lottery.crate.key." + crateId), count(matchingKey)));
            keyButton.active = phase == 0 && count(matchingKey) > 0 && !selectedKey.equals(matchingKey);
        }
        if (openButton != null) {
            int crateCount = count(CrateService.crateItemId(crateId));
            int keyCount = count(matchingKey);
            openButton.active = phase == 0 && crateCount > 0 && keyCount > 0 && selectedKey.equals(matchingKey);
        }
    }

    private void beginOpen() {
        if (phase != 0 || count(CrateService.crateItemId(crateId)) <= 0
                || count(matchingKeyId()) <= 0 || !selectedKey.equals(matchingKeyId())) return;
        phase = 1; animationTick = 0; message = "screen.habitrain_lottery.crate.waiting";
        updateButtons();
        CrateClientNetwork.open(crateId, selectedKey);
    }

    public void refreshFromState() {
        if (inventoryVersion == CrateClientNetwork.STATE.inventoryVersion) return;
        inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
        inventory = CrateClientNetwork.STATE.inventory;
        inventoryKnown = true;
        updateButtons();
    }

    public void receive(CrateNetwork.OpenResultS2C payload) {
        if (!crateId.equals(payload.crateId())) return;
        if (!payload.success()) {
            phase = 0; result = null; message = payload.message(); updateButtons(); return;
        }
        result = payload;
        phase = 2; animationTick = 0; message = "screen.habitrain_lottery.crate.spinning";
        refreshFromState();
    }

    private int count(String id) { return (int) Math.round(inventory.getOrDefault(id, 0D)); }
    private String matchingKeyId() { return CrateService.keyItemId(crateId); }

    @Override public void tick() {
        super.tick();
        refreshFromState();
        if (phase == 1 && ++animationTick > 120) { phase = 0; message = "screen.habitrain_lottery.crate.timeout"; updateButtons(); }
        if (phase == 2 && ++animationTick > 82) { phase = 3; animationTick = 0; message = "screen.habitrain_lottery.crate.reveal"; }
        if (phase == 3 && ++animationTick > 36) { phase = 4; message = "screen.habitrain_lottery.crate.complete"; updateButtons(); }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g, mouseX, mouseY, delta);
        int accent = CrateService.definition(crateId) == null ? 0xFF57C6D6 : CrateService.definition(crateId).color();
        g.fill(0, 0, width, height, 0xB812171E);
        g.fill(24, 24, width - 24, height - 32, 0xE51B242C);
        g.fill(24, 24, width - 24, 27, accent);
        g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate." + crateId), width / 2, 38, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate.description"), width / 2, 54, 0xFFAEBBC1);

        int cx = width / 2, cy = Math.min(150, height / 2 - 8);
        float pulse = phase == 2 ? 1F + Mth.sin(animationTick * .55F) * .08F : 1F;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(pulse, pulse, 1);
        ItemStack box = crateStack(crateId);
        g.renderFakeItem(box, -8, -8);
        g.pose().popPose();

        if (phase == 2) {
            float angle = animationTick * 0.24F;
            for (int i = 0; i < 8; i++) {
                double a = angle + i * Math.PI / 4;
                int px = cx + (int) (Math.cos(a) * (42 + animationTick / 2.5));
                int py = cy + (int) (Math.sin(a) * (26 + animationTick / 3.0));
                g.fill(px - 2, py - 2, px + 2, py + 2, accent | 0xAA000000);
            }
            g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate.rolling"), cx, cy + 30, accent);
        } else if (phase >= 3 && result != null) {
            SkinQuality quality = SkinQuality.fromId(result.quality());
            ItemStack skin = SkinItems.preview(result.skinType() + "/" + result.skin());
            if (skin.isEmpty()) skin = new ItemStack(Items.NETHER_STAR);
            g.pose().pushPose();
            float scale = phase == 3 ? 1F + Mth.sin(animationTick * .25F) * .16F : 1.12F;
            g.pose().translate(cx, cy - 4, 0); g.pose().scale(scale, scale, 1);
            g.renderFakeItem(skin, -8, -8);
            g.pose().popPose();
            g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate.skin_result", result.skinType() + "/" + result.skin()), cx, cy + 34, quality.color());
            g.drawCenteredString(font, Component.translatable(quality.translationKey()), cx, cy + 48, quality.color());
        }

        Component status = message.isBlank() && inventoryKnown
                ? Component.translatable(count(CrateService.crateItemId(crateId)) <= 0
                    ? "screen.habitrain_lottery.crate.missing_crate"
                    : count(matchingKeyId()) <= 0 ? "screen.habitrain_lottery.crate.missing_key"
                    : selectedKey.isEmpty() ? "screen.habitrain_lottery.crate.select_key"
                    : "screen.habitrain_lottery.crate.ready")
                : message.isBlank() ? Component.empty() : Component.translatable(message);
        g.drawCenteredString(font, status, width / 2, height - 94, phase == 0 ? 0xFFD4A55A : 0xFF9FD3DA);
        g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate.selected_key",
                selectedKey.isEmpty() ? Component.translatable("screen.habitrain_lottery.crate.unselected_key")
                        : Component.translatable("screen.habitrain_lottery.crate.key." + crateId)), width / 2, height - 82, 0xFF9BA8AF);
        g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.crate.inventory", count(CrateService.crateItemId(crateId)), count(matchingKeyId())), width / 2, height - 104, 0xFFB7C1C6);
        super.render(g, mouseX, mouseY, delta);
    }

    private static ItemStack crateStack(String id) {
        var definition = CrateService.definition(id);
        if (definition == null) return new ItemStack(Items.CHEST);
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(definition.icon()));
        return new ItemStack(item == null ? Items.CHEST : item);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

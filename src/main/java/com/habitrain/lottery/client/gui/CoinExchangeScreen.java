package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.network.OpenCoinExchangeS2C;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Select a quantity without spending coins until the player confirms. */
public final class CoinExchangeScreen extends Screen {
    private final Screen parent;
    private final int coins;
    private final int price;
    private final int maximum;
    private EditBox amount;
    private Button confirm;
    private String value = "1";
    private int top;

    public CoinExchangeScreen(Screen parent, OpenCoinExchangeS2C quote) {
        super(label("title"));
        this.parent = parent;
        coins = Math.max(0, quote.coins());
        price = Math.max(1, quote.price());
        maximum = Math.min(coins / price, Integer.MAX_VALUE - Math.max(0, quote.draws()));
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("screen.habitrain_lottery.exchange." + key, args);
    }

    @Override protected void init() {
        int w = Math.min(300, width - 24);
        int left = (width - w) / 2;
        top = Math.max(8, (height - 200) / 2);
        amount = new EditBox(font, left, top + 76, w - 116, 20, label("quantity"));
        amount.setMaxLength(10);
        amount.setFilter(s -> s.matches("[0-9]*"));
        amount.setValue(value);
        amount.setResponder(s -> { value = s; refresh(); });
        addRenderableWidget(amount);
        Button plus = addRenderableWidget(Button.builder(Component.literal("+10"), b ->
                amount.setValue(Long.toString(Math.min(maximum, (long) Math.max(0, quantity()) + 10))))
                .bounds(left + w - 110, top + 76, 50, 20).build());
        Button max = addRenderableWidget(Button.builder(Component.literal("+Max"), b ->
                amount.setValue(Integer.toString(maximum)))
                .bounds(left + w - 56, top + 76, 56, 20).build());
        plus.active = max.active = maximum > 0;
        int half = (w - 8) / 2;
        confirm = addRenderableWidget(Button.builder(label("confirm"), b -> submit())
                .bounds(left, top + 164, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + half + 8, top + 164, half, 20).build());
        refresh();
        setInitialFocus(amount);
    }

    private int quantity() {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private void refresh() {
        if (confirm != null) confirm.active = quantity() > 0 && quantity() <= maximum;
    }

    private void submit() {
        if (!confirm.active || minecraft == null || minecraft.getConnection() == null) return;
        confirm.active = false;
        minecraft.getConnection().sendCommand("sre:loot coin2lottery " + quantity());
        onClose();
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fillGradient(0, 0, width, height, 0xFF181420, 0xFF060B12);
        g.drawCenteredString(font, title, width / 2, top + 4, 0xFFFFD76A);
        g.drawCenteredString(font, label("balance", coins, price), width / 2, top + 28, 0xFFE1D8C6);
        g.drawCenteredString(font, label("maximum", maximum), width / 2, top + 44, 0xFFB9A98C);
        g.drawCenteredString(font, label("quantity"), width / 2, top + 62, 0xFFFFFFFF);
        long cost = (long) quantity() * price;
        g.drawCenteredString(font, label("cost", cost), width / 2, top + 108, 0xFFFFD76A);
        Component status = maximum == 0 ? label("unavailable")
                : quantity() <= 0 ? label("invalid")
                : quantity() > maximum ? label("too_many") : label("remaining", coins - cost);
        g.drawCenteredString(font, status, width / 2, top + 132,
                confirm.active ? 0xFFB9A98C : 0xFFFF7777);
        super.render(g, mouseX, mouseY, delta);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

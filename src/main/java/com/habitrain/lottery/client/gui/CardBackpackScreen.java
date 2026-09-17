package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.network.CardUseRequestC2S;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Server-backed card inventory with independent, keyboard-accessible entries. */
public final class CardBackpackScreen extends Screen {
    private static final String[] KEYS = {"killer", "civilian", "neutral", "neutral_for_killer", "self_select", "limit_break"};
    private final Screen parent;
    private final List<Button> cards = new ArrayList<>();
    private boolean limitBreakPending;
    private int seenInventoryVersion;

    public CardBackpackScreen(Screen parent) {
        super(Component.translatable("screen.habitrain_lottery.backpack.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        cards.clear();
        limitBreakPending = false;
        seenInventoryVersion = ClientLotteryState.cardInventoryVersion;
        ClientLotteryState.cardBalances = java.util.Map.of();
        int w = Math.min(340, width - 24);
        int rowHeight = Math.min(28, Math.max(20, (height - 96) / KEYS.length));
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            Button button = Button.builder(Component.empty(), b -> {
                if (key.equals("limit_break")) {
                    limitBreakPending = true;
                    b.active = false;
                    LotteryClientNetwork.clientCardUseConfirm(key, "bonus", "");
                } else {
                    ClientPlayNetworking.send(new CardUseRequestC2S(key));
                }
            })
                    .bounds((width - w) / 2, 44 + i * rowHeight, w, 20).build();
            button.setTooltip(Tooltip.create(Component.translatable("screen.habitrain_lottery.backpack."
                    + (key.equals("self_select") ? "self_hint"
                    : key.equals("limit_break") ? "limit_break_hint" : "faction_hint"))));
            cards.add(addRenderableWidget(button));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
        refresh();
        ClientPlayNetworking.send(new CardUseRequestC2S("inventory"));
    }

    private void refresh() {
        if (seenInventoryVersion != ClientLotteryState.cardInventoryVersion) {
            seenInventoryVersion = ClientLotteryState.cardInventoryVersion;
            limitBreakPending = false;
        }
        for (int i = 0; i < cards.size(); i++) {
            String key = KEYS[i];
            int count = ClientLotteryState.cardBalances.getOrDefault(key, 0);
            int remaining = key.equals("self_select") ? ClientLotteryState.cardUseRemainingSelfUses
                    : ClientLotteryState.cardUseRemainingUses;
            cards.get(i).setMessage(Component.translatable("screen.habitrain_lottery.backpack.row",
                    Component.translatable("screen.habitrain_lottery.config.cards." + key), count));
            cards.get(i).active = count > 0 && (remaining > 0 || key.equals("limit_break"))
                    && !(key.equals("limit_break") && limitBreakPending)
                    && !CardGuiGameState.gameActiveOrStarting();
        }
    }

    @Override public void tick() {
        if (CardGuiGameState.gameActiveOrStarting()) { onClose(); return; }
        refresh();
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fillGradient(0, 0, width, height, 0xFF181420, 0xFF060B12);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFD76A);
        g.drawCenteredString(font, Component.translatable("screen.habitrain_lottery.backpack.quota",
                ClientLotteryState.cardUseRemainingUses, ClientLotteryState.cardUseRemainingSelfUses),
                width / 2, 28, 0xFFB9A98C);
        super.render(g, mouseX, mouseY, delta);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

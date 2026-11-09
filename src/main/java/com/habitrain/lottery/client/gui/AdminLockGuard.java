package com.habitrain.lottery.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Shared {@code MenuAccessBridge.isLocked()} UI overlay and input guard ({@code B-22}).
 * Ensures locked admin screens only expose the explicit {@code backButton} and block all
 * invisible widget clicks, scroll events, and keyboard shortcuts.
 */
final class AdminLockGuard {
    private static final int BAD = 0xFFFF6B6B;
    private static final int ACCENT = 0xFF57C6D6;
    private static final int SUBTITLE = 0xFFB0B8C0;
    private static final int HINT = 0xFF8A92A0;

    private AdminLockGuard() {
    }

    static Button createLockedBackButton(int width, int height, Component label, Button.OnPress onClose) {
        int boxH = 104;
        int boxY = Math.max(8, (height - boxH) / 2 - 12);
        int btnW = Math.min(104, Math.max(64, width - 24));
        int btnX = (width - btnW) / 2;
        int btnY = Math.min(Math.max(8, height - 24), boxY + boxH + 8);
        return Button.builder(label, onClose).bounds(btnX, btnY, btnW, 20).build();
    }

    static void renderLockedScreen(
            GuiGraphics g,
            Font font,
            int width,
            int height,
            Button backButton,
            int mx,
            int my,
            float delta
    ) {
        g.fill(0, 0, width, height, 0xC0000000);
        int boxW = Math.max(140, Math.min(420, width - 32));
        int boxH = 104;
        int boxX = (width - boxW) / 2;
        int boxY = Math.max(8, (height - boxH) / 2 - 12);
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF01A2230);
        g.fill(boxX, boxY, boxX + boxW, boxY + 2, ACCENT);

        Component title = Component.translatable("screen.habitrain_lottery.config.status.unauthorized");
        Component sub = Component.translatable("screen.habitrain_lottery.config.locked.subtitle");
        Component hint = Component.translatable("screen.habitrain_lottery.config.locked.hint");
        Component esc = Component.translatable("screen.habitrain_lottery.config.locked.esc");

        g.drawCenteredString(font, title, width / 2, boxY + 18, BAD);
        int innerW = Math.max(60, boxW - 20);
        int y = boxY + 38;
        for (FormattedCharSequence line : font.split(sub, innerW)) {
            if (y + font.lineHeight > boxY + boxH - 8) break;
            g.drawCenteredString(font, line, width / 2, y, SUBTITLE);
            y += font.lineHeight + 2;
        }
        y += 4;
        List<FormattedCharSequence> hintLines = font.split(hint, innerW);
        for (FormattedCharSequence line : hintLines) {
            if (y + font.lineHeight > boxY + boxH - 6) break;
            g.drawCenteredString(font, line, width / 2, y, HINT);
            y += font.lineHeight + 2;
        }
        if (backButton != null) {
            backButton.render(g, mx, my, delta);
        } else {
            g.drawCenteredString(font, esc, width / 2, Math.max(8, height - 18), HINT);
        }
    }

    static boolean handleLockedMouseClicked(Button backButton, double mx, double my, int button) {
        return backButton != null && backButton.mouseClicked(mx, my, button);
    }

    static boolean handleLockedKeyPressed(Screen screen, Button backButton, int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE && screen.shouldCloseOnEsc()) {
            screen.onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB && backButton != null) {
            screen.setFocused(backButton);
            backButton.setFocused(true);
            return true;
        }
        if (backButton != null
                && backButton.active
                && backButton.visible
                && backButton.isFocused()
                && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE)) {
            backButton.onPress();
            return true;
        }
        return false;
    }
}

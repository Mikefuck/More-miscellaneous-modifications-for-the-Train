package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.client.gui.config.ConfigConsoleLayout;
import com.habitrain.lottery.client.gui.config.ConfigSectionId;
import com.habitrain.lottery.network.ClientLotteryState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Extracted navigation rail, header access badge, chrome backdrop, and tab hit-testing for
 * {@link LotteryConfigRootScreen} ({@code N-01}).
 */
final class ConfigConsoleNavBar {
    static final ConfigSectionId[] SECTION_IDS = {
            ConfigSectionId.PLAYERS,
            ConfigSectionId.TITLES,
            ConfigSectionId.SKINS,
            ConfigSectionId.CRATES,
            ConfigSectionId.MAIL,
            ConfigSectionId.DAILY,
            ConfigSectionId.SELF_SELECT
    };
    private static final int[] NAV_ORDER = {0, 1, 2, 6, 3, 5, 4};

    private int[] tabX;
    private int[] tabY;
    private int[] tabW;

    void layout(ConfigConsoleLayout layout, int selectedTab) {
        tabX = new int[SECTION_IDS.length];
        tabY = new int[SECTION_IDS.length];
        tabW = new int[SECTION_IDS.length];
        ConfigConsoleLayout.Rect nav = layout.navigation();
        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            tabX[selectedTab] = nav.x() + 24;
            tabY[selectedTab] = nav.y() + Math.max(0, (nav.height() - 16) / 2);
            tabW[selectedTab] = Math.max(0, nav.width() - 48);
            return;
        }
        int y = nav.y() + 8;
        int rowStep = Math.max(16, Math.min(18, (nav.height() - 32) / NAV_ORDER.length));
        ConfigSectionId.Group previousGroup = null;
        for (int tab : NAV_ORDER) {
            ConfigSectionId.Group group = SECTION_IDS[tab].group();
            if (previousGroup == null || previousGroup != group) {
                y += 12;
            }
            tabX[tab] = nav.x() + 4;
            tabY[tab] = y;
            tabW[tab] = Math.max(0, nav.width() - 8);
            y += rowStep;
            previousGroup = group;
        }
    }

    private void ensureLaidOut(ConfigConsoleLayout layout, int selectedTab) {
        if (tabX == null || tabY == null || tabW == null) {
            layout(layout, selectedTab);
        }
    }

    static void drawBackdrop(GuiGraphics g, ConfigConsoleLayout layout, int width, int height) {
        g.fill(0, 0, width, height, 0xD010141A);
        g.fill(0, 0, width, layout.header().bottom(), 0xF0111A22);
        g.fill(layout.content().x() - 4, layout.content().y(),
                layout.content().right(), layout.content().bottom(), 0xC0141D25);
        g.fill(layout.content().x() - 4, layout.content().y(),
                layout.content().right(), layout.content().y() + 1, 0x5057C6D6);
        g.fill(0, layout.footer().y(), width, height, 0xE011181F);
        g.fill(0, layout.footer().y(), width, layout.footer().y() + 1, 0x4057C6D6);
    }

    void drawTabsAndHeader(
            GuiGraphics g,
            Font font,
            ConfigConsoleLayout layout,
            int screenWidth,
            int selectedTab,
            boolean hideSectionHeader,
            int mx,
            int my
    ) {
        ensureLaidOut(layout, selectedTab);
        ConfigConsoleLayout.Rect nav = layout.navigation();
        g.fill(nav.x(), nav.y(), nav.right(), nav.bottom(), 0xE512171E);
        g.fill(nav.right() - 1, nav.y(), nav.right(), nav.bottom(), 0x4057C6D6);

        Component consoleTitle = Component.translatable("screen.habitrain_lottery.config.title");
        g.drawString(font, consoleTitle, 12, 12, 0xFFFFFFFF, false);
        String access = !LotteryClientNetwork.canSendPlay()
                ? Component.translatable("screen.habitrain_lottery.config.status.offline").getString()
                : (ClientLotteryState.op
                        ? Component.translatable("screen.habitrain_lottery.config.status.connected_op").getString()
                        : Component.translatable("screen.habitrain_lottery.config.status.connected_readonly").getString());
        int accessColor = !LotteryClientNetwork.canSendPlay()
                ? 0xFFD4A55A
                : (ClientLotteryState.op ? 0xFF65D18A : 0xFFFF6B6B);
        int badgeW = font.width(access) + 14;
        int badgeX = Math.max(12, screenWidth - badgeW - 12);
        g.fill(badgeX, 8, screenWidth - 12, 27, 0x80212C35);
        g.fill(badgeX, 8, badgeX + 2, 27, accessColor);
        g.drawString(font, access, badgeX + 8, 13, accessColor, false);

        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            int cy = nav.y() + Math.max(4, (nav.height() - 8) / 2);
            g.drawString(font, "‹", nav.x() + 8, cy, 0xFFD4A55A, false);
            g.drawCenteredString(font, sectionTitle(selectedTab), nav.x() + nav.width() / 2, cy, 0xFFFFFFFF);
            g.drawString(font, "›", nav.right() - 14, cy, 0xFFD4A55A, false);
        } else {
            ConfigSectionId.Group previousGroup = null;
            for (int tab : NAV_ORDER) {
                ConfigSectionId.Group group = SECTION_IDS[tab].group();
                if (previousGroup == null || previousGroup != group) {
                    g.drawString(font, Component.translatable(group.translationKey()),
                            nav.x() + 6, tabY[tab] - 10, 0xFF57C6D6, false);
                }
                previousGroup = group;
            }
            for (int i = 0; i < SECTION_IDS.length; i++) {
                boolean selected = i == selectedTab;
                boolean hover = mx >= tabX[i] && mx < tabX[i] + tabW[i]
                        && my >= tabY[i] && my < tabY[i] + 16;
                int bg = selected ? 0xFF24313A : (hover ? 0xFF1C252E : 0x00141820);
                g.fill(tabX[i], tabY[i], tabX[i] + tabW[i], tabY[i] + 16, bg);
                g.fill(tabX[i], tabY[i], tabX[i] + 2, tabY[i] + 16,
                        selected ? 0xFFD4A55A : 0x204D5965);
                g.drawString(font, sectionTitle(i), tabX[i] + 8, tabY[i] + 4,
                        selected ? 0xFFFFFFFF : 0xFF8A92A0, false);
            }
        }
        if (!hideSectionHeader) {
            g.drawString(font, sectionTitle(selectedTab), layout.content().x(), layout.content().y() + 4,
                    0xFFFFFFFF, false);
            g.drawString(font, sectionDescription(selectedTab), layout.content().x(), layout.content().y() + 17,
                    0xFF8A92A0, false);
        }
    }

    /** Returns the clicked tab index {@code 0..SECTION_IDS.length-1}, or {@code -1} if outside tabs. */
    int hitTestClick(ConfigConsoleLayout layout, int selectedTab, double mx, double my) {
        ensureLaidOut(layout, selectedTab);
        if (!layout.navigation().contains(mx, my)) {
            return -1;
        }
        if (layout.mode() == ConfigConsoleLayout.Mode.NARROW) {
            if (mx < layout.navigation().x() + 24) {
                return (selectedTab - 1 + SECTION_IDS.length) % SECTION_IDS.length;
            }
            if (mx >= layout.navigation().right() - 24) {
                return (selectedTab + 1) % SECTION_IDS.length;
            }
        }
        for (int i = 0; i < SECTION_IDS.length; i++) {
            if (mx >= tabX[i] && mx < tabX[i] + tabW[i]
                    && my >= tabY[i] && my < tabY[i] + 16) {
                return i;
            }
        }
        return -1;
    }

    static Component sectionTitle(int tab) {
        return Component.translatable(SECTION_IDS[Mth.clamp(tab, 0, SECTION_IDS.length - 1)].titleKey());
    }

    static Component sectionDescription(int tab) {
        return Component.translatable(SECTION_IDS[Mth.clamp(tab, 0, SECTION_IDS.length - 1)].descriptionKey());
    }
}

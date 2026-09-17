package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.LotteryNetwork;

import io.wifi.starrailexpress.client.util.PinYinUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.agmas.noellesroles.utils.RoleUtils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 自选角色页。
 *
 * <p>布局参照上游 {@code CustomRoleSelectScreen}：顶部搜索框、居中的职业卡网格、
 * 分页按钮和底部操作提示。搜索控件只创建一次，过滤时只替换卡牌和分页按钮，
 * 避免 {@link EditBox#setValue(String)} 触发 responder 后重新递归刷新页面。</p>
 */
public class RoleSelectScreen extends Screen {
    private static final int BG_TOP = 0xF0181420;
    private static final int BG_BOTTOM = 0xF0060B12;
    private static final int PANEL_TOP = 0xE61A1420;
    private static final int PANEL_BOTTOM = 0xE6091018;
    private static final int BORDER = 0xFF8B6914;
    private static final int GOLD = 0xFFFFD76A;
    private static final int TEXT = 0xFFFFF4DC;
    private static final int MUTED = 0xFFB9A98C;
    private static final int CARD_GAP = 8;
    private static final int MAX_COLUMNS = 5;

    private static final Gson GSON = new Gson();

    public static final class Candidate {
        public String id;
        public String name;
        public int color;
        public String bound;

        private transient String cachedDisplayName;

        public String displayName() {
            if (cachedDisplayName != null) {
                return cachedDisplayName;
            }
            cachedDisplayName = resolveRoleDisplayName(id, name);
            return cachedDisplayName;
        }

        public boolean isBound() {
            return bound != null && !bound.isBlank();
        }
    }

    private final Screen parent;
    private final List<Candidate> allCandidates = new ArrayList<>();
    private final List<Candidate> filtered = new ArrayList<>();
    private final List<RoleCardWidget> roleCards = new ArrayList<>();

    private EditBox searchBox;
    private Button previousPageButton;
    private Button nextPageButton;
    private Button backButton;
    private String searchQuery = "";
    private boolean syncingSearchBox;
    private boolean closedForGameStart;
    private boolean selectionSubmitted;

    private int page;
    private int pageCount = 1;
    private int columns;
    private int rowsPerPage;
    private int cardWidth;
    private int cardHeight;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int searchX;
    private int searchY;
    private int searchW;
    private int gridX;
    private int gridTop;
    private int gridBottom;
    private int tickCounter;

    public RoleSelectScreen(Screen parent) {
        super(Component.literal("自选角色"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        tickCounter = 0;
        parseCandidates();
        computeLayout();
        createSearchBox();
        rebuildPage();

        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
        }
    }

    private void parseCandidates() {
        allCandidates.clear();
        String json = LotteryNetwork.ClientLotteryState.cardUseCandidatesJson;
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Type type = new TypeToken<List<Candidate>>() {
            }.getType();
            List<Candidate> parsed = GSON.fromJson(json, type);
            if (parsed != null) {
                for (Candidate candidate : parsed) {
                    if (candidate != null && candidate.id != null && !candidate.id.isBlank()) {
                        allCandidates.add(candidate);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 服务端快照异常时保持空列表，不能让 GUI 初始化崩溃。
        }
    }

    private void computeLayout() {
        int availableW = Math.max(240, width - 20);
        int availableH = Math.max(200, height - 18);
        panelW = Math.min(780, Math.max(320, availableW));
        panelH = Math.min(430, Math.max(220, availableH));
        panelW = Math.min(panelW, width);
        panelH = Math.min(panelH, height);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        boolean compact = height < 390 || width < 560;
        searchX = panelX + 34;
        searchY = panelY + (compact ? 46 : 48);
        searchW = Math.max(100, panelW - 50);
        gridX = panelX + 16;
        gridTop = panelY + (compact ? 84 : 88);
        gridBottom = Math.max(gridTop + 62, panelY + panelH - 42);

        int preferredCardWidth = compact ? 92 : 112;
        int usableW = Math.max(72, panelW - 32);
        columns = Mth.clamp((usableW + CARD_GAP) / (preferredCardWidth + CARD_GAP), 1, MAX_COLUMNS);
        cardWidth = Math.max(72, (usableW - (columns - 1) * CARD_GAP) / columns);

        int areaHeight = Math.max(62, gridBottom - gridTop);
        int preferredCardHeight = compact ? 78 : 104;
        rowsPerPage = Math.max(1, Math.min(2,
                (areaHeight + CARD_GAP) / (preferredCardHeight + CARD_GAP)));
        cardHeight = Math.max(58, Math.min(preferredCardHeight,
                (areaHeight - (rowsPerPage - 1) * CARD_GAP) / rowsPerPage));
    }

    private void createSearchBox() {
        searchBox = new EditBox(font, searchX, searchY, searchW, 20, Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("搜索职业名、ID或拼音…"));
        searchBox.setEditable(true);
        searchBox.setTextColor(TEXT);
        searchBox.setResponder(this::onSearchChanged);
        addRenderableWidget(searchBox);

        if (!searchQuery.isEmpty()) {
            syncingSearchBox = true;
            searchBox.setValue(searchQuery);
            syncingSearchBox = false;
        }
    }

    private void onSearchChanged(String text) {
        if (syncingSearchBox) {
            return;
        }
        String next = text == null ? "" : text;
        if (Objects.equals(searchQuery, next)) {
            return;
        }
        searchQuery = next;
        page = 0;
        rebuildPage();
    }

    private void applyFilter() {
        filtered.clear();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            filtered.addAll(allCandidates);
        } else {
            for (Candidate candidate : allCandidates) {
                if (matches(candidate, query)) {
                    filtered.add(candidate);
                }
            }
        }

        int perPage = Math.max(1, columns * rowsPerPage);
        pageCount = Math.max(1, (int) Math.ceil(filtered.size() / (double) perPage));
        page = Mth.clamp(page, 0, pageCount - 1);
    }

    private boolean matches(Candidate candidate, String query) {
        String name = candidate.displayName().toLowerCase(Locale.ROOT);
        String id = candidate.id == null ? "" : candidate.id.toLowerCase(Locale.ROOT);
        String bound = candidate.bound == null ? "" : candidate.bound.toLowerCase(Locale.ROOT);
        String boundName = candidate.isBound()
                ? resolveBoundRoleDisplayName(candidate.bound).toLowerCase(Locale.ROOT)
                : "";
        if (name.contains(query) || id.contains(query) || bound.contains(query) || boundName.contains(query)) {
            return true;
        }
        try {
            return PinYinUtils.contains(query, candidate.displayName())
                    || PinYinUtils.contains(query, candidate.id == null ? "" : candidate.id)
                    || (candidate.isBound() && PinYinUtils.contains(query, boundName));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void rebuildPage() {
        removePageWidgets();
        applyFilter();

        int perPage = Math.max(1, columns * rowsPerPage);
        int start = page * perPage;
        int end = Math.min(start + perPage, filtered.size());
        for (int i = start; i < end; i++) {
            int indexOnPage = i - start;
            int column = indexOnPage % columns;
            int row = indexOnPage / columns;
            int x = gridX + column * (cardWidth + CARD_GAP);
            int y = gridTop + row * (cardHeight + CARD_GAP);
            RoleCardWidget card = new RoleCardWidget(x, y, cardWidth, cardHeight, filtered.get(i));
            roleCards.add(card);
            addRenderableWidget(card);
        }

        int navY = panelY + panelH - 28;
        previousPageButton = Button.builder(Component.literal("‹ 上一页"), button -> {
            if (page > 0) {
                page--;
                rebuildPage();
            }
        }).bounds(panelX + 16, navY, 74, 20).build();
        previousPageButton.active = page > 0;
        addRenderableWidget(previousPageButton);

        nextPageButton = Button.builder(Component.literal("下一页 ›"), button -> {
            if (page + 1 < pageCount) {
                page++;
                rebuildPage();
            }
        }).bounds(panelX + panelW - 90, navY, 74, 20).build();
        nextPageButton.active = page + 1 < pageCount;
        addRenderableWidget(nextPageButton);

        backButton = Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(panelX + panelW / 2 - 46, navY, 92, 20).build();
        addRenderableWidget(backButton);
    }

    private void removePageWidgets() {
        for (RoleCardWidget card : roleCards) {
            removeWidget(card);
        }
        roleCards.clear();
        if (previousPageButton != null) {
            removeWidget(previousPageButton);
            previousPageButton = null;
        }
        if (nextPageButton != null) {
            removeWidget(nextPageButton);
            nextPageButton = null;
        }
        if (backButton != null) {
            removeWidget(backButton);
            backButton = null;
        }
    }

    private String questKey() {
        String key = LotteryNetwork.ClientLotteryState.cardUseMenuQuestKey;
        return key == null ? "" : key;
    }

    private void confirm(Candidate candidate) {
        if (candidate == null || candidate.id == null || candidate.id.isBlank()
                || closedForGameStart || selectionSubmitted) {
            return;
        }
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return;
        }
        if (questKey().isBlank()) {
            return;
        }
        selectionSubmitted = true;
        try {
            LotteryClientNetwork.clientCardUseConfirm(questKey(), "self", candidate.id);
        } finally {
            // 选择已经提交后立即返回职业卡父页面；服务端会负责最终校验，
            // 即使网络发送失败也不能让玩家停留在已完成的选择页。
            if (parent instanceof CardUseMenuScreen menu) {
                menu.closeAfterSelection();
            } else {
                closeToParent();
            }
        }
    }

    @Override
    public void tick() {
        if (CardGuiGameState.gameActiveOrStarting() && !closedForGameStart) {
            closeForGameStart();
            return;
        }
        tickCounter++;
        super.tick();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (CardGuiGameState.gameActiveOrStarting()) {
            closeForGameStart();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (closedForGameStart) {
            return;
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics);
        drawListHeader(graphics);
        drawEmptyState(graphics);
        drawFooter(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPanel(GuiGraphics graphics) {
        graphics.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH,
                PANEL_TOP, PANEL_BOTTOM);
        graphics.renderOutline(panelX, panelY, panelW, panelH, BORDER);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 3, 0x44FFE8C0);

        graphics.drawString(font, Component.literal("自选角色"), panelX + 16, panelY + 12, GOLD, false);
        graphics.drawString(font,
                Component.translatable("screen.habitrain_lottery.role_select.balance",
                        LotteryNetwork.ClientLotteryState.cardBalances.getOrDefault("self_select", 0)),
                panelX + 16, panelY + 28, MUTED, false);

        int barX = panelX + 16;
        int barY = searchY - 3;
        int barW = panelW - 32;
        graphics.fillGradient(barX, barY, barX + barW, barY + 26,
                0xD51A2230, 0xD40D151F);
        graphics.renderOutline(barX, barY, barW, 26, 0xCC5A7890);
        graphics.drawString(font, Component.literal("⌕"), panelX + 22, searchY + 4, GOLD, false);

        graphics.fill(panelX + 16, gridTop - 27, panelX + panelW - 16, gridTop - 26, 0x448B6914);
    }

    private void drawListHeader(GuiGraphics graphics) {
        String pageText = filtered.isEmpty()
                ? "没有可显示的角色"
                : "第 " + (page + 1) + "/" + pageCount + " 页  ·  共 " + filtered.size() + " 个角色";
        graphics.drawString(font, Component.literal("职业列表"), gridX, gridTop - 18, TEXT, false);
        graphics.drawString(font, Component.literal(pageText),
                panelX + panelW - 16 - font.width(pageText), gridTop - 18, MUTED, false);
    }

    private void drawEmptyState(GuiGraphics graphics) {
        if (!filtered.isEmpty()) {
            return;
        }
        int centerX = panelX + panelW / 2;
        int centerY = gridTop + Math.max(30, (gridBottom - gridTop) / 2);
        graphics.fill(centerX - 96, centerY - 28, centerX + 96, centerY + 28, 0x44130912);
        graphics.renderOutline(centerX - 96, centerY - 28, 192, 56, 0x665A4530);
        String message = searchQuery == null || searchQuery.isBlank()
                ? "当前阵营没有可用职业"
                : "没有匹配“" + searchQuery + "”的职业";
        graphics.drawCenteredString(font, Component.literal(message), centerX, centerY - 4, MUTED);
        graphics.drawCenteredString(font, Component.literal("尝试搜索名称、ID或拼音"), centerX,
                centerY + 12, 0xFF7C6D5A);
    }

    private void drawFooter(GuiGraphics graphics) {
        graphics.fill(panelX + 16, panelY + panelH - 40, panelX + panelW - 16,
                panelY + panelH - 39, 0x332E5A66);
        graphics.drawCenteredString(font, Component.literal("点击卡牌立即确认  ·  Esc 返回"),
                panelX + panelW / 2, panelY + panelH - 37, MUTED);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
        graphics.fillGradient(0, 0, width, 54, 0xAA000000, 0x00000000);

        long time = minecraft != null && minecraft.level != null
                ? minecraft.level.getGameTime() : tickCounter;
        for (int i = 0; i < 14; i++) {
            int x = (int) ((time * (i + 2L) + i * 71L) % Math.max(1, width));
            int y = 48 + (i * 29) % Math.max(1, height - 48);
            graphics.fill(x, y, x + 2, y + 2, 0x223D8BA3);
        }
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    /** 开局时直接返回背包，确保未发送确认包。 */
    void closeForGameStart() {
        if (closedForGameStart) {
            return;
        }
        closedForGameStart = true;
        selectionSubmitted = false;
        if (parent instanceof CardUseMenuScreen menu) {
            menu.closeForGameStart();
        } else {
            closeToParent();
        }
    }

    private void closeToParent() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen != parent) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class RoleCardWidget extends AbstractWidget {
        private final Candidate candidate;
        private float hoverAnimation;

        private RoleCardWidget(int x, int y, int width, int height, Candidate candidate) {
            super(x, y, width, height, Component.literal(candidate.displayName()));
            this.candidate = candidate;
            String boundName = candidate.isBound() ? resolveBoundRoleDisplayName(candidate.bound) : "";
            String boundText = candidate.isBound()
                    ? "\n绑定：" + (boundName.isBlank() ? shortId(candidate.bound) : boundName + " (" + shortId(candidate.bound) + ")")
                    : "";
            setTooltip(Tooltip.create(Component.literal(candidate.displayName()
                    + "\n" + candidate.id + boundText + "\n点击选择此职业")));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (active && isFocused() && (keyCode == 257 || keyCode == 335 || keyCode == 32)) {
                confirm(candidate);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = active && isMouseOver(mouseX, mouseY);
            hoverAnimation = Mth.lerp(0.20F, hoverAnimation, hovered ? 1.0F : 0.0F);

            int x = getX();
            int y = getY();
            int w = width;
            int h = height;
            int accent = roleColor(candidate);
            int top = blend(0xFF2A2231, accent, hoverAnimation * 0.28F);
            int bottom = blend(0xFF12121C, accent, hoverAnimation * 0.14F);

            if (hoverAnimation > 0.02F) {
                int glow = ((int) (hoverAnimation * 80) << 24) | (accent & 0xFFFFFF);
                graphics.renderOutline(x - 1, y - 1, w + 2, h + 2, glow);
                graphics.renderOutline(x - 2, y - 2, w + 4, h + 4, glow & 0x35FFFFFF);
            }
            graphics.fillGradient(x, y, x + w, y + h, top, bottom);
            graphics.renderOutline(x, y, w, h, hovered ? GOLD : blend(0xFF5A4530, accent, 0.48F));
            graphics.fill(x + 1, y + 1, x + w - 1, y + 5, accent);

            int iconSize = Math.min(34, Math.max(18, h / 3));
            int iconX = x + (w - iconSize) / 2;
            int iconY = y + 10;
            graphics.fill(iconX - 2, iconY - 2, iconX + iconSize + 2, iconY + iconSize + 2,
                    0x77000000);
            graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize,
                    withAlpha(accent, hovered ? 0xE0 : 0xB0));
            graphics.renderOutline(iconX, iconY, iconSize, iconSize, 0xCCFFF4DC);
            graphics.drawCenteredString(font, Component.literal(initial(candidate)),
                    iconX + iconSize / 2, iconY + iconSize / 2 - 4, 0xFFFFFFFF);

            int nameBarY = y + h * 2 / 3;
            graphics.fill(x + 1, nameBarY, x + w - 1, y + h - 1, 0x990B0B12);
            List<FormattedCharSequence> lines = font.split(Component.literal(candidate.displayName()), w - 12);
            int visibleLines = Math.min(2, lines.size());
            int nameY = nameBarY + Math.max(1, (h / 3 - visibleLines * font.lineHeight) / 2);
            for (int i = 0; i < visibleLines; i++) {
                graphics.drawCenteredString(font, lines.get(i), x + w / 2, nameY + i * font.lineHeight,
                        TEXT);
            }

            String boundName = candidate.isBound() ? resolveBoundRoleDisplayName(candidate.bound) : "";
            String meta = candidate.isBound()
                    ? "绑定 · " + (boundName.isBlank() ? shortId(candidate.bound) : boundName)
                    : shortId(candidate.id);
            meta = trim(meta, Math.max(24, w - 10));
            graphics.drawCenteredString(font, Component.literal(meta), x + w / 2, y + h - 12,
                    candidate.isBound() ? 0xFFFFB56B : MUTED);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            confirm(candidate);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.HINT, Component.literal("点击选择此职业"));
        }
    }

    public static String resolveRoleDisplayName(String roleIdentifier, String rawName) {
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            ResourceLocation roleId = ResourceLocation.tryParse(roleIdentifier);
            if (roleId == null && !roleIdentifier.contains(":")) {
                roleId = ResourceLocation.tryParse("starrailexpress:" + roleIdentifier);
            }
            if (roleId != null) {
                // 1. habitrain_core RoleCatalogApi
                try {
                    EffectiveRole effective = RoleCatalogApi.instance().find(RoleKey.of(roleId)).orElse(null);
                    if (effective != null && effective.role() != null && effective.role().getName() != null) {
                        String effName = effective.role().getName().getString();
                        if (isMeaningfulRoleName(effName, roleId)) {
                            return effName;
                        }
                    }
                } catch (Throwable e) {
                    // catalog unavailable in unit tests / classload
                }

                // 2. SRE RoleUtils
                try {
                    Component ruName = RoleUtils.getRoleName(roleId);
                    if (ruName != null) {
                        String s = ruName.getString();
                        if (isMeaningfulRoleName(s, roleId)) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {
                }

                // 4. Client Language dictionary
                Language lang = Language.getInstance();
                String key1 = "announcement.star.role." + roleId.getPath();
                if (lang.has(key1)) {
                    return lang.getOrDefault(key1);
                }
                String key2 = "announcement.star.role." + roleId.toString();
                if (lang.has(key2)) {
                    return lang.getOrDefault(key2);
                }
                String key3 = "role." + roleId.getNamespace() + "." + roleId.getPath();
                if (lang.has(key3)) {
                    return lang.getOrDefault(key3);
                }
                String key4 = "role." + roleId.getPath();
                if (lang.has(key4)) {
                    return lang.getOrDefault(key4);
                }
                String key5 = "role." + roleId.toString();
                if (lang.has(key5)) {
                    return lang.getOrDefault(key5);
                }
                String key6 = "sre.role." + roleId.getPath();
                if (lang.has(key6)) {
                    return lang.getOrDefault(key6);
                }
            }
        }

        // 5. Check rawName
        if (rawName != null && !rawName.isBlank()) {
            Language lang = Language.getInstance();
            if (lang.has(rawName)) {
                return lang.getOrDefault(rawName);
            }
            if (!rawName.startsWith("announcement.") && !rawName.startsWith("role.") && !rawName.startsWith("sre.")) {
                return rawName;
            }
        }

        // 6. Fallback to path or id
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            int colon = roleIdentifier.indexOf(':');
            return colon >= 0 && colon + 1 < roleIdentifier.length()
                    ? roleIdentifier.substring(colon + 1)
                    : roleIdentifier;
        }
        return "未知职业";
    }

    public static String resolveBoundRoleDisplayName(String bound) {
        if (bound == null || bound.isBlank()) {
            return "";
        }
        return resolveRoleDisplayName(bound, null);
    }

    private static boolean isMeaningfulRoleName(String text, ResourceLocation roleId) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (roleId != null) {
            if (trimmed.equalsIgnoreCase(roleId.toString()) || trimmed.equalsIgnoreCase(roleId.getPath())) {
                return false;
            }
        }
        if (trimmed.startsWith("announcement.star.role.") || trimmed.startsWith("role.") || trimmed.startsWith("sre.role.")) {
            return false;
        }
        return true;
    }

    private static String initial(Candidate candidate) {
        String text = candidate.displayName();
        if (text == null || text.isBlank()) {
            return "?";
        }
        int codePoint = text.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "未知";
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
        return trim(path, Math.max(24, cardWidth - 10));
    }

    private String trim(String value, int maxWidth) {
        return font.plainSubstrByWidth(value == null ? "" : value, Math.max(4, maxWidth));
    }

    private static int roleColor(Candidate candidate) {
        if (candidate.color != 0) {
            return (candidate.color & 0xFF000000) == 0 ? candidate.color | 0xFF000000 : candidate.color;
        }
        if (candidate.id != null && !candidate.id.isBlank()) {
            ResourceLocation rl = ResourceLocation.tryParse(candidate.id);
            if (rl != null) {
                try {
                    EffectiveRole effective = RoleCatalogApi.instance().find(RoleKey.of(rl)).orElse(null);
                    if (effective != null && effective.role() != null && effective.role().getColor() != 0) {
                        int c = effective.role().getColor();
                        return (c & 0xFF000000) == 0 ? c | 0xFF000000 : c;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return 0xFF8B6914;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private static int blend(int first, int second, float amount) {
        float t = Mth.clamp(amount, 0.0F, 1.0F);
        int r1 = first >> 16 & 0xFF;
        int g1 = first >> 8 & 0xFF;
        int b1 = first & 0xFF;
        int r2 = second >> 16 & 0xFF;
        int g2 = second >> 8 & 0xFF;
        int b2 = second & 0xFF;
        return 0xFF000000
                | ((int) (r1 + (r2 - r1) * t) << 16)
                | ((int) (g1 + (g2 - g1) * t) << 8)
                | (int) (b1 + (b2 - b1) * t);
    }
}

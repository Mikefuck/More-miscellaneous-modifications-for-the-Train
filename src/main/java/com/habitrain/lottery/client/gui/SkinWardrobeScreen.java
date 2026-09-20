package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.client.SkinClient;
import com.habitrain.lottery.client.WardrobeExtras;
import com.habitrain.lottery.skin.SkinNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Independent wardrobe. Browsing is local; equipped state only comes from the server. */
public final class SkinWardrobeScreen extends Screen {
    private static final String[] TYPES = {"knife", "revolver", "bat", "grenade", "hat", "title"};
    // Midnight carriage enamel, brass fittings, pale labels and mint status lights.
    private static final int BACK = 0xFF101D2A, PANEL = 0xFF1B2D3D, TILE = 0xFF223849;
    private static final int LINE = 0xFF415A6B, INK = 0xFFE8F1EE, MUTED = 0xFFA4B8C3;
    private static final int BRASS = 0xFFE9BD76, MINT = 0xFF8AD7BC;
    /** Drag sensitivities per pixel; pitch is clamped so the model never flips over. */
    private static final float YAW_SENSITIVITY = 0.03F, PITCH_SENSITIVITY = 0.02F, PITCH_LIMIT = 1.4F;
    private final Screen parent;
    private final List<TileButton> cards = new ArrayList<>();
    private final List<ActionButton> tabs = new ArrayList<>();
    private List<Entry> catalog = List.of(), visible = List.of();
    private WardrobeLayout layout;
    private EditBox search;
    private ActionButton equip, previous, next, filterButton, sortButton, hats, reload, view;
    private int typeIndex, page, filter;
    private boolean sortByName, received, initialized;
    /** The third-person model is the default preview; 2D keeps the flat skin icon. */
    private boolean view3d = true, dragging;
    private float previewYaw, previewPitch;
    private String query = "", selected = "default", pendingType, pendingId;
    private String titleEquipped = "";
    private List<String> knownTitles = List.of();
    private long requestAt, pendingAt;
    private int attempts;
    private Component notice = text("loading");

    private record Entry(String id, Component name, boolean owned, boolean equipped,
                         boolean available, int color, ItemStack icon) {}

    public SkinWardrobeScreen(Screen parent) { super(text("title")); this.parent = parent; }
    private static Component text(String key, Object... args) {
        return Component.translatable("screen.habitrain_lottery.wardrobe." + key, args);
    }
    private String type() { return TYPES[typeIndex]; }
    private boolean titles() { return "title".equals(type()); }
    private Component category(String type) {
        return "title".equals(type) ? text("titles") : Component.translatable("skin.habitrain_lottery.type." + type);
    }

    /**
     * Display name for one skin id.
     *
     * <p>The current key is {@code skin.habitrain_lottery.<type>.<id>}; extensions
     * written for API v1 used the upstream {@code screen.sre.skins.<type>.<id>.name}
     * key instead, which left every v1 skin showing its raw id in this wardrobe.
     * The legacy key is probed second so old translations keep working (audit F-06).</p>
     */
    private static Component skinName(String type, String id) {
        String key = "skin.habitrain_lottery." + type + "." + id;
        return key.equals(Component.translatable(key).getString())
                ? legacySkinName(type, id)
                : Component.translatable(key);
    }

    /** {@code screen.sre.skins.<type>.<id>.name} when translated, otherwise the raw id. */
    private static Component legacySkinName(String type, String id) {
        String key = "screen.sre.skins." + type + "." + id + ".name";
        // translatableWithFallback cannot tell "translated" from "fell back", so the
        // resolved string is compared against the key: vanilla returns the key itself
        // when no translation is installed.
        return key.equals(Component.translatable(key).getString())
                ? Component.literal(id)
                : Component.translatable(key);
    }

    /** The legacy v1 description key, when the installed language file still defines it. */
    private static Component legacySkinDescription(String type, String id) {
        String key = "screen.sre.skins." + type + "." + id + ".desc";
        return key.equals(Component.translatable(key).getString())
                ? Component.empty()
                : Component.translatable(key);
    }

    @Override protected void init() {
        layout = WardrobeLayout.of(width, height);
        cards.clear(); tabs.clear();
        int x = layout.x() + 10, w = layout.width() - 20;
        int tabW = (w - 5 * 4) / 6;
        for (int i = 0; i < TYPES.length; i++) {
            final int index = i;
            tabs.add(action(x + i * (tabW + 4), layout.y() + 34, tabW, category(TYPES[i]), b -> {
                typeIndex = index; page = 0; selected = "default"; refreshCatalog();
            }));
        }
        // Keep this widget alive while typing: rebuilding the screen loses the cursor/focus.
        int controlWidth = Math.min(88, w / 4), searchWidth = w - 2 * controlWidth - 8;
        search = new EditBox(font, x, layout.y() + 60, searchWidth, 20, text("search"));
        search.setMaxLength(100); search.setHint(text("search")); search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; refreshCatalog(); });
        addRenderableWidget(search);
        filterButton = action(x + searchWidth + 4, search.getY(), controlWidth, text("filter.0"), b -> {
            filter = (filter + 1) % 3; page = 0; refreshCatalog();
        });
        sortButton = action(x + searchWidth + controlWidth + 8, search.getY(), controlWidth, text("sort.owned"), b -> {
            sortByName = !sortByName; page = 0; refreshCatalog();
        });
        equip = action(layout.detailX() + 8, layout.gridY() + layout.contentHeight() - 28,
                layout.detailWidth() - 16, text("equip"), b -> equipSelected());
        view = action(layout.detailX() + layout.detailWidth() - 52,
                layout.gridY() + layout.contentHeight() - 48, 44, text("view.3d"), b -> {
                    view3d = !view3d; updateControls();
                });
        view.setTooltip(Tooltip.create(text("view.hint")));
        previous = action(x, layout.bottom() - 26, 26, Component.literal("<"), b -> changePage(-1));
        previous.setTooltip(Tooltip.create(text("previous")));
        next = action(x + 92, layout.bottom() - 26, 26, Component.literal(">"), b -> changePage(1));
        next.setTooltip(Tooltip.create(text("next")));
        reload = action(x + 124, layout.bottom() - 26, 56, text("refresh"), b -> requestCatalog());
        action(layout.x() + layout.width() - 70, layout.bottom() - 26, 60, text("back"), b -> onClose());
        hats = action(layout.x() + layout.width() - 126, layout.y() + 8, 116, text("hats.0"), b -> {
            WardrobeExtras.cycleHatMode(); updateControls();
        });
        hats.setTooltip(Tooltip.create(text("hats.hint")));
        if (!initialized) {
            initialized = true;
            knownTitles = WardrobeExtras.titles(); titleEquipped = WardrobeExtras.equippedTitle();
            requestCatalog();
        }
        refreshCatalog();
    }

    private ActionButton action(int x, int y, int w, Component label, Button.OnPress press) {
        return addRenderableWidget(new ActionButton(x, y, w, 20, label, press));
    }
    private void requestCatalog() {
        if (pendingId != null) return;
        received = false; attempts = 0; notice = text("loading"); sendRefresh();
        if (layout != null && equip != null) refreshCatalog();
    }
    private void sendRefresh() {
        requestAt = Util.getMillis(); attempts++;
        if (ClientPlayNetworking.canSend(SkinNetwork.Request.TYPE)) {
            ClientPlayNetworking.send(new SkinNetwork.Request("", "refresh"));
        } else { attempts = 3; notice = text("unavailable"); }
    }

    /** Called on the client thread after the authoritative catalog has been replaced. */
    public void refresh() {
        received = true;
        if (pendingId != null && !"title".equals(pendingType)) {
            boolean applied = "default".equals(pendingId)
                    ? SkinClient.entries().stream().noneMatch(e -> e.type().equals(pendingType) && e.equipped())
                    : SkinClient.entries().stream().anyMatch(e -> e.type().equals(pendingType)
                            && e.id().equals(pendingId) && e.equipped());
            notice = text(applied ? "saved" : "unchanged"); pendingId = null;
        } else if (pendingId == null) notice = text("hint");
        if (layout != null) refreshCatalog();
    }

    private void refreshCatalog() {
        List<Entry> list = new ArrayList<>();
        if (titles()) {
            // Upstream only accepts owned title IDs; do not offer an unsupported clear request.
            if (titleEquipped.isBlank()) list.add(new Entry("default", text("no_title"), true, true, true,
                    BRASS, new ItemStack(Items.NAME_TAG)));
            for (String id : knownTitles.stream().filter(s -> s != null && !s.isBlank()).distinct().toList()) {
                list.add(new Entry(id, Component.translatableWithFallback(id, id), true,
                        id.equals(titleEquipped), true, BRASS, new ItemStack(Items.NAME_TAG)));
            }
        } else if (received) {
            boolean hasEquipped = SkinClient.entries().stream().anyMatch(e -> e.type().equals(type()) && e.equipped());
            list.add(new Entry("default", text("default"), true, !hasEquipped, true, MUTED, defaultIcon()));
            for (var entry : SkinClient.entries()) {
                if (!type().equals(entry.type())) continue;
                var definition = HabiSkinApi.find(entry.type(), entry.id());
                // Availability is resolved by SkinClient's sprite-based check; comparing
                // against ModelManager#getMissingModel never matched (audit F-01), which
                // made this branch unreachable and let model-less skins be equipped.
                boolean available = definition.isPresent() && SkinClient.hasModel(entry.type(), entry.id());
                list.add(new Entry(entry.id(), skinName(entry.type(), entry.id()),
                        entry.owned(), entry.equipped(), available,
                        definition.map(d -> 0xFF000000 | d.color()).orElse(MUTED),
                        SkinItems.preview(entry.type() + "/" + entry.id())));
            }
        }
        catalog = List.copyOf(list);
        String term = query.strip().toLowerCase(Locale.ROOT);
        Comparator<Entry> comparator = Comparator.comparing(e -> e.name().getString().toLowerCase(Locale.ROOT));
        if (!sortByName) comparator = Comparator.comparing((Entry e) -> !e.equipped())
                .thenComparing(e -> !e.owned()).thenComparing(comparator);
        visible = catalog.stream().filter(e -> filter == 0 || (filter == 1 ? e.owned() : !e.owned()))
                .filter(e -> e.id().toLowerCase(Locale.ROOT).contains(term)
                        || e.name().getString().toLowerCase(Locale.ROOT).contains(term))
                .sorted(comparator.thenComparing(Entry::id)).toList();
        page = Math.max(0, Math.min(page, pages() - 1));
        if (visible.stream().noneMatch(e -> e.id().equals(selected))) selected = visible.isEmpty() ? "" : visible.getFirst().id();
        buildCards(); updateControls();
    }
    private ItemStack defaultIcon() {
        return titles() ? new ItemStack(Items.NAME_TAG) : SkinItems.defaultStack(type());
    }
    /** Stack shown on the model: the type's real item, carrying the selected skin component. */
    private ItemStack previewStack(Entry entry) {
        if (entry == null || "default".equals(entry.id())) return SkinItems.defaultStack(type());
        return SkinItems.styled(type(), entry.id());
    }
    private ItemStack handStack(Entry entry) { return "hat".equals(type()) ? ItemStack.EMPTY : previewStack(entry); }
    private ItemStack headStack(Entry entry) { return "hat".equals(type()) ? previewStack(entry) : ItemStack.EMPTY; }
    private boolean model3d() { return view3d && !titles() && PlayerPreview.available(); }
    private int pages() { return Math.max(1, (visible.size() + layout.pageSize() - 1) / layout.pageSize()); }
    private Entry selectedEntry() { return catalog.stream().filter(e -> e.id().equals(selected)).findFirst().orElse(null); }
    private void buildCards() {
        String focusedId = getFocused() instanceof TileButton tile ? tile.entry.id() : null;
        if (focusedId != null) setFocused(null);
        for (var card : cards) removeWidget(card);
        cards.clear();
        int start = page * layout.pageSize(), end = Math.min(visible.size(), start + layout.pageSize());
        for (int i = start; i < end; i++) {
            int index = i - start;
            cards.add(addRenderableWidget(new TileButton(
                    layout.gridX() + index % layout.columns() * (layout.cardWidth() + 6),
                    layout.gridY() + index / layout.columns() * (layout.cardHeight() + 6), visible.get(i))));
        }
        if (focusedId != null) {
            TileButton replacement = cards.stream().filter(card -> card.entry.id().equals(focusedId)).findFirst().orElse(null);
            setFocused(replacement == null ? search : replacement);
        }
    }
    private void updateControls() {
        Entry entry = selectedEntry();
        boolean connected = titles() ? WardrobeExtras.canEquipTitle() : received && ClientPlayNetworking.canSend(SkinNetwork.Request.TYPE);
        equip.active = pendingId == null && connected && entry != null && entry.owned() && entry.available() && !entry.equipped();
        equip.setMessage(pendingId != null ? text("saving") : entry == null ? text("select")
                : entry.equipped() ? text("equipped") : !entry.owned() ? text("locked")
                : !entry.available() ? text("missing") : "default".equals(entry.id()) ? text("restore") : text("equip"));
        previous.active = page > 0; next.active = page < pages() - 1;
        reload.active = pendingId == null && (received || Util.getMillis() - requestAt > 1200);
        filterButton.setMessage(text("filter." + filter));
        sortButton.setMessage(text(sortByName ? "sort.name" : "sort.owned"));
        hats.visible = "hat".equals(type()); hats.setMessage(text("hats." + WardrobeExtras.hatMode()));
        view.visible = !titles();
        view.active = !titles();
        view.setMessage(text(view3d ? "view.3d" : "view.2d"));
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).chosen = i == typeIndex;
    }
    private void equipSelected() {
        Entry entry = selectedEntry();
        if (!equip.active || entry == null) return;
        pendingId = entry.id(); pendingType = type(); pendingAt = Util.getMillis(); notice = text("saving");
        if (titles()) WardrobeExtras.equipTitle("default".equals(entry.id()) ? "" : entry.id());
        else ClientPlayNetworking.send(new SkinNetwork.Request(type(), entry.id()));
        updateControls();
    }

    @Override public void tick() {
        long now = Util.getMillis();
        if (!received && attempts < 3 && now - requestAt > 1200) sendRefresh();
        if (!received && attempts >= 3 && now - requestAt > 4000)
            notice = text(ClientPlayNetworking.canSend(SkinNetwork.Request.TYPE) ? "timeout" : "unavailable");
        var latestTitles = WardrobeExtras.titles();
        String latestEquipped = WardrobeExtras.equippedTitle();
        if (!knownTitles.equals(latestTitles) || !titleEquipped.equals(latestEquipped)) {
            knownTitles = latestTitles; titleEquipped = latestEquipped;
            if ("title".equals(pendingType) && pendingId != null
                    && ("default".equals(pendingId) ? latestEquipped.isBlank() : latestEquipped.equals(pendingId))) {
                pendingId = null; notice = text("saved");
            }
            if (titles()) refreshCatalog();
        }
        if (pendingId != null && now - pendingAt > 5000) {
            pendingId = null; notice = text("save_timeout");
            // Fetch actual state, never repeat a mutation after an ambiguous timeout.
            if (ClientPlayNetworking.canSend(SkinNetwork.Request.TYPE))
                ClientPlayNetworking.send(new SkinNetwork.Request("", "refresh"));
        }
        updateControls();
    }
    private void changePage(int offset) {
        int target = Math.max(0, Math.min(pages() - 1, page + offset));
        if (target == page) return;
        page = target; buildCards(); updateControls();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= layout.gridX() && x < layout.detailX() && y >= layout.gridY()
                && y < layout.gridY() + layout.contentHeight() && vertical != 0) {
            changePage(vertical > 0 ? -1 : 1); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    /** The rendered player is the only draggable surface; cards and buttons keep normal clicks. */
    private boolean overPreview(double x, double y) {
        return model3d() && x >= layout.detailX() + 4 && x < layout.detailX() + layout.detailWidth() - 4
                && y >= layout.gridY() + 28 && y < layout.gridY() + layout.contentHeight() - 50;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && overPreview(x, y)) { dragging = true; return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Same sign convention as the vanilla mouse-follow preview, but unbounded on yaw
            // so the whole model (including its back) can be inspected.
            previewYaw -= (float) dragX * YAW_SENSITIVITY;
            previewPitch = Mth.clamp(previewPitch - (float) dragY * PITCH_SENSITIVITY, -PITCH_LIMIT, PITCH_LIMIT);
            return true;
        }
        return super.mouseDragged(x, y, button, dragX, dragY);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { dragging = false; return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_F && hasControlDown()) { setFocused(search); return true; }
        if (!search.isFocused() && WardrobeExtras.matchesOpenKey(key, scan)) { onClose(); return true; }
        if (!search.isFocused() && (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP)) {
            changePage(key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // Screen.render invokes this before its widgets. The custom shell is drawn below.
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fillGradient(0, 0, width, height, BACK, 0xFF0A131D);
        int x = layout.x(), y = layout.y(), w = layout.width();
        g.fill(x, y, x + w, layout.bottom(), PANEL);
        g.renderOutline(x, y, w, layout.height(), LINE);
        g.fill(x, y, x + 3, y + 28, BRASS);
        g.drawString(font, title, x + 12, y + 13, INK, false);
        if (!hats.visible) {
            long owned = catalog.stream().filter(e -> e.owned() && !"default".equals(e.id())).count();
            long total = catalog.stream().filter(e -> !"default".equals(e.id())).count();
            Component count = !received && !titles() ? text("loading") : text("collection", owned, total);
            g.drawString(font, count, x + w - 12 - font.width(count), y + 13, MUTED, false);
        }
        renderDetail(g);
        super.render(g, mouseX, mouseY, delta);
        if (visible.isEmpty()) {
            Component empty = !received && !titles() ? text("loading") : text("no_results");
            int ty = layout.gridY() + 12;
            for (var line : font.split(empty, layout.gridWidth() - 16)) {
                g.drawString(font, line, layout.gridX() + 8, ty, MUTED, false); ty += 12;
            }
        }
        g.drawCenteredString(font, (page + 1) + " / " + pages(), layout.gridX() + 59, layout.bottom() - 20, MUTED);
        Component status = received && catalog.size() == 1 && query.isBlank() && !titles() && pendingId == null
                ? text("empty_catalog") : notice;
        clippedText(g, status, layout.gridX(), layout.bottom() - 40, w - 20, MUTED);
    }
    private void renderDetail(GuiGraphics g) {
        int x = layout.detailX(), y = layout.gridY(), w = layout.detailWidth(), h = layout.contentHeight();
        g.fill(x, y, x + w, y + h, BACK); g.renderOutline(x, y, w, h, LINE);
        Entry entry = selectedEntry();
        if (entry == null) { clippedText(g, text("select"), x + 10, y + 12, w - 20, MUTED); return; }
        clippedText(g, entry.name(), x + 10, y + 10, w - 20, INK);
        int artTop = y + 28, artBottom = y + h - 50;
        // Cabinet rails frame the actual model; no replacement illustration obscures it.
        g.fill(x + 12, artBottom + 1, x + w - 12, artBottom + 2, LINE);
        if (model3d()) {
            // Other players see the real stack in third person, so preview exactly that.
            PlayerPreview.render(g, x + 4, artTop, x + w - 4, artBottom, previewYaw, previewPitch,
                    handStack(entry), headStack(entry), "hat".equals(type()));
        } else {
            int size = Math.max(16, Math.min(w - 32, artBottom - artTop - 4));
            g.enableScissor(x + 4, artTop, x + w - 4, artBottom);
            drawIcon(g, entry, x + w / 2, (artTop + artBottom) / 2, size); g.disableScissor();
        }
        if (h > 180) clippedText(g, Component.literal(entry.id()), x + 10, artBottom - 12, w - 20, MUTED);
        clippedText(g, state(entry), x + 10, y + h - 42, w - 20 - (view.visible ? 48 : 0),
                entry.equipped() ? MINT : MUTED);
    }
    private Component state(Entry entry) {
        return text(entry.equipped() ? "equipped" : !entry.available() ? "missing" : entry.owned() ? "owned" : "locked");
    }
    private void drawIcon(GuiGraphics g, Entry entry, int cx, int cy, int size) {
        if (entry.icon().isEmpty()) { g.drawCenteredString(font, "?", cx, cy - 4, MUTED); return; }
        g.pose().pushPose(); g.pose().translate(cx - size / 2f, cy - size / 2f, 0);
        g.pose().scale(size / 16f, size / 16f, 1); g.renderFakeItem(entry.icon(), 0, 0); g.pose().popPose();
    }
    private void clippedText(GuiGraphics g, Component component, int x, int y, int maxWidth, int color) {
        String value = component.getString();
        if (font.width(value) > maxWidth) value = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
        g.drawString(font, value, x, y, color, false);
    }
    private class ActionButton extends Button {
        boolean chosen;
        ActionButton(int x, int y, int w, int h, Component label, OnPress press) {
            super(x, y, w, h, label, press, DEFAULT_NARRATION);
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            int border = chosen || isFocused() ? BRASS : isHovered() && active ? MINT : LINE;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), chosen ? 0xFF3B4C52 : TILE);
            g.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
            if (chosen) g.fill(getX() + 3, getY() + getHeight() - 2, getX() + getWidth() - 3, getY() + getHeight() - 1, BRASS);
            g.enableScissor(getX() + 3, getY(), getX() + getWidth() - 3, getY() + getHeight());
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, active ? INK : MUTED);
            g.disableScissor();
        }
    }
    private final class TileButton extends ActionButton {
        private final Entry entry;
        TileButton(int x, int y, Entry entry) {
            super(x, y, layout.cardWidth(), layout.cardHeight(), entry.name().copy().append(" · ").append(state(entry)),
                    b -> { selected = entry.id(); updateControls(); });
            this.entry = entry;
            Component tooltip = entry.name().copy().append("\n").append(state(entry)).append("\n" + entry.id());
            if (!"title".equals(type())) {
                Component description = legacySkinDescription(type(), entry.id());
                if (!description.getString().isEmpty()) tooltip = tooltip.copy().append("\n").append(description);
            }
            setTooltip(Tooltip.create(tooltip
                    .copy().append(entry.available() ? Component.empty() : text("missing_hint"))));
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean picked = selected.equals(entry.id());
            g.fill(x, y, x + w, y + h, picked ? 0xFF304957 : TILE);
            g.renderOutline(x, y, w, h, isFocused() ? INK : picked ? BRASS : isHovered() ? MINT : LINE);
            g.fill(x + 1, y + 1, x + 3, y + h - 1, entry.color());
            int size = Math.max(16, Math.min(44, h - 40));
            g.enableScissor(x + 4, y + 2, x + w - 4, y + h - 29);
            drawIcon(g, entry, x + w / 2, y + (h - 28) / 2, size); g.disableScissor();
            clippedText(g, entry.name(), x + 7, y + h - 26, w - 14, entry.owned() ? INK : MUTED);
            clippedText(g, state(entry), x + 7, y + h - 13, w - 14, entry.equipped() ? MINT : MUTED);
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}

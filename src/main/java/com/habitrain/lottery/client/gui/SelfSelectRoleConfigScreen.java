package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.habitrain.lottery.card.SelfSelectRoleConfig;
import com.habitrain.lottery.card.SelfSelectRoleConfigService;
import com.habitrain.lottery.client.MenuAccessBridge;
import com.habitrain.lottery.client.SelfSelectAdminClient;
import com.habitrain.lottery.network.SelfSelectAdminNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Server-backed draft editor. Esc/cancel discard; save succeeds only after the server replies. */
public final class SelfSelectRoleConfigScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.self_select.";
    private static final Gson GSON = new Gson();
    private final Screen parent;
    private final ScrollableButtonList list = new ScrollableButtonList();
    private final List<AbstractWidget> rows = new ArrayList<>();
    private List<RoleRow> roles = List.of();
    private List<RoleRow> filtered = List.of();
    private SelfSelectRoleConfig baseline = new SelfSelectRoleConfig();
    private SelfSelectRoleConfig draft = baseline.copy();
    private boolean writable;
    private boolean pending;
    private boolean saving;
    private boolean requested;
    private boolean conflict;
    private int pendingTicks;
    private String requestId = "";
    private String query = "";
    private Component status = text("loading");
    private EditBox search;
    private Button master, allow, deny, save, refresh, cancel, previous, next;
    private int left, contentWidth, searchY, toolsY, listY, listBottom;
    private boolean suppressBackground;

    private record RoleRow(String id, Component name, boolean unavailable) { }

    public SelfSelectRoleConfigScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    @Override
    protected void init() {
        rows.clear();
        contentWidth = Math.min(720, width - 16);
        left = (width - contentWidth) / 2;
        int hintLines = Math.min(2, font.split(text("hint"), contentWidth).size());
        searchY = 32 + hintLines * font.lineHeight;
        toolsY = searchY + 24;
        listY = toolsY + 36;
        listBottom = height - 46;
        search = addRenderableWidget(new EditBox(font, left, searchY, contentWidth - 56, 20, text("search")));
        search.setMaxLength(128);
        search.setHint(text("search"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            list.setScroll(0);
            rebuildRows();
        });
        previous = addRenderableWidget(Button.builder(Component.literal("↑"), b -> scroll(-list.visibleRows()))
                .bounds(left + contentWidth - 52, searchY, 24, 20).tooltip(Tooltip.create(text("previous")))
                .createNarration(n -> text("previous").copy()).build());
        next = addRenderableWidget(Button.builder(Component.literal("↓"), b -> scroll(list.visibleRows()))
                .bounds(left + contentWidth - 24, searchY, 24, 20).tooltip(Tooltip.create(text("next")))
                .createNarration(n -> text("next").copy()).build());
        int third = (contentWidth - 8) / 3;
        master = addRenderableWidget(Button.builder(text("master_on"), b -> {
            draft.restrictionsEnabled = !draft.restrictionsEnabled;
            refreshLocks();
        }).bounds(left, toolsY, third, 20).tooltip(Tooltip.create(text("master_hint"))).build());
        allow = addRenderableWidget(Button.builder(text("allow_filtered"), b -> setFiltered(true))
                .bounds(left + third + 4, toolsY, third, 20).tooltip(Tooltip.create(text("batch_hint"))).build());
        deny = addRenderableWidget(Button.builder(text("deny_filtered"), b -> setFiltered(false))
                .bounds(left + (third + 4) * 2, toolsY, contentWidth - (third + 4) * 2, 20)
                .tooltip(Tooltip.create(text("batch_hint"))).build());
        int footerY = height - 26;
        refresh = addRenderableWidget(Button.builder(text("refresh"), b -> confirmDiscard(this::request))
                .bounds(left, footerY, third, 20).tooltip(Tooltip.create(text("refresh_hint"))).build());
        cancel = addRenderableWidget(Button.builder(text("cancel"), b -> onClose())
                .bounds(left + third + 4, footerY, third, 20).build());
        save = addRenderableWidget(Button.builder(text("save"), b -> submit())
                .bounds(left + (third + 4) * 2, footerY, contentWidth - (third + 4) * 2, 20).build());
        rebuildRows();
        setInitialFocus(search);
        if (!requested) {
            requested = true;
            request();
        }
    }

    private boolean dirty() {
        return draft.restrictionsEnabled != baseline.restrictionsEnabled
                || !draft.disabledRoles.equals(baseline.disabledRoles);
    }

    private boolean editable() {
        return writable && !pending && SelfSelectAdminClient.connected() && !MenuAccessBridge.isLocked();
    }

    private void refreshLocks() {
        if (master == null) return;
        master.setMessage(text(draft.restrictionsEnabled ? "master_on" : "master_off"));
        master.active = editable();
        allow.active = deny.active = editable() && !filtered.isEmpty();
        for (AbstractWidget row : rows) row.active = editable();
        save.active = editable() && dirty() && !conflict;
        refresh.active = !pending && SelfSelectAdminClient.connected() && !MenuAccessBridge.isLocked();
        cancel.active = !pending;
        previous.active = list.getScroll() > 0;
        next.active = list.getScroll() < list.maxScroll();
    }

    private void rebuildRows() {
        int focusedRow = rows.indexOf(getFocused());
        for (AbstractWidget row : rows) removeWidget(row);
        rows.clear();
        String match = query.trim().toLowerCase(Locale.ROOT);
        filtered = roles.stream().filter(r -> r.id.toLowerCase(Locale.ROOT).contains(match)
                || r.name.getString().toLowerCase(Locale.ROOT).contains(match)).toList();
        list.setBounds(left, listY, contentWidth, Math.max(22, listBottom - listY));
        List<String> labels = filtered.stream().map(r -> {
            String label = text(draft.disabledRoles.contains(r.id) ? "role_denied" : "role_allowed", r.name).getString();
            return font.plainSubstrByWidth(label, contentWidth - 20);
        }).toList();
        list.setItems(labels, list.getSelectedIndex());
        list.setOnSelect(index -> {
            RoleRow row = filtered.get(index);
            if (!draft.disabledRoles.remove(row.id)) draft.disabledRoles.add(row.id);
            // Update existing buttons, retaining keyboard focus on the toggled role.
            for (int i = 0; i < rows.size(); i++) {
                RoleRow visible = filtered.get(list.getScroll() + i);
                rows.get(i).setMessage(text(draft.disabledRoles.contains(visible.id) ? "role_denied" : "role_allowed", visible.name));
            }
            refreshLocks();
        });
        list.rebuildWidgets(widget -> {
            RoleRow role = filtered.get(list.getScroll() + rows.size());
            widget.setTooltip(Tooltip.create(Component.empty().append(role.name).append("\n" + role.id + "\n")
                    .append(text(role.unavailable ? "unavailable" : "role_hint"))));
            rows.add(widget);
            addRenderableWidget(widget);
        }, null);
        // Rebuilt rows precede the footer in Tab order, matching their visual order.
        for (Button button : new Button[]{refresh, cancel, save}) {
            removeWidget(button);
            addRenderableWidget(button);
        }
        if (focusedRow >= 0 && !rows.isEmpty()) setFocused(rows.get(Math.min(focusedRow, rows.size() - 1)));
        refreshLocks();
    }

    private void setFiltered(boolean allowed) {
        for (RoleRow row : filtered) {
            if (allowed) draft.disabledRoles.remove(row.id);
            else draft.disabledRoles.add(row.id);
        }
        rebuildRows();
        setFocused(allowed ? allow : deny);
    }

    private void scroll(int amount) {
        list.setScroll(list.getScroll() + amount);
        rebuildRows();
    }

    private boolean begin(boolean saveRequest) {
        if (!SelfSelectAdminClient.connected() || MenuAccessBridge.isLocked()) {
            status = text("offline");
            return false;
        }
        pending = true;
        saving = saveRequest;
        pendingTicks = 0;
        requestId = UUID.randomUUID().toString();
        status = text(saveRequest ? "saving" : "loading");
        refreshLocks();
        return true;
    }

    private void request() {
        if (begin(false)) SelfSelectAdminClient.request(requestId);
    }

    private void submit() {
        if (editable() && dirty() && begin(true)) SelfSelectAdminClient.save(requestId, GSON.toJson(draft));
    }

    public void receive(SelfSelectAdminNetwork.ConfigS2C response) {
        if (!pending || !response.requestId().equals(requestId)) return;
        pending = false;
        try {
            var snapshot = GSON.fromJson(response.json(), SelfSelectRoleConfigService.Snapshot.class);
            writable = snapshot != null && snapshot.writable() && !"no_permission".equals(response.message());
            if (snapshot != null && snapshot.config() != null) {
                List<RoleRow> nextRoles = new ArrayList<>();
                for (var role : snapshot.roles()) nextRoles.add(new RoleRow(role.id, Component.translatable(role.name), false));
                for (String id : snapshot.config().disabledRoles) {
                    if (nextRoles.stream().noneMatch(r -> r.id.equals(id))) nextRoles.add(new RoleRow(id, Component.literal(id), true));
                }
                roles = nextRoles;
                if (!saving || "saved".equals(response.message())) {
                    baseline = snapshot.config().copy();
                    draft = baseline.copy();
                    conflict = false;
                }
            }
            conflict = "conflict".equals(response.message());
            status = text(response.message().isEmpty() ? "ready" : response.message());
        } catch (RuntimeException malformed) {
            writable = false;
            status = text("malformed");
        }
        rebuildRows();
    }

    @Override
    public void tick() {
        super.tick();
        if (pending && (!SelfSelectAdminClient.connected() || ++pendingTicks >= 200)) {
            pending = false;
            requestId = "";
            status = text(SelfSelectAdminClient.connected() ? "timeout" : "offline");
        }
        refreshLocks();
    }

    private void confirmDiscard(Runnable action) {
        if (!dirty()) {
            action.run();
            return;
        }
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) action.run();
        }, text("discard_title"), text("discard_hint")));
    }

    @Override
    public void onClose() {
        if (!pending) confirmDiscard(() -> minecraft.setScreen(parent));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (list.mouseScrolled(mx, my, vertical)) {
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scroll((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * list.visibleRows());
            if (!rows.isEmpty()) setFocused(rows.get(0));
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (!suppressBackground) super.renderBackground(g, mx, my, delta);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        var hint = font.split(text("hint"), contentWidth);
        for (int i = 0; i < Math.min(2, hint.size()); i++)
            g.drawString(font, hint.get(i), left, 26 + i * font.lineHeight, 0xFFB7C1C6, false);
        long allowed = roles.stream().filter(r -> !draft.disabledRoles.contains(r.id)).count();
        g.drawString(font, font.plainSubstrByWidth(text("count", allowed, roles.size(), filtered.size(),
                text(dirty() ? "dirty" : "clean")).getString(), contentWidth), left, toolsY + 24, 0xFFB7C1C6, false);
        if (filtered.isEmpty()) g.drawString(font, text("empty"), left + 4, listY + 5, 0xFFB7C1C6, false);
        g.drawString(font, font.plainSubstrByWidth(status.getString(), contentWidth), left, height - 40, 0xFFE1C184, false);
        if (my >= height - 42 && my < height - 28 && mx >= left && mx < left + contentWidth)
            setTooltipForNextRenderPass(status);
        suppressBackground = true;
        try { super.render(g, mx, my, delta); }
        finally { suppressBackground = false; }
        list.renderScrollbar(g);
    }
}

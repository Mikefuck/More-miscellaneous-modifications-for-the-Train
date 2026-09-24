package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.habitrain.lottery.client.LotteryClientNetwork;
import com.habitrain.lottery.network.CardUseMenuS2C;
import com.habitrain.lottery.network.CardUseRequestC2S;
import com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState;
import com.habitrain.lottery.network.WarehouseNetwork;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.warehouse.WarehouseEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.agmas.noellesroles.client.screen.LootInfoScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The account reward warehouse, including the entire card-use flow in one screen. */
public final class WarehouseScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.warehouse.";
    private enum Page { WAREHOUSE, CARDS, ROLES }
    private final Screen parent;
    private WarehouseLayout layout;
    private Page page = Page.WAREHOUSE;
    private String filter = "all", query = "";
    private boolean sortByCount, reducedMotion;
    private List<WarehouseEntry> inventory = List.of();
    private List<WarehouseRole> roles = List.of();
    private final List<Tile> tiles = new ArrayList<>();
    private List<Tile> outgoing = List.of();
    private final List<Control> controls = new ArrayList<>();
    private EditBox search;
    private double scroll;
    private long opened = -1, changed = -1000, lastFrame, requestAt;
    private float delta = 16, switchDirection = 1;
    private int requestId, requestAttempts, expectedTotal = -1, cardEpoch;
    private long cardRequestAt;
    private int cardAttempts;
    private final List<WarehouseEntry> incoming = new ArrayList<>();
    private boolean loading, known, cardsKnown, refreshAfterReturn;
    private String error = "", notice = "", waitingCard = "";
    private long waitingSince, submitAt = -1, pendingSince = -1;
    private String submitKey = "", submitMode = "", submitRole = "";
    private Tile detail;
    private long detailAt;
    private boolean detailClosing;
    private int detailScroll, detailMaxScroll;
    private Control detailBack, detailAction;

    public WarehouseScreen(Screen parent) { super(text("title")); this.parent = parent; }
    private static Component text(String suffix, Object... args) { return Component.translatable(KEY + suffix, args); }
    private static Component translated(String key) { return Component.translatableWithFallback(key, key); }
    private static long now() { return Util.getMillis(); }

    @Override protected void init() {
        layout = WarehouseLayout.of(width, height);
        boolean first = opened < 0;
        if (first) { opened = now(); lastFrame = opened; }
        detail = null;
        rebuildChrome();
        rebuildGrid(false);
        if (first || refreshAfterReturn) { refreshAfterReturn = false; refresh(true); }
    }

    private void rebuildChrome() {
        clearWidgets(); controls.clear();
        int x = layout.x(), w = layout.width();
        controls.add(control(x, 8, 64, 24, text("warehouse"), () -> switchPage(Page.WAREHOUSE)));
        controls.add(control(x + 68, 8, 64, 24, text("cards"), () -> switchPage(Page.CARDS)));
        controls.add(control(x + w - 46, 8, 46, 24, Component.translatable("gui.back"), this::onClose));
        String[] filters = page == Page.WAREHOUSE ? new String[]{"all", "currency", "special", "appearance"}
                : page == Page.CARDS ? new String[]{"all", "owned", "usable"} : new String[]{"all", "available", "bound"};
        int fw = Math.min(84, (w - 6) / filters.length);
        for (int i = 0; i < filters.length; i++) {
            String id = filters[i];
            controls.add(control(x + i * fw, 40, fw - 3, 19, text("filter." + id), () -> {
                if (filter.equals(id)) return;
                filter = id; scroll = 0; rebuildGrid(true);
            }));
        }
        int searchW = Math.max(64, w - 147);
        search = new EditBox(font, x + 5, 69, searchW - 10, 16, text("search"));
        search.setBordered(false); search.setMaxLength(96); search.setHint(text("search"));
        search.setValue(query); search.setResponder(value -> {
            query = value; scroll = 0; rebuildGrid(false);
        });
        addRenderableWidget(search);
        controls.add(control(x + searchW + 5, 66, 52, 22, text(sortByCount ? "count_sort" : "name_sort"), () -> {
            sortByCount = !sortByCount; rebuildChrome(); rebuildGrid(true);
        }));
        controls.add(control(x + searchW + 61, 66, 42, 22, text("refresh"), () -> refresh(true)));
        Control motion = control(x + w - 39, 66, 39, 22, text(reducedMotion ? "motion_off" : "motion_on"), () -> {
            reducedMotion = !reducedMotion; outgoing = List.of(); changed = -1000;
            rebuildChrome(); rebuildGrid(false);
        });
        motion.setTooltip(Tooltip.create(text("motion_hint"))); controls.add(motion);
        detailBack = control(width - drawerWidth() + 10, 46, 55, 20, Component.translatable("gui.back"), this::closeDetail);
        detailAction = control(width - drawerWidth() + 12, height - 42, drawerWidth() - 24, 24, text("use"), this::detailAction);
        detailBack.visible = detailAction.visible = false;
    }

    private Control control(int x, int y, int w, int h, Component label, Runnable click) {
        return addRenderableWidget(new Control(x, y, w, h, label, click));
    }

    private void switchPage(Page next) {
        if (next == page || busy()) return;
        switchDirection = next.ordinal() >= page.ordinal() ? 1 : -1;
        captureOutgoing();
        page = next; filter = "all"; query = ""; scroll = 0; detail = null;
        rebuildChrome(); rebuildGrid(false); changed = now();
    }

    private void captureOutgoing() {
        outgoing = tiles.stream().filter(t -> t.getY() + t.getHeight() > layout.top() && t.getY() < layout.bottom()).toList();
    }

    private void rebuildGrid(boolean animate) {
        if (layout == null) return;
        if (animate) { captureOutgoing(); changed = now(); }
        for (Tile tile : tiles) removeWidget(tile);
        tiles.clear();
        if (getFocused() instanceof Tile) setFocused(null);
        List<Tile> result = new ArrayList<>();
        if (page == Page.ROLES) {
            for (WarehouseRole role : roles) {
                if ("available".equals(filter) && role.taken || "bound".equals(filter) && !role.isBound()) continue;
                WarehouseEntry entry = new WarehouseEntry("role", role.id, role.displayName(),
                        role.isBound() ? text("bound_to", role.boundDisplayName()).getString() : text("role_hint").getString(),
                        "minecraft:paper", role.taken ? 0 : 1, role.color, false);
                Tile tile = new Tile(entry, role);
                if (matches(tile)) result.add(tile);
            }
        } else {
            for (WarehouseEntry original : inventory) {
                WarehouseEntry entry = original;
                if ("card".equals(entry.kind()) && cardsKnown) entry = new WarehouseEntry(entry.kind(), entry.id(), entry.name(),
                        entry.description(), entry.icon(), ClientLotteryState.cardBalances.getOrDefault(entry.id(), entry.count()), entry.color(), false);
                if (page == Page.CARDS && !entry.kind().equals("card")) continue;
                if (page == Page.WAREHOUSE) {
                    if (entry.count() <= 0 && !entry.kind().equals("currency")) continue;
                    if (!"all".equals(filter) && !("appearance".equals(filter)
                            ? entry.kind().equals("skin") || entry.kind().equals("title") : entry.kind().equals(filter))) continue;
                } else {
                    if ("owned".equals(filter) && entry.count() <= 0 || "usable".equals(filter) && !canUse(entry)) continue;
                }
                Tile tile = new Tile(entry, null);
                if (matches(tile)) result.add(tile);
            }
        }
        Comparator<Tile> order = Comparator.comparing(t -> t.getMessage().getString(), String.CASE_INSENSITIVE_ORDER);
        if (sortByCount) order = Comparator.<Tile>comparingInt(t -> t.entry.count()).reversed().thenComparing(order);
        // Currency first in the warehouse; other categories share the reference's uniform grid.
        if (page == Page.WAREHOUSE) order = Comparator.<Tile>comparingInt(t -> t.entry.kind().equals("currency") ? 0 : 1).thenComparing(order);
        result.sort(order);
        tiles.addAll(result);
        for (int i = 0; i < tiles.size(); i++) { tiles.get(i).index = i; addWidget(tiles.get(i)); }
        scroll = Mth.clamp(scroll, 0, layout.maxScroll(tiles.size()));
        positionTiles();
    }

    private boolean matches(Tile tile) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        String name = tile.getMessage().getString();
        if (q.isEmpty() || (name + " " + tile.entry.id() + " " + tile.entry.description()).toLowerCase(Locale.ROOT).contains(q)) return true;
        try { return io.wifi.starrailexpress.client.util.PinYinUtils.contains(q, name); }
        catch (RuntimeException ex) { return false; }
    }

    private void positionTiles() {
        for (Tile t : tiles) {
            t.setX(layout.tileX(t.index)); t.setY(layout.tileY(t.index, scroll));
            t.visible = t.getY() + t.getHeight() > layout.top() && t.getY() < layout.bottom();
            t.active = t.visible && detail == null && !transitioning() && !busy();
        }
    }

    private void refresh(boolean resetAttempts) {
        if (busy() || loading && now() - requestAt < 1000) return;
        if (resetAttempts) { requestAttempts = 0; cardsKnown = false; }
        if (!ClientPlayNetworking.canSend(WarehouseNetwork.Request.TYPE)) { error = "unavailable"; loading = false; return; }
        requestId = java.util.concurrent.ThreadLocalRandom.current().nextInt();
        incoming.clear(); expectedTotal = -1; loading = true; error = ""; requestAt = now(); requestAttempts++;
        ClientPlayNetworking.send(new WarehouseNetwork.Request(requestId));
        if (!CardGuiGameState.gameActiveOrStarting() && ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) {
            cardEpoch = ClientLotteryState.cardInventoryVersion;
            if (resetAttempts) cardAttempts = 0;
            requestCards();
        }
    }

    private void requestCards() {
        cardRequestAt = now(); cardAttempts++;
        ClientPlayNetworking.send(new CardUseRequestC2S("inventory"));
    }

    public void receive(WarehouseNetwork.Snapshot snapshot) {
        if (!loading || snapshot.requestId() != requestId) return;
        if (!snapshot.error().isEmpty()) { loading = false; error = snapshot.error(); return; }
        if (snapshot.offset() != incoming.size() || expectedTotal >= 0 && expectedTotal != snapshot.total()) {
            loading = false; error = "sync_error"; return;
        }
        expectedTotal = snapshot.total(); incoming.addAll(snapshot.entries());
        if (incoming.size() == expectedTotal) {
            inventory = List.copyOf(incoming); loading = false; known = true; error = "";
            rebuildGrid(false);
        }
    }

    /** A delayed response never opens a closed screen or interrupts a different request. */
    public void receiveCardMenu(CardUseMenuS2C payload) {
        if (waitingCard.isEmpty() || !waitingCard.equals(payload.questKey()) || CardGuiGameState.gameActiveOrStarting()) return;
        waitingCard = "";
        if ("self_select".equals(payload.questKey())) {
            try {
                List<WarehouseRole> parsed = new Gson().fromJson(payload.candidatesJson(), new TypeToken<List<WarehouseRole>>() {}.getType());
                roles = parsed == null ? List.of() : parsed.stream().filter(r -> r != null && r.id != null && !r.id.isBlank()).toList();
                closeDetailImmediately(); switchPage(Page.ROLES);
            } catch (RuntimeException e) { notice = "sync_error"; }
        } else {
            // Faction activation uses an explicit confirmation inside the new detail drawer.
            notice = "";
        }
    }

    private boolean canUse(WarehouseEntry e) {
        if (!cardsKnown || CardGuiGameState.gameActiveOrStarting() || busy() || !error.isEmpty() || loading
                || ClientLotteryState.cardBalances.getOrDefault(e.id(), 0) <= 0) return false;
        return "limit_break".equals(e.id()) || ("self_select".equals(e.id())
                ? ClientLotteryState.cardUseRemainingSelfUses : ClientLotteryState.cardUseRemainingUses) > 0;
    }

    private boolean busy() { return submitAt >= 0 || pendingSince >= 0 || !waitingCard.isEmpty(); }
    private boolean transitioning() { return !reducedMotion && (now() - opened < WarehouseMotion.OPEN_MS || now() - changed < WarehouseMotion.SWITCH_MS); }

    private void openDetail(Tile tile) {
        if (busy() || transitioning()) return;
        detail = tile; detailAt = now(); detailClosing = false; detailScroll = 0; notice = "";
        setFocused(detailBack); updateControls();
    }

    private void closeDetail() {
        if (busy()) return;
        detailClosing = true; detailAt = now();
        if (reducedMotion) closeDetailImmediately();
    }

    private void closeDetailImmediately() {
        Tile previous = detail;
        detail = null; detailClosing = false;
        detailBack.visible = detailAction.visible = false;
        if (previous != null && tiles.contains(previous)) setFocused(previous); else setFocused(null);
        updateControls();
    }

    private void detailAction() {
        if (detail == null || busy() || detailClosing) return;
        WarehouseEntry e = detail.entry;
        if ("card".equals(e.kind())) {
            if (!canUse(e)) return;
            if ("self_select".equals(e.id())) {
                waitingCard = e.id(); waitingSince = now();
                ClientPlayNetworking.send(new CardUseRequestC2S(e.id()));
            } else prepareSubmit(e.id(), "limit_break".equals(e.id()) ? "bonus" : "direct", "");
        } else if ("role".equals(e.kind())) {
            if (detail.role.taken || !cardsKnown || CardGuiGameState.gameActiveOrStarting()
                    || ClientLotteryState.cardBalances.getOrDefault("self_select", 0) <= 0
                    || ClientLotteryState.cardUseRemainingSelfUses <= 0) return;
            prepareSubmit("self_select", "self", detail.role.id);
        } else if ("skin".equals(e.kind()) || "title".equals(e.kind())) minecraft.setScreen(new SkinWardrobeScreen(this));
        else if ("currency".equals(e.kind())) minecraft.setScreen(new LootInfoScreen());
    }

    private void prepareSubmit(String key, String mode, String role) {
        submitKey = key; submitMode = mode; submitRole = role; submitAt = now(); notice = "";
    }

    @Override public void tick() {
        long time = now();
        boolean inGame = CardGuiGameState.gameActiveOrStarting();
        if (inGame && (busy() || page == Page.ROLES || detail != null && detail.entry.kind().equals("card"))) {
            submitAt = pendingSince = -1; waitingCard = ""; closeDetailImmediately();
            if (page == Page.ROLES) switchPage(Page.CARDS);
            notice = "lobby_only";
        }
        if (ClientLotteryState.cardInventoryVersion != cardEpoch) {
            cardEpoch = ClientLotteryState.cardInventoryVersion; cardsKnown = true;
            if (pendingSince >= 0) {
                pendingSince = -1; closeDetailImmediately();
                if (page == Page.ROLES) switchPage(Page.CARDS);
                notice = "receipt"; refresh(true);
            }
            rebuildGrid(false);
        }
        if (!cardsKnown && !busy() && !inGame && cardAttempts < 4 && time - cardRequestAt > 1200
                && ClientPlayNetworking.canSend(CardUseRequestC2S.TYPE)) requestCards();
        if (loading && time - requestAt > 4000) {
            loading = false;
            if (requestAttempts < 3) refresh(false); else error = "timeout";
        }
        if (!waitingCard.isEmpty() && time - waitingSince > 5000) { waitingCard = ""; notice = "timeout"; }
        if (submitAt >= 0 && time - submitAt >= (reducedMotion ? 0 : 180)) {
            submitAt = -1;
            if (!inGame) {
                cardEpoch = ClientLotteryState.cardInventoryVersion;
                pendingSince = time;
                if (!LotteryClientNetwork.clientCardUseConfirm(submitKey, submitMode, submitRole)) {
                    pendingSince = -1; notice = "sync_error";
                }
            }
        }
        if (pendingSince >= 0 && time - pendingSince > 6000) {
            pendingSince = -1; cardsKnown = false; notice = "timeout"; refresh(true);
        }
        if (detailClosing && time - detailAt > 180) closeDetailImmediately();
        if (time - changed > WarehouseMotion.SWITCH_MS) outgoing = List.of();
        updateControls();
        super.tick();
    }

    private void updateControls() {
        boolean modal = detail != null;
        for (Control c : controls) c.active = !modal && !busy();
        if (search != null) search.active = !modal && !busy();
        if (detailBack == null) return;
        detailBack.visible = detailAction.visible = modal;
        detailBack.active = modal && !busy() && !detailClosing;
        detailAction.active = false;
        if (modal) {
            var e = detail.entry;
            if (e.kind().equals("card")) {
                detailAction.setMessage(text(busy() ? "pending" : e.id().equals("self_select") ? "choose_role" : "confirm_use"));
                detailAction.active = canUse(e);
            } else if (e.kind().equals("role")) {
                detailAction.setMessage(text(busy() ? "pending" : "confirm_role"));
                detailAction.active = !busy() && !detail.role.taken && cardsKnown && !CardGuiGameState.gameActiveOrStarting()
                        && ClientLotteryState.cardBalances.getOrDefault("self_select", 0) > 0 && ClientLotteryState.cardUseRemainingSelfUses > 0;
            } else {
                boolean appearance = e.kind().equals("skin") || e.kind().equals("title");
                detailAction.setMessage(text(appearance ? "wardrobe" : e.kind().equals("currency") ? "lottery" : "stored"));
                detailAction.active = !busy() && (appearance || e.kind().equals("currency"));
            }
            detailAction.active &= !detailClosing && (reducedMotion || now() - detailAt >= 220);
        }
        positionTiles();
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now(); delta = Math.min(80, time - lastFrame); lastFrame = time;
        float open = reducedMotion ? 1 : WarehouseMotion.ease(WarehouseMotion.progress(time, opened, 380));
        WarehouseTheme.background(g, width, height, open);
        g.fill(0, 0, width, 36, WarehouseTheme.alpha(0xA92D3232, open));
        g.fill(0, 37, width, 61, WarehouseTheme.alpha(0x8A202525, open));
        g.hLine(0, width, 36, WarehouseTheme.alpha(0x606E7775, open));
        int ux = layout.x() + (page == Page.WAREHOUSE ? 0 : 68);
        if (!reducedMotion && time - changed < 320) ux -= Math.round(switchDirection * 22 * (1 - WarehouseMotion.ease(WarehouseMotion.progress(time, changed, 320))));
        g.fill(ux + 4, 32, ux + 60, 34, WarehouseTheme.BLUE);
        if (width > 470) {
            String account = minecraft.player == null ? "" : minecraft.player.getGameProfile().getName();
            drawTrim(g, Component.literal(account), layout.x() + layout.width() - 198, 16, 142, WarehouseTheme.MUTED);
        }
        g.fill(layout.x(), 66, layout.x() + Math.max(64, layout.width() - 147), 88, 0x80323738);
        boolean switching = !reducedMotion && time - changed < 320;
        float progress = switching ? WarehouseMotion.progress(time, changed, 320) : 1;
        g.enableScissor(layout.x(), layout.top(), layout.x() + layout.width(), layout.bottom());
        if (switching && progress < .5f) {
            float p = WarehouseMotion.ease(progress * 2);
            g.pose().pushPose(); g.pose().translate(-switchDirection * 28 * p, 0, 0);
            for (Tile t : outgoing) t.paint(g, -1, -1, 1 - p, 0);
            g.pose().popPose();
        } else {
            float p = switching ? WarehouseMotion.ease((progress - .5f) * 2) : 1;
            g.pose().pushPose(); g.pose().translate(switchDirection * 28 * (1 - p), 0, 0);
            int firstRow = Math.max(0, (int) scroll / layout.rowHeight());
            for (Tile t : tiles) if (t.visible) {
                float enter = reducedMotion ? 1 : WarehouseMotion.reveal(time, opened + 100, Math.max(0, t.index - firstRow * layout.columns()));
                t.paint(g, detail == null && !transitioning() ? mx : -1, detail == null && !transitioning() ? my : -1,
                        open * p * enter, Math.round((1 - enter) * 18));
            }
            g.pose().popPose();
        }
        if (tiles.isEmpty() && known && !loading) {
            g.drawCenteredString(font, text("empty"), width / 2, layout.top() + 30, WarehouseTheme.TEXT);
            g.drawCenteredString(font, text(query.isBlank() ? "empty_hint" : "search_empty"), width / 2, layout.top() + 48, WarehouseTheme.MUTED);
        }
        if (!known && loading) {
            for (int i = 0; i < layout.columns(); i++) {
                int x = layout.tileX(i), y = layout.top();
                g.fill(x, y, x + layout.tileWidth(), y + layout.tileHeight(), 0x40343C3D);
                g.fill(x + 8, y + layout.tileHeight() - 26, x + layout.tileWidth() - 16, y + layout.tileHeight() - 22, 0x506C7777);
            }
            g.drawCenteredString(font, text("loading"), width / 2, layout.top() + 30, WarehouseTheme.TEXT);
        }
        g.disableScissor();
        drawScrollbar(g);
        g.fill(0, height - 24, width, height, 0xBC272D2F);
        Component status = !error.isEmpty() ? text(error) : !notice.isEmpty() ? text(notice) : loading ? text("loading")
                : page == Page.WAREHOUSE ? text("asset_count", tiles.size())
                : cardsKnown ? text("quotas", Math.max(0, ClientLotteryState.cardUseRemainingUses), Math.max(0, ClientLotteryState.cardUseRemainingSelfUses)) : text("card_sync");
        drawTrim(g, status, layout.x(), height - 16, layout.width(), error.isEmpty() ? WarehouseTheme.MUTED : 0xFFFFBAA2);
        for (int i = 3; i < controls.size(); i++) {
            Control c = controls.get(i);
            if (c.getY() == 40 && c.getMessage().getString().equals(text("filter." + filter).getString()))
                g.fill(c.getX(), 40, c.getX() + c.getWidth(), 59, 0x804D7381);
        }
        // Widgets are drawn once. Drawer controls are drawn separately in their translated layer.
        detailBack.visible = detailAction.visible = false;
        super.render(g, detail == null ? mx : -1, detail == null ? my : -1, partialTick);
        detailBack.visible = detailAction.visible = detail != null;
        if (detail != null) drawDetail(g, mx, my, partialTick);
        else if (!transitioning() && layout.contains(mx, my)) {
            for (Tile t : tiles) if (t.visible && t.isMouseOver(mx, my)) {
                var tooltip = new ArrayList<>(font.split(t.getMessage(), Math.min(240, width - 24)));
                if (t.entry.kind().equals("skin")) tooltip.add(SkinQualityStyle.label(t.entry.quality()).getVisualOrderText());
                tooltip.add(text("inspect").getVisualOrderText());
                g.renderTooltip(font, tooltip, mx, my); break;
            }
        }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) { /* Single background in render. */ }

    private void drawScrollbar(GuiGraphics g) {
        int max = layout.maxScroll(tiles.size());
        if (max <= 0) return;
        int h = Math.max(16, layout.viewportHeight() * layout.viewportHeight() / (layout.viewportHeight() + max));
        int x = layout.x() + layout.width() - 3;
        int y = layout.top() + (int) ((layout.viewportHeight() - h) * scroll / max);
        g.fill(x, layout.top(), x + 2, layout.bottom(), 0x405B6465);
        g.fill(x, y, x + 2, y + h, 0xFF9DAAAA);
    }

    private int drawerWidth() { return Math.min(280, Math.max(226, width * 2 / 5)); }

    private void drawDetail(GuiGraphics g, int mx, int my, float partialTick) {
        float p = reducedMotion ? 1 : WarehouseMotion.ease(WarehouseMotion.progress(now(), detailAt, detailClosing ? 180 : 240));
        if (detailClosing) p = 1 - p;
        int w = drawerWidth(), x = width - w;
        // Item rendering adds 150/200 to Z: both the scrim and drawer must cover those items.
        g.pose().pushPose(); g.pose().translate(0, 0, 350);
        g.fill(0, 36, width, height - 24, WarehouseTheme.alpha(0x980B1012, p));
        g.pose().popPose();
        g.pose().pushPose(); g.pose().translate((1 - p) * w, 0, 400);
        g.fill(x, 36, width, height, 0xFF343B3D); g.fill(x, 36, x + 2, height, detail.accent);
        if (detail.entry.kind().equals("skin")) g.fillGradient(x + 2, 36, width, height,
                SkinQualityStyle.top(detail.entry.quality(), 0), SkinQualityStyle.bottom(detail.entry.quality()));
        int artH = Math.min(128, Math.max(48, height / 4));
        g.enableScissor(x + 3, 73, width, height - 72);
        g.pose().pushPose(); g.pose().translate(0, -detailScroll, 0);
        detail.drawIcon(g, x + w / 2, 75 + artH / 2, artH - 8, p);
        int y = 82 + artH;
        drawTrim(g, detail.getMessage(), x + 14, y, w - 28, WarehouseTheme.TEXT);
        g.drawString(font, text("quantity", detail.entry.count()), x + 14, y + 16, WarehouseTheme.GREEN, false);
        String description = translated(detail.entry.description()).getString();
        if (detail.entry.kind().equals("skin")) description = SkinQualityStyle.label(detail.entry.quality()).getString() + "\n" + description;
        if (detail.entry.equipped()) description += "\n" + text("equipped").getString();
        if (detail.entry.kind().equals("card")) {
            description += "\n" + text(!cardsKnown ? "card_sync" : CardGuiGameState.gameActiveOrStarting() ? "lobby_only"
                    : detail.entry.count() <= 0 ? "not_owned" : !canUse(detail.entry) && !busy() ? "no_uses" : "cost_one").getString();
        }
        if (detail.role != null && detail.role.taken) description += "\n" + text("taken").getString();
        var wrapped = font.split(Component.literal(description), w - 28);
        for (int i = 0; i < wrapped.size(); i++) g.drawString(font, wrapped.get(i), x + 14, y + 35 + i * 11, WarehouseTheme.MUTED, false);
        detailMaxScroll = Math.max(0, y + 35 + wrapped.size() * 11 - (height - 76));
        detailScroll = Math.min(detailScroll, detailMaxScroll);
        g.pose().popPose(); g.disableScissor();
        if (detailMaxScroll > 0) {
            int sy = 75 + (height - 168) * detailScroll / detailMaxScroll;
            g.fill(width - 4, sy, width - 2, sy + 18, WarehouseTheme.BLUE);
        }
        if (!notice.isEmpty()) drawTrim(g, text(notice), x + 14, height - 62, w - 28, 0xFFFFBAA2);
        if (submitAt >= 0) g.fill(x + 12, height - 44, x + 12 + Math.round((w - 24) * WarehouseMotion.progress(now(), submitAt, 180)), height - 42, WarehouseTheme.GREEN);
        detailBack.render(g, p == 1 ? mx : -1, p == 1 ? my : -1, partialTick);
        detailAction.render(g, p == 1 ? mx : -1, p == 1 ? my : -1, partialTick);
        g.pose().popPose();
    }

    private void drawTrim(GuiGraphics g, Component value, int x, int y, int w, int color) {
        String raw = value.getString();
        String trimmed = font.width(raw) <= w ? raw : font.plainSubstrByWidth(raw, Math.max(0, w - font.width("…"))) + "…";
        g.drawString(font, trimmed, x, y, color, false);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false;
        if (detail != null) {
            if (busy() || detailClosing || !reducedMotion && now() - detailAt < 240) return true;
            if (detailBack.mouseClicked(x, y, button)) { setFocused(detailBack); return true; }
            if (detailAction.mouseClicked(x, y, button)) { setFocused(detailAction); return true; }
            if (x < width - drawerWidth()) closeDetail();
            return true;
        }
        if (transitioning() || busy()) return true;
        if (layout.contains(x, y)) {
            if (x >= layout.x() + layout.width() - 6 && layout.maxScroll(tiles.size()) > 0) { dragScroll(y); return true; }
            for (Tile t : tiles) if (t.visible && t.isMouseOver(x, y)) { setFocused(t); openDetail(t); return true; }
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    private void dragScroll(double y) {
        scroll = Mth.clamp((y - layout.top()) / layout.viewportHeight(), 0, 1) * layout.maxScroll(tiles.size());
        positionTiles();
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && detail == null && !busy() && x >= layout.x() + layout.width() - 8 && x <= layout.x() + layout.width()) {
            dragScroll(y); return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (detail != null && x >= width - drawerWidth()) {
            detailScroll = Mth.clamp(detailScroll - (int) (vertical * 22), 0, detailMaxScroll); return true;
        }
        if (detail != null || busy() || transitioning() || !layout.contains(x, y)) return false;
        scroll = Mth.clamp(scroll - vertical * layout.rowHeight() * .48, 0, layout.maxScroll(tiles.size()));
        positionTiles(); return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) {
            if (detail != null) { closeDetail(); return true; }
            if (page == Page.ROLES) { switchPage(Page.CARDS); return true; }
            onClose(); return true;
        }
        if (busy() || transitioning()) return true;
        if (detail != null) {
            if (key == 264 || key == 265 || key == 266 || key == 267) {
                detailScroll = Mth.clamp(detailScroll + (key == 264 || key == 267 ? 1 : -1) * (key >= 266 ? 66 : 22), 0, detailMaxScroll); return true;
            }
            if (key == 258) { setFocused(getFocused() == detailBack && detailAction.active ? detailAction : detailBack); return true; }
            return getFocused() instanceof Control c && c.keyPressed(key, scan, modifiers);
        }
        if (search.isFocused()) return super.keyPressed(key, scan, modifiers);
        int step = switch (key) { case 263 -> -1; case 262 -> 1; case 265 -> -layout.columns(); case 264 -> layout.columns(); default -> 0; };
        if (step != 0 && !tiles.isEmpty()) {
            int current = getFocused() instanceof Tile t ? t.index : -1;
            Tile target = tiles.get(Mth.clamp(current < 0 ? 0 : current + step, 0, tiles.size() - 1));
            int y = layout.tileY(target.index, scroll);
            if (y < layout.top()) scroll -= layout.top() - y;
            else if (y + layout.tileHeight() > layout.bottom()) scroll += y + layout.tileHeight() - layout.bottom();
            scroll = Mth.clamp(scroll, 0, layout.maxScroll(tiles.size()));
            positionTiles(); setFocused(target); return true;
        }
        if ((key == 257 || key == 335 || key == 32) && getFocused() instanceof Tile t) { openDetail(t); return true; }
        if (key == 266 || key == 267) {
            scroll = Mth.clamp(scroll + (key == 267 ? 1 : -1) * layout.viewportHeight(), 0, layout.maxScroll(tiles.size()));
            positionTiles(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean charTyped(char c, int modifiers) { return detail == null && !busy() && super.charTyped(c, modifiers); }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public void removed() { refreshAfterReturn = true; }
    @Override public boolean isPauseScreen() { return false; }

    private final class Control extends AbstractWidget {
        private final Runnable action;
        Control(int x, int y, int w, int h, Component message, Runnable action) { super(x, y, w, h, message); this.action = action; }
        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean hover = active && (isHoveredOrFocused());
            if (this == detailAction) {
                g.fill(getX(), getY(), getX() + width, getY() + height, active ? 0xFF4C6D70 : 0xFF303739);
                g.fill(getX(), getY() + height - 1, getX() + width, getY() + height,
                        active ? WarehouseTheme.BLUE : 0xFF626868);
            }
            if (hover) g.fill(getX(), getY(), getX() + width, getY() + height, 0x604F7480);
            if (isFocused()) g.renderOutline(getX(), getY(), width, height, WarehouseTheme.BLUE);
            int color = active ? WarehouseTheme.TEXT : 0xFF858F92;
            String s = font.plainSubstrByWidth(getMessage().getString(), width - 6);
            g.drawString(font, s, getX() + (width - font.width(s)) / 2, getY() + (height - 8) / 2, color, false);
        }
        @Override public void onClick(double x, double y) { if (active) action.run(); }
        @Override public boolean keyPressed(int key, int scan, int mods) {
            if (active && (key == 257 || key == 335 || key == 32)) { action.run(); return true; } return false;
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    private final class Tile extends AbstractWidget {
        final WarehouseEntry entry;
        final WarehouseRole role;
        final ItemStack icon;
        final ResourceLocation art;
        final int accent;
        int index;
        float hover;
        Tile(WarehouseEntry entry, WarehouseRole role) {
            super(0, 0, layout.tileWidth(), layout.tileHeight(), entryName(entry));
            this.entry = entry; this.role = role;
            accent = 0xFF000000 | (entry.kind().equals("skin") ? entry.quality().color()
                    : entry.kind().equals("card") ? WarehouseTheme.cardColor(entry.id()) : entry.color());
            String artId = role != null ? role.cardArt() : entry.kind().equals("card") ? entry.id() : null;
            ResourceLocation candidate = artId != null ? ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/cards/" + artId + ".png") : null;
            art = candidate != null && minecraft.getResourceManager().getResource(candidate).isPresent() ? candidate : null;
            ItemStack stack = ItemStack.EMPTY;
            if (entry.kind().equals("skin")) stack = SkinItems.preview(entry.id());
            if (stack.isEmpty()) {
                ResourceLocation item = ResourceLocation.tryParse(entry.icon());
                stack = new ItemStack(item == null ? Items.CHEST : BuiltInRegistries.ITEM.get(item));
            }
            icon = stack.isEmpty() ? new ItemStack(Items.CHEST) : stack;
        }
        private static Component entryName(WarehouseEntry entry) {
            if (entry.kind().equals("skin")) {
                String s = translated(entry.name()).getString();
                if (s.equals(entry.name())) {
                    String[] parts = entry.id().split("/", 2);
                    String legacy = parts.length == 2 ? "screen.sre.skins." + parts[0] + "." + parts[1] + ".name" : entry.id();
                    return Component.translatableWithFallback(legacy, parts[parts.length - 1]);
                }
            }
            return translated(entry.name());
        }
        void paint(GuiGraphics g, int mx, int my, float opacity, int drop) {
            if (opacity < .01) return;
            hover = GuiFx.approach(hover, isMouseOver(mx, my) || isFocused() && detail == null ? 1 : 0, delta, 90);
            int x = getX(), y = getY() + drop, artHeight = height - 45;
            boolean skin = entry.kind().equals("skin");
            g.fillGradient(x, y, x + width, y + artHeight,
                    WarehouseTheme.alpha(skin ? SkinQualityStyle.top(entry.quality(), hover) : GuiFx.mix(0xFF727570, accent, .12f + hover * .08f), opacity),
                    WarehouseTheme.alpha(skin ? SkinQualityStyle.bottom(entry.quality()) : GuiFx.mix(0xFF484E4E, accent, .10f), opacity));
            g.fill(x, y + artHeight, x + width, y + height,
                    WarehouseTheme.alpha(skin ? SkinQualityStyle.bottom(entry.quality()) : 0xE52A302F, opacity));
            g.fill(x, y + artHeight - 2, x + width, y + artHeight, WarehouseTheme.alpha(accent, opacity * .85f));
            drawIcon(g, x + width / 2, y + artHeight / 2, Math.min(width - 16, artHeight - 10) + Math.round(hover * 3), opacity);
            if (entry.equipped()) {
                g.fill(x + 3, y + artHeight - 13, x + 42, y + artHeight - 3, WarehouseTheme.alpha(0xFF788E43, opacity));
                drawTrim(g, text("equipped"), x + 5, y + artHeight - 12, 36, WarehouseTheme.alpha(WarehouseTheme.TEXT, opacity));
            }
            var lines = font.split(getMessage(), width - 10);
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), x + 5, y + artHeight + 5 + i * 10, WarehouseTheme.alpha(WarehouseTheme.TEXT, opacity), false);
            Component count = role == null ? text("quantity", entry.count()) : text(role.taken ? "taken" : "available");
            drawTrim(g, count, x + 5, y + height - 12, width - 20, WarehouseTheme.alpha(entry.count() > 0 ? WarehouseTheme.GREEN : WarehouseTheme.MUTED, opacity));
            g.drawString(font, "i", x + width - 9, y + height - 12, WarehouseTheme.alpha(WarehouseTheme.MUTED, opacity), false);
            if (hover > .05 || isFocused()) g.renderOutline(x, y, width, height, WarehouseTheme.alpha(WarehouseTheme.BLUE, opacity * Math.max(.5f, hover)));
        }
        void drawIcon(GuiGraphics g, int cx, int cy, int size, float opacity) {
            g.pose().pushPose();
            if (art != null) {
                g.setColor(1, 1, 1, opacity);
                g.blit(art, cx - size / 2, cy - size / 2, size, size, 0, 0, 128, 128, 128, 128);
                g.setColor(1, 1, 1, 1);
            } else {
                float scale = size / 16f;
                g.pose().translate(cx - size / 2f, cy - size / 2f, 0); g.pose().scale(scale, scale, 1);
                g.renderFakeItem(icon, 0, 0);
            }
            g.pose().popPose();
        }
        @Override protected void renderWidget(GuiGraphics g, int x, int y, float pt) { paint(g, x, y, 1, 0); }
        @Override public void onClick(double x, double y) { openDetail(this); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            return layout.contains(x, y) && detail == null && !transitioning() && super.mouseClicked(x, y, button);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput out) {
            out.add(NarratedElementType.TITLE, getMessage());
            out.add(NarratedElementType.POSITION, text("quantity", entry.count()));
            out.add(NarratedElementType.HINT, entry.kind().equals("skin")
                    ? SkinQualityStyle.label(entry.quality()).copy().append(" · ").append(text("inspect")) : text("inspect"));
        }
    }
}

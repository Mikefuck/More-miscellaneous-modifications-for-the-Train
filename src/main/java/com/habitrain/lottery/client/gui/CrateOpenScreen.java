package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.network.CrateNetwork;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 开箱界面：固定 1920×1080 参考画布，按 {@link CrateStage} 的时间轴逐层绘制。
 *
 * <p>分层：庭院背景 → 背景粒子 → 箱子与展台（或展示舞台）→ 奖励橱窗 / 转盘 → 暗场 →
 * 展示物品 → 前景粒子 → 确认弹窗 → 标题与操作栏 → 按钮。发光件全部走 {@link CrateFx} 的加色混合。</p>
 *
 * <p>节奏：落箱扶正 → 确认 → 蓄力（箱体震颤、缝隙漏光、能量汇聚）→ 爆开（闪白、冲击环、火花、光柱）→
 * 转盘减速 → 中奖卡按品质绽放 → 暗场 → 物品登场（品质闪光、射线、星芒；金 / 红追加彩屑与震屏）。</p>
 */
public final class CrateOpenScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.crate.";

    // =====================================================================
    // 画布与分层
    // =====================================================================

    private static final float REF_W = 1920.0F, REF_H = 1080.0F;
    /** 物品渲染自身会再加 150 的深度，因此覆盖层必须高出物品层。 */
    private static final float Z_PARTICLES_BACK = 10, Z_SCENE = 20, Z_STRIP = 100, Z_REEL = 100,
            Z_REEL_LIGHT = 105, Z_REEL_OVER = 300, Z_BRIDGE = 400, Z_REVEAL_ITEM = 420,
            Z_MODAL = 480, Z_PARTICLES_FRONT = 600, Z_UI = 650, Z_HOTSPOT = 700;

    // ---- 顶部标题 ----
    private static final float HEAD_EYEBROW_Y = 34, HEAD_TITLE_Y = 58, HEAD_TITLE_SIZE = 42,
            HEAD_CHIP_Y0 = 120, HEAD_CHIP_Y1 = 154;

    // ---- 奖励橱窗 ----
    private static final float STRIP_X0 = 150, STRIP_X1 = 1770, STRIP_Y0 = 778, STRIP_Y1 = 996;
    private static final float STRIP_CARD_Y = 822, STRIP_CARD_W = 170, STRIP_CARD_H = 164, STRIP_GAP = 14;
    private static final int STRIP_SLOTS = 8;
    private static final float STRIP_ARROW_Y0 = 870, STRIP_ARROW_Y1 = 918,
            STRIP_PREV_X = 160, STRIP_NEXT_X = 1716, ARROW_W = 44;

    // ---- 底部操作栏 ----
    private static final float NAV_Y0 = 1008, BTN_Y0 = 1022, BTN_Y1 = 1068;
    private static final float PRIMARY_X0 = 730, PRIMARY_X1 = 950, CLOSE_X0 = 970, CLOSE_X1 = 1150;

    // ---- 确认弹窗 ----
    private static final float MODAL_X0 = 540, MODAL_X1 = 1380, MODAL_Y0 = 400, MODAL_Y1 = 680;
    private static final float MODAL_TEXT_X = 850, MODAL_BTN_Y0 = 610, MODAL_BTN_Y1 = 660,
            MODAL_OK_X0 = 1090, MODAL_OK_X1 = 1250, MODAL_CANCEL_X0 = 1264, MODAL_CANCEL_X1 = 1360;

    // ---- 转盘 ----
    private static final float REEL_CY = 520, REEL_CARD_W = 300, REEL_CARD_H = 236, REEL_PITCH = 318,
            BAND_Y0 = 384, BAND_Y1 = 656;

    // ---- 展示页 ----
    private static final float REVEAL_X = 960, REVEAL_Y = 478, PEDESTAL_Y = 712;
    private static final float PANEL_X0 = 460, PANEL_X1 = 1460, PANEL_Y0 = 796, PANEL_Y1 = 992;
    private static final float MINI_W = 100, MINI_H = 118, MINI_GAP = 12, MINI_Y = 836;
    private static final int MINI_SLOTS = 8;
    private static final float PANEL_PREV_X = 404, PANEL_NEXT_X = 1472;

    // ---- 配色 ----
    private static final int TEXT_BRIGHT = 0xFFF2F2F2, TEXT_BODY = 0xFFD2D2D2, TEXT_DIM = 0xFFA9B3B1,
            TEXT_GOLD = 0xFFF0D36E, DANGER = 0xFFFF8A8A, TEAL = WarehouseTheme.TEAL;

    // =====================================================================
    // 状态
    // =====================================================================

    private final Screen parent;
    private final String crateId;
    private String selectedKey = "";
    private Map<String, Double> inventory = Map.of();
    private int inventoryVersion = -1;

    private CrateStage stage = CrateStage.idle();
    private CrateNetwork.OpenResultS2C result;
    private List<CrateService.Reward> rewards = List.of();
    private ItemStack resultStack = ItemStack.EMPTY;
    private String activeOpenId;
    private int resultSlot = -1;
    private List<ItemStack> reel = List.of();
    private List<SkinQuality> reelQuality = List.of();
    private List<CrateCatalog.RewardPreview> reelRewards = List.of();
    private List<ItemStack> strip = List.of();
    private List<SkinQuality> stripQuality = List.of();
    private String message = "";
    private long toastAt = -1;
    private boolean failure;
    private boolean modalCancelled = true;
    private int catalogVersion = -1, stripPage, rewardPage;

    private long enteredAt = -1;
    private long departAt = -1;
    private long modalShownAt = -1;
    private boolean leaving;
    private long lastFrame;

    private float unit = 1.0F;
    private float canvasX, canvasY;
    private float mouseX = -1, mouseY = -1;
    private int keyboardFocus = -1;
    private float frameDelta = 16.0F;

    private final List<Hotspot> hotspots = new ArrayList<>();
    private final Map<String, Float> hoverAnim = new HashMap<>();
    private final Map<Integer, Float> stripHover = new HashMap<>();
    private final CrateParticles particles = new CrateParticles();
    private float emitClock;

    // ---- 一次性事件（每轮开箱重置） ----
    private boolean firedHold, firedOpen, firedStop, firedReveal, firedLanding;
    private int lastTickSlot = Integer.MIN_VALUE;
    private long lastTickAt;

    public CrateOpenScreen(Screen parent, String crateId) {
        super(Component.translatable(KEY + "hud.title"));
        this.parent = parent;
        this.crateId = crateId;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override protected void init() {
        long now = now();
        if (enteredAt < 0) enteredAt = now;
        lastFrame = now;
        if (inventoryVersion < 0) {
            // 仓库可能早就把库存拉下来了；这里连同版本号一起接管，
            // 否则 refreshFromState() 会因为版本没变而永远不填 inventory。
            inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
            inventory = CrateClientNetwork.STATE.inventory;
        }
        selectedKey = matchingKeyId();
        CrateClientNetwork.requestInventory();
        if (reel.isEmpty()) buildReel();
        if (strip.isEmpty()) buildStrip();
    }

    /** 背景只在 {@link #render} 里画一次。 */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
    }

    @Override public void tick() {
        super.tick();
        refreshFromState();
        long time = now();
        // 与服务端等待上限一致：超时后停下转盘，允许玩家重试。
        if (stage.active() && !stage.hasResult() && !failure
                && time - stage.openedAt() > CrateStage.SPIN_TIMEOUT_MS) {
            fail("screen.habitrain_lottery.crate.timeout");
        }
        if (departAt >= 0 && !leaving) finishLeaving();
    }

    public void refreshFromState() {
        if (catalogVersion != CrateClientNetwork.STATE.catalogVersion && (!stage.active() || stage.finished(now()) || failure)) {
            catalogVersion = CrateClientNetwork.STATE.catalogVersion;
            buildStrip();
            if (!stage.active() || failure) buildReel();
        }
        if (inventoryVersion == CrateClientNetwork.STATE.inventoryVersion) return;
        inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
        inventory = CrateClientNetwork.STATE.inventory;
        if (!stage.active()) message = "";
    }

    public void receive(CrateNetwork.OpenResultS2C payload) {
        if (!crateId.equals(payload.crateId()) || !payload.openId().equals(activeOpenId)) return;
        if (!payload.success()) {
            fail(payload.message());
            return;
        }
        long now = now();
        if (!stage.active()) stage = CrateStage.opened(now - 1L);
        result = payload;
        rewardPage = 0;
        if (parent instanceof WarehouseScreen warehouse) warehouse.expectInventoryRevision(payload.inventoryRevision());
        try {
            CrateService.Reward[] parsed = new Gson().fromJson(payload.rewardsJson(), CrateService.Reward[].class);
            rewards = parsed == null ? List.of() : List.of(parsed);
        } catch (RuntimeException error) { rewards = List.of(); }
        stage = stage.result(now);
        resultStack = previewReward(payload.skinType(), payload.skin());
        SkinQuality quality = SkinQuality.fromId(payload.quality());
        // 结果固定落在「转盘停稳」那一刻光标正中的槽位。
        resultSlot = stage.cursorSlot(stage.stopAt());
        if (!reel.isEmpty()) {
            int index = Math.floorMod(resultSlot, reel.size());
            List<ItemStack> next = new ArrayList<>(reel);
            List<SkinQuality> nextQuality = new ArrayList<>(reelQuality);
            next.set(index, resultStack);
            nextQuality.set(index, quality);
            reel = List.copyOf(next);
            reelQuality = List.copyOf(nextQuality);
        }
    }

    private void fail(String key) {
        failure = true;
        message = key;
        toastAt = now();
        stage = stage.active() ? stage.failure() : CrateStage.idle();
        if (!stage.active()) result = null;
    }

    private void finishLeaving() {
        if (leaving) return;
        leaving = true;
        if (minecraft != null) {
            Screen target = parent;
            minecraft.execute(() -> minecraft.setScreen(target));
        }
    }

    private boolean locked(long time) {
        CrateStage.Phase phase = stage.phase(time);
        return !failure && (phase == CrateStage.Phase.DISMISS || phase == CrateStage.Phase.HOLD
                || phase == CrateStage.Phase.CAROUSEL || phase == CrateStage.Phase.BRIDGE);
    }

    @Override public void onClose() {
        if (departAt >= 0) return;
        // 开盖与锁定过程中不允许半途离开；物品已经登场后就可以直接返回仓库。
        if (locked(now())) {
            message = KEY + "busy";
            toastAt = now();
            return;
        }
        depart();
    }

    private void depart() {
        if (departAt >= 0) return;
        departAt = now();
        message = "";
    }

    @Override public boolean isPauseScreen() { return false; }

    // =====================================================================
    // 数据
    // =====================================================================

    private static long now() { return Util.getMillis(); }

    private int count(String id) { return (int) Math.round(inventory.getOrDefault(id, 0D)); }

    private String matchingKeyId() { return CrateService.keyItemId(crateId); }

    private int crateCount() { return count(CrateService.crateItemId(crateId)); }

    private int keyCount() { return count(matchingKeyId()); }

    private int accent() {
        CrateService.Definition definition = CrateService.definition(crateId);
        return definition == null ? 0xFF57C6D6 : 0xFF000000 | definition.color();
    }

    /** 箱内光：强调色与暖白混合，开箱前不泄露结果品质。 */
    private int crateLight() {
        return GuiFx.mix(CrateArt.WARM_LIGHT, accent(), 0.45F);
    }

    private SkinQuality resultQuality() {
        return result == null ? SkinQuality.WHITE : SkinQuality.fromId(result.quality());
    }

    /** 品质档位 0..1（白 0、红 1），用于按品质放大展示效果。 */
    private float tier() {
        return resultQuality().ordinal() / (float) (SkinQuality.values().length - 1);
    }

    private int rarityColor(SkinQuality quality) {
        return quality.color() | 0xFF000000;
    }

    private String crateName() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return Component.translatable(entry == null ? KEY + crateId : entry.nameKey()).getString();
    }

    private String keyName() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return Component.translatable(entry == null ? KEY + "key." + crateId : entry.keyName()).getString();
    }

    private ItemStack iconStack(String id, net.minecraft.world.item.Item fallback) {
        try {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != Items.AIR) return new ItemStack(item);
        } catch (RuntimeException ignored) { }
        return new ItemStack(fallback);
    }

    private ItemStack crateIcon() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return iconStack(entry == null ? "minecraft:chest" : entry.icon(), Items.CHEST);
    }

    private ItemStack keyIcon() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return iconStack(entry == null ? "minecraft:tripwire_hook" : entry.keyIcon(), Items.TRIPWIRE_HOOK);
    }

    private void drawCrate(GuiGraphics g, float x, float baseY, float width, float lid,
                           int ambient, float glow, float alpha) {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        CrateCatalog.Entry builtin = CrateCatalog.builtin(crateId);
        boolean styled = entry != null && (builtin == null || entry.color() != builtin.color()
                || !crateId.equals(entry.appearancePreset()) || !"star".equals(entry.badge()));
        if (styled) CrateArt.crateStyledLit(g, x, baseY, width, lid, accent(), entry.appearancePreset(), entry.badge(),
                ambient, glow, crateLight(), alpha);
        else CrateArt.crateLit(g, x, baseY, width, lid, ambient, glow, crateLight(), alpha);
    }

    private static ItemStack previewReward(String type, String id) {
        if ("green_apples".equals(type)) {
            ItemStack stack = new ItemStack(Items.APPLE);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.translatable("screen.habitrain_lottery.warehouse.green_apples"));
            return stack;
        }
        if ("card".equals(type)) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.translatable("screen.habitrain_lottery.config.cards." + id));
            return stack;
        }
        return preview(type, id);
    }

    private static ItemStack preview(String type, String id) {
        ItemStack stack = SkinItems.preview(type + "/" + id);
        if (!stack.isEmpty()) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                SkinWardrobeScreen.skinName(type, id));
        return stack.isEmpty() ? new ItemStack(Items.NETHER_STAR) : stack;
    }

    private List<CrateCatalog.RewardPreview> candidates() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return entry == null ? List.of() : entry.rewards();
    }

    private ItemStack previewCandidate(CrateCatalog.RewardPreview reward) {
        ItemStack stack;
        if ("skin".equals(reward.kind())) {
            String[] parts = reward.id().split("/", 2);
            stack = parts.length == 2 ? preview(parts[0], parts[1]) : new ItemStack(Items.BARRIER);
        } else stack = previewReward(reward.kind(), reward.id());
        if (reward.amount() > 1) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(stack.getHoverName().getString() + " ×" + reward.amount()));
        return stack;
    }

    /** 奖励的品质：主结果用服务端品质，其余按公开奖池查找。 */
    private SkinQuality qualityOf(CrateService.Reward reward) {
        if (result != null && "skin".equals(reward.kind())
                && reward.id().equals(result.skinType() + "/" + result.skin())) return resultQuality();
        for (var candidate : candidates()) {
            if (candidate.kind().equals(reward.kind()) && candidate.id().equals(reward.id()))
                return SkinQuality.fromId(candidate.quality());
        }
        return SkinQuality.WHITE;
    }

    /** Decorative order only; every card must belong to the server's published candidates. */
    private void buildReel() {
        List<ItemStack> out = new ArrayList<>();
        List<SkinQuality> quality = new ArrayList<>();
        reelRewards = CrateReelSequence.sample(candidates(), CrateStage.SLOTS, ThreadLocalRandom.current());
        for (var reward : reelRewards) {
            out.add(previewCandidate(reward));
            quality.add(SkinQuality.fromId(reward.quality()));
        }
        reel = List.copyOf(out);
        reelQuality = List.copyOf(quality);
    }

    private void buildStrip() {
        strip = candidates().stream().map(this::previewCandidate).toList();
        stripQuality = candidates().stream().map(r -> SkinQuality.fromId(r.quality())).toList();
        stripPage = Math.min(stripPage, Math.max(0, (strip.size() - 1) / STRIP_SLOTS));
    }

    private void drawReward(GuiGraphics g, CrateCatalog.RewardPreview reward, ItemStack stack,
                            float x, float y, float scale, float yaw, float roll, float tint, float alpha) {
        if (reward == null || "skin".equals(reward.kind())) {
            CrateArt.item(g, stack, x, y, scale, yaw, roll, tint, alpha);
            return;
        }
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "textures/gui/"
                + ("green_apples".equals(reward.kind()) ? "green_apple" : "cards/" + reward.id()) + ".png");
        g.pose().pushPose();
        g.pose().translate(x, y, 150);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
        g.pose().scale(scale, scale, 1);
        g.setColor(tint, tint, tint, alpha);
        g.blit(texture, -8, -8, 0, 0, 16, 16, 16, 16);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    private CrateCatalog.RewardPreview resultPreview() {
        if (result == null) return null;
        String kind = result.skinType();
        return new CrateCatalog.RewardPreview("green_apples".equals(kind) || "card".equals(kind) ? kind : "skin",
                result.skin(), 1, result.quality());
    }

    private boolean readyToOpen() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return entry != null && entry.enabled() && !entry.archived() && !candidates().isEmpty()
                && crateCount() > 0 && keyCount() > 0;
    }

    private ItemStack stackAt(int index) {
        return index < 0 || index >= reel.size() ? ItemStack.EMPTY : reel.get(index);
    }

    private SkinQuality qualityAt(int index) {
        return index < 0 || index >= reelQuality.size() ? SkinQuality.WHITE : reelQuality.get(index);
    }

    private static void sound(SoundEvent event, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    // =====================================================================
    // 渲染：驱动
    // =====================================================================

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now();
        frameDelta = Math.min(80.0F, Math.max(1.0F, time - lastFrame));
        lastFrame = time;
        g.flush();
        // A hard screen cut must not inherit the previous drawer's depth buffer.
        com.mojang.blaze3d.systems.RenderSystem.clear(256, Minecraft.ON_OSX);
        g.fill(0, 0, width, height, 0xFF0B0D0C);
        unit = Math.min(width / REF_W, height / REF_H);
        canvasX = (width - REF_W * unit) * .5F;
        canvasY = (height - REF_H * unit) * .5F;
        mouseX = (mx - canvasX) / unit;
        mouseY = (my - canvasY) / unit;

        driveEvents(time);
        particles.tick(frameDelta);

        float[] shake = shake(time);
        g.enableScissor((int) canvasX, (int) canvasY, (int) Math.ceil(canvasX + REF_W * unit),
                (int) Math.ceil(canvasY + REF_H * unit));
        g.pose().pushPose();
        g.pose().translate(canvasX, canvasY, 0);
        g.pose().scale(unit, unit, 1.0F);
        g.pose().pushPose();
        g.pose().translate(shake[0], shake[1], 0);
        renderWorld(g, time);
        g.pose().popPose();
        renderInterface(g, time);
        g.pose().popPose();
        g.disableScissor();

        int hovered = hoveredStripCard(time);
        if (hovered >= 0) {
            g.renderTooltip(font, List.of(strip.get(hovered).getHoverName().getVisualOrderText(),
                    Component.translatable(KEY + "preview_note").getVisualOrderText()), mx, my);
        }
        if (departAt >= 0) finishLeaving();
    }

    /** 画面里会动的部分（跟着震屏一起晃）。 */
    private void renderWorld(GuiGraphics g, long time) {
        boolean revealEnv = stage.cardsGone(time) && !failure;
        float reveal = CrateStage.clamp01(stage.revealing(time));

        if (revealEnv) renderRevealStage(g, time, reveal);
        else renderCourtyard(g, time);

        layer(g, Z_PARTICLES_BACK, () -> particles.render(g, 1, false));

        if (!revealEnv) layer(g, Z_SCENE, () -> renderCrate(g, time));
        else layer(g, Z_SCENE, () -> renderRevealBack(g, time, reveal));

        layer(g, Z_STRIP, () -> renderStrip(g, time));

        boolean cardsVisible = stage.openedAt() >= 0 && !stage.cardsGone(time) && !failure
                && time >= stage.spinAt();
        if (cardsVisible) renderReel(g, time);

        float bridge = stage.bridge(time);
        if (bridge > 0.01F) layer(g, Z_BRIDGE, () ->
                CrateArt.bridge(g, (int) REF_W, (int) REF_H, ScreenSwap.BRIDGE_COLOR, 0.94F * bridge));

        if (revealEnv && reveal > 0) layer(g, Z_REVEAL_ITEM, () -> renderRevealItem(g, time, reveal));
        layer(g, Z_PARTICLES_FRONT, () -> {
            particles.render(g, 1, true);
            renderFlashes(g, time);
        });
    }

    /** 不跟着震屏的界面件：弹窗、标题、操作栏与按钮。 */
    private void renderInterface(GuiGraphics g, long time) {
        layer(g, Z_MODAL, () -> renderModal(g, time));
        layer(g, Z_UI, () -> renderChrome(g, time));
        layer(g, Z_HOTSPOT, () -> {
            buildHotspots(time);
            for (Hotspot hotspot : hotspots) hotspot.render(g, time);
        });
    }

    private static void layer(GuiGraphics g, float z, Runnable body) {
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, z);
        body.run();
        g.flush();
        g.pose().popPose();
    }

    // ---------------------------------------------------------------------
    // 时间轴上的一次性事件：声音与粒子爆发
    // ---------------------------------------------------------------------

    private void driveEvents(long time) {
        emitClock += frameDelta;
        boolean emit = emitClock >= 33;
        if (emit) emitClock = 0;
        CrateStage.Phase phase = stage.phase(time);
        float cx = REF_W * .5F;
        int light = crateLight();

        // 落地：扶正结束时一圈尘土与一声闷响
        if (!firedLanding && time >= enteredAt + CrateStage.DROP_DELAY_MS + CrateStage.DROP_MS) {
            firedLanding = true;
            particles.burst(cx, 720, 26, 60, 220, 40, 0xFFD8C6A4, false);
            sound(SoundEvents.ARMOR_EQUIP_NETHERITE.value(), 0.7F, 0.5F);
        }
        if (emit && !stage.cardsGone(time)) {
            // 光里的浮尘
            particles.motes(560, 160, 1360, 760, 1, 0xFFFFE9C4);
        }
        if (failure) return;

        if (stage.active() && !firedHold && time >= stage.holdAt()) {
            firedHold = true;
            sound(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.75F, 0.45F);
        }
        if (phase == CrateStage.Phase.HOLD && emit) {
            float p = stage.hold(time);
            int n = 1 + (int) (p * 4);
            particles.charge(cx, 560, 520, n, light);
        }
        if (stage.active() && !firedOpen && time >= stage.spinAt()) {
            firedOpen = true;
            particles.burst(cx, 690, 90, 280, 920, 260, light, true);
            particles.stars(cx, 600, 360, 140, 10, light, true);
            sound(SoundEvents.VAULT_OPEN_SHUTTER, 1.0F, 0.9F);
            sound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.8F, 0.35F);
        }
        if (phase == CrateStage.Phase.CAROUSEL && emit) {
            particles.embers(cx - 240, cx + 240, 700, 1, light);
        }
        // 转盘经过光标的滴答声：越慢音调越高
        if (phase == CrateStage.Phase.CAROUSEL && time > stage.spinAt() + 120) {
            int slot = Math.round(stage.reelPosition(time));
            if (slot != lastTickSlot && time - lastTickAt >= 38) {
                if (lastTickSlot != Integer.MIN_VALUE) {
                    float settle = stage.settling(time);
                    sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.55F + 0.45F * settle, 0.16F);
                }
                lastTickSlot = slot;
                lastTickAt = time;
            }
        }
        if (stage.hasResult() && !firedStop && time >= stage.stopAt()) {
            firedStop = true;
            int rarity = rarityColor(resultQuality());
            particles.burst(cx, REEL_CY, 30 + (int) (70 * tier()), 200, 700, 120, rarity, true);
            particles.stars(cx, REEL_CY, 200, 130, 6 + (int) (10 * tier()), rarity, true);
            sound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F + 0.5F * tier(), 0.55F);
        }
        if (stage.hasResult() && !firedReveal && time >= stage.revealAt()) {
            firedReveal = true;
            int rarity = rarityColor(resultQuality());
            float t = tier();
            particles.burst(REVEAL_X, REVEAL_Y, 60 + (int) (140 * t), 260, 1100, 200, rarity, true);
            particles.stars(REVEAL_X, REVEAL_Y, 420, 260, 10 + (int) (22 * t), rarity, true);
            if (resultQuality().ordinal() >= SkinQuality.GOLD.ordinal()) {
                particles.confetti(200, 1720, 0, 90, new int[]{rarity, 0xFFFFFFFF, CrateArt.GOLD_LINE,
                        GuiFx.shade(rarity, 0.3F), 0xFF57D5C8});
            }
            switch (resultQuality()) {
                case WHITE -> sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9F, 0.6F);
                case BLUE -> sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.1F, 1.0F);
                case PURPLE -> {
                    sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.0F);
                    sound(SoundEvents.BEACON_POWER_SELECT, 1.2F, 0.6F);
                }
                case GOLD -> {
                    sound(SoundEvents.PLAYER_LEVELUP, 1.0F, 0.7F);
                    sound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0F, 0.6F);
                }
                case RED -> {
                    sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.7F);
                    sound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.9F, 0.7F);
                }
            }
        }
        if (stage.cardsGone(time) && emit) {
            int rarity = rarityColor(resultQuality());
            float t = tier();
            particles.embers(REVEAL_X - 260, REVEAL_X + 260, PEDESTAL_Y, t > 0.4F ? 2 : 1, rarity);
            if (particles.random().nextFloat() < 0.15F + 0.35F * t)
                particles.stars(REVEAL_X, REVEAL_Y, 300, 220, 1, rarity, true);
        }
    }

    /** 震屏：蓄力时越来越强，开盖时猛震，金 / 红登场时再震一次。 */
    private float[] shake(long time) {
        if (failure || !stage.active()) return new float[]{0, 0};
        float amp = 0;
        if (stage.phase(time) == CrateStage.Phase.HOLD) {
            float p = stage.hold(time);
            amp = 4.0F * p * p;
        }
        long sinceOpen = time - stage.spinAt();
        if (sinceOpen >= 0 && sinceOpen < 380) amp = Math.max(amp, 16 * (1 - sinceOpen / 380F));
        if (stage.hasResult()) {
            long sinceReveal = time - stage.revealAt();
            float strength = Math.max(0, tier() - 0.5F) * 2;
            if (sinceReveal >= 0 && sinceReveal < 450) amp = Math.max(amp, 12 * strength * (1 - sinceReveal / 450F));
        }
        if (amp <= 0.05F) return new float[]{0, 0};
        return new float[]{(float) (Math.sin(time * 0.091) + Math.sin(time * 0.057)) * 0.5F * amp,
                (float) (Math.sin(time * 0.073 + 1.3) + Math.sin(time * 0.049)) * 0.5F * amp};
    }

    // ---------------------------------------------------------------------
    // 庭院背景：调色、聚光、光柱与暗角
    // ---------------------------------------------------------------------

    private void renderCourtyard(GuiGraphics g, long time) {
        float focus = ScreenSwap.arrive(time, enteredAt);
        float hold = failure ? 0 : stage.hold(time);
        boolean spinning = !failure && stage.active() && time >= stage.spinAt();
        float dolly = CrateStage.easeInOutCubic(CrateStage.progress(time, enteredAt + 1000, 1966));
        CrateArt.backdrop(g, (int) REF_W, (int) REF_H, focus, dolly, 0, spinning ? 0.55F : hold * 0.35F);

        // 电影调色：上下压暗，中部保留
        CrateFx.vGradient(g, 0, 0, REF_W, 300, 0xB0050606, 0x00050606, false);
        CrateFx.vGradient(g, 0, 640, REF_W, REF_H, 0x00050606, 0xD0050606, false);
        CrateFx.hGradient(g, 0, 0, 420, REF_H, 0x90050606, 0x00050606, false);
        CrateFx.hGradient(g, REF_W - 420, 0, REF_W, REF_H, 0x00050606, 0x90050606, false);

        // 顶部聚光锥与斜射光柱
        float sway = (float) Math.sin(time / 2600.0) * 18;
        CrateFx.spotlight(g, 960, -60, 780, 110, 560, 0xFFFFE2B0, 0.16F + 0.10F * hold);
        CrateFx.skewBand(g, 700 + sway, -40, 820, 70, 260, 0x16FFE7C0);
        CrateFx.skewBand(g, 900 - sway * 0.6F, -40, 820, 44, 260, 0x10FFE7C0);
        CrateFx.skewBand(g, 1180 + sway * 0.4F, -40, 820, 90, 260, 0x0CFFE7C0);

        // 蓄力：暗角收拢、画面整体压暗，只留箱子周围
        float dim = Math.max(hold * 0.55F, spinning ? 0.62F : 0);
        CrateFx.vignette(g, REF_W, REF_H, 960, 560, 520 - 160 * dim, 360 - 90 * dim, 1180, 760,
                0xFF020303, 0.55F + 0.35F * dim);
        if (dim > 0.01F) g.fill(0, 0, (int) REF_W, (int) REF_H, GuiFx.fade(0xFF020303, dim * 0.55F));
    }

    // ---------------------------------------------------------------------
    // 箱子、展台与开盖光柱
    // ---------------------------------------------------------------------

    private void renderCrate(GuiGraphics g, long time) {
        float cx = REF_W * .5F;
        float dollyScale = CrateStage.idle().crateScale(time, enteredAt);
        float hold = failure ? 0 : stage.hold(time);
        float push = CrateStage.easeInOutCubic(hold);
        float back = failure || !stage.active() ? 0
                : CrateStage.easeInOutCubic(CrateStage.progress(time, stage.spinAt(), CrateStage.BACKDROP_MS));
        float width = 330 * CrateStage.lerp(dollyScale * (1 + 0.07F * push), 1.6F, back);
        float baseY = CrateStage.lerp(560 + (width - 330) * .62F + 12 * push, 1010, back);

        float drop = -REF_H * 1.15F * (1.0F - CrateStage.dropProgress(time, enteredAt));
        float roll = CrateStage.entryRoll(time, enteredAt);
        float yaw = CrateStage.entryYaw(time, enteredAt);
        // 蓄力震颤
        float tremble = hold * hold;
        roll += (float) Math.sin(time * 0.083) * 1.6F * tremble;
        float jitterX = (float) Math.sin(time * 0.121) * 3.5F * tremble;

        float lid = failure ? 0 : stage.lidAngle(time);
        float open = CrateStage.clamp01(lid / 100.0F);
        long sinceOpen = stage.active() && !failure ? time - stage.spinAt() : -1;
        float burst = sinceOpen >= 0 && sinceOpen < 500 ? 1 - sinceOpen / 500F : 0;
        float glow = Math.max(hold * 0.9F, open > 0 ? 0.72F + 0.28F * burst : 0);
        int ambient = GuiFx.mix(0xFFFFFFFF, 0xFF6A6A6A, Math.max(hold * 0.35F, back * 0.55F));
        int light = crateLight();
        boolean landed = drop > -1;

        // ---- 展台：接触阴影、暖色光池、旋转符文环 ----
        if (landed) {
            float settle = CrateStage.righting(time, enteredAt);
            float ringR = width * 0.72F, ringRy = width * 0.10F;
            float groundY = baseY + width * 0.02F;
            CrateFx.glow(g, cx, groundY, width * 0.62F, width * 0.10F, 0xFF000000, 0.75F * settle, false);
            CrateFx.glow(g, cx, groundY, width * 0.95F, width * 0.20F, light, (0.20F + 0.45F * glow) * settle, true);
            float spin = time / 1000.0F * (0.35F + 2.4F * hold * hold);
            CrateFx.dashRing(g, cx, groundY, ringR, ringRy, 36, 0.55F, spin, 3.2F, light, (0.30F + 0.6F * glow) * settle);
            CrateFx.dashRing(g, cx, groundY, ringR * 0.8F, ringRy * 0.8F, 14, 0.3F, -spin * 1.6F, 2.4F, TEAL,
                    (0.18F + 0.4F * glow) * settle);
            CrateFx.ring(g, cx, groundY, ringR * 1.05F, 6, 0.14F, light, (0.12F + 0.35F * glow) * settle);
        }

        g.pose().pushPose();
        g.pose().translate(cx + jitterX, baseY + drop, 0.0F);
        if (Math.abs(roll) > 0.01F) g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
        if (Math.abs(yaw) > 0.01F) g.pose().scale(Math.max(0.35F, (float) Math.cos(Math.toRadians(yaw))), 1.0F, 1.0F);
        g.pose().translate(-cx, -baseY, 0.0F);
        drawCrate(g, cx, baseY, width, lid, ambient, glow, 1);
        // 缓慢掠过箱面的高光，让静止的箱子也在「呼吸」
        if (!stage.active() && landed) {
            CrateArt.Iso iso = new CrateArt.Iso(cx, baseY, width);
            float sweep = ((time - enteredAt) % 4200L) / 4200.0F;
            float x = iso.px(0, 0, 0) + (iso.px(1, 0, 0) - iso.px(0, 0, 0) + width * 0.4F) * sweep - width * 0.2F;
            g.enableScissor((int) (canvasX + iso.px(0, 1, 0) * unit), (int) (canvasY + iso.py(0, 1, 0) * unit),
                    (int) (canvasX + iso.px(1, 0, 0) * unit), (int) (canvasY + iso.py(1, 0, 0) * unit));
            CrateFx.skewBand(g, x, iso.py(0, 1, 0), iso.py(0, 0, 0) + width * 0.06F, width * 0.05F, width * 0.12F, 0x1CFFFFFF);
            g.disableScissor();
        }
        g.pose().popPose();

        // ---- 开盖光：箱口辉光与冲天光柱 ----
        if (open > 0.01F) {
            CrateArt.Iso iso = new CrateArt.Iso(cx, baseY, width);
            float mouthY = iso.py(.5F, 1, .5F);
            CrateFx.glow(g, cx, mouthY, width * 0.55F, width * 0.13F, light, 0.85F * open, true);
            CrateFx.glow(g, cx, mouthY, width * 0.28F, width * 0.07F, 0xFFFFFFFF, 0.55F * open, true);
            CrateFx.beam(g, cx, -40, mouthY, width * 0.62F, width * 0.30F, light, (0.30F + 0.5F * burst) * open);
            CrateFx.beam(g, cx, -40, mouthY, width * 0.20F, width * 0.10F, 0xFFFFFFFF, (0.18F + 0.5F * burst) * open);
        }
    }

    // ---------------------------------------------------------------------
    // 奖励橱窗
    // ---------------------------------------------------------------------

    private float stripAlpha(long time) {
        float in = CrateStage.stripIn(time, enteredAt);
        if (failure) return in;
        return in * (1 - stage.dismiss(time));
    }

    private int stripCount() {
        return Math.max(0, Math.min(STRIP_SLOTS, strip.size() - stripPage * STRIP_SLOTS));
    }

    private float stripLeft(int count) {
        return (REF_W - (count * STRIP_CARD_W + Math.max(0, count - 1) * STRIP_GAP)) / 2;
    }

    private int hoveredStripCard(long time) {
        if (stage.active() && !failure || !modalCancelled || stripAlpha(time) < 0.5F) return -1;
        int count = stripCount();
        float left = stripLeft(count);
        for (int i = 0; i < count; i++) {
            float x = left + i * (STRIP_CARD_W + STRIP_GAP);
            if (mouseX >= x && mouseX < x + STRIP_CARD_W && mouseY >= STRIP_CARD_Y && mouseY < STRIP_CARD_Y + STRIP_CARD_H)
                return stripPage * STRIP_SLOTS + i;
        }
        return -1;
    }

    private void renderStrip(GuiGraphics g, long time) {
        float alpha = stripAlpha(time);
        if (alpha <= .02F) return;
        float rise = CrateStage.stripOffset(time, enteredAt) + 30 * (failure ? 0 : stage.dismiss(time));
        CrateArt.stripPanel(g, (int) STRIP_X0, (int) (STRIP_Y0 + rise), (int) STRIP_X1, (int) (STRIP_Y1 + rise), alpha);
        float headY = STRIP_Y0 + 12 + rise;
        textSpaced(g, Component.translatable(KEY + "strip.header"), REF_W / 2, headY, 20, 1.2F,
                GuiFx.fade(TEXT_BRIGHT, alpha), true);
        CrateFx.hairline(g, REF_W / 2 - 330, REF_W / 2 - 150, headY + 10, 1, 60, GuiFx.fade(CrateArt.GOLD_LINE, 0.7F * alpha), false);
        CrateFx.hairline(g, REF_W / 2 + 150, REF_W / 2 + 330, headY + 10, 1, 60, GuiFx.fade(CrateArt.GOLD_LINE, 0.7F * alpha), false);
        CrateFx.diamond(g, REF_W / 2 - 140, headY + 10.5F, 4, 4, GuiFx.fade(CrateArt.GOLD_LINE, alpha), false);
        CrateFx.diamond(g, REF_W / 2 + 140, headY + 10.5F, 4, 4, GuiFx.fade(CrateArt.GOLD_LINE, alpha), false);
        int pages = Math.max(1, (strip.size() + STRIP_SLOTS - 1) / STRIP_SLOTS);
        textRight(g, Component.translatable(KEY + "strip.page", strip.size(), stripPage + 1, pages),
                STRIP_X1 - 24, headY + 2, 16, GuiFx.fade(TEXT_DIM, alpha), false);
        renderRarityLegend(g, STRIP_X0 + 24, headY + 4, alpha);
        if (strip.isEmpty()) {
            textCentered(g, Component.translatable(KEY + "strip.empty"), REF_W / 2, 890 + rise,
                    22, GuiFx.fade(TEXT_DIM, alpha), true);
            return;
        }
        int count = stripCount();
        float left = stripLeft(count);
        int hovered = hoveredStripCard(time);
        for (int i = 0; i < count; i++) {
            int index = stripPage * STRIP_SLOTS + i;
            // 逐张错峰入场
            float enter = CrateStage.easeOutCubic(CrateStage.progress(time,
                    enteredAt + CrateStage.STRIP_DELAY_MS + 60L * i, 420));
            float a = alpha * enter;
            if (a <= 0.02F) continue;
            float target = index == hovered ? 1 : 0;
            float h = GuiFx.approach(stripHover.getOrDefault(index, 0F), target, frameDelta, 60);
            stripHover.put(index, h);
            float x = left + i * (STRIP_CARD_W + STRIP_GAP);
            float y = STRIP_CARD_Y + rise + 24 * (1 - enter) - 8 * h;
            int rarity = rarityColor(stripQuality.get(index));
            CrateArt.rarityCard(g, x, y, STRIP_CARD_W, STRIP_CARD_H, rarity, 1, 0.15F + 0.85F * h, a);
            if (h > 0.02F) CrateArt.sheen(g, x, y, STRIP_CARD_W, STRIP_CARD_H,
                    ((time % 1400L) / 1400.0F), h * a);
            var reward = candidates().get(index);
            drawReward(g, reward, strip.get(index), x + STRIP_CARD_W / 2, y + 56, 5.2F + 0.5F * h, 0, 0, 1, a);
            textClipped(g, strip.get(index).getHoverName(), x + 10, y + 110, 18, STRIP_CARD_W - 20,
                    GuiFx.fade(TEXT_BRIGHT, a), true);
            Component detail = "skin".equals(reward.kind())
                    ? Component.translatable(stripQuality.get(index).translationKey())
                    : Component.translatable(KEY + "strip." + reward.kind());
            textClipped(g, detail, x + 10, y + 134, 15, STRIP_CARD_W - 20,
                    GuiFx.fade(GuiFx.mix(TEXT_DIM, rarity, 0.55F), a), false);
        }
    }

    /** 橱窗左上：各品质数量的小色点图例。 */
    private void renderRarityLegend(GuiGraphics g, float x, float y, float alpha) {
        int[] counts = new int[SkinQuality.values().length];
        for (SkinQuality quality : stripQuality) counts[quality.ordinal()]++;
        for (SkinQuality quality : SkinQuality.values()) {
            if (counts[quality.ordinal()] == 0) continue;
            int color = rarityColor(quality);
            CrateFx.diamond(g, x + 5, y + 7, 5, 5, GuiFx.fade(color, alpha), false);
            CrateFx.glow(g, x + 5, y + 7, 12, color, 0.35F * alpha);
            Component label = Component.literal(Component.translatable(quality.translationKey()).getString()
                    + " " + counts[quality.ordinal()]);
            textLeft(g, label, x + 16, y, 15, GuiFx.fade(TEXT_BODY, alpha), false);
            x += 30 + textWidth(label, 15);
        }
    }

    // ---------------------------------------------------------------------
    // 确认弹窗
    // ---------------------------------------------------------------------

    private float modalAlpha(long time) {
        if (modalCancelled && !stage.active()) return 0;
        if (failure) return 0;
        float in = modalShownAt < 0 ? 0 : CrateStage.easeOutCubic(CrateStage.progress(time, modalShownAt, 260));
        return in * (1.0F - CrateStage.easeOutCubic(stage.dismiss(time)));
    }

    private void renderModal(GuiGraphics g, long time) {
        float alpha = modalAlpha(time);
        if (alpha <= 0.02F) return;
        g.fill(0, 0, (int) REF_W, (int) REF_H, GuiFx.fade(0x99020304, alpha));
        float pop = 0.94F + 0.06F * alpha;
        g.pose().pushPose();
        g.pose().translate(REF_W / 2, (MODAL_Y0 + MODAL_Y1) / 2, 0);
        g.pose().scale(pop, pop, 1);
        g.pose().translate(-REF_W / 2, -(MODAL_Y0 + MODAL_Y1) / 2, 0);
        int accent = accent();
        CrateFx.rectGlow(g, MODAL_X0, MODAL_Y0, MODAL_X1, MODAL_Y1, 40, accent, 0.18F * alpha);
        CrateArt.glassPanel(g, MODAL_X0, MODAL_Y0, MODAL_X1, MODAL_Y1, 14, GuiFx.mix(accent, CrateArt.GOLD_LINE, 0.3F), alpha);

        // 左侧：展台上的小箱子
        float tx = 690, ty = 612;
        CrateFx.glow(g, tx, ty - 90, 170, 150, crateLight(), 0.30F * alpha, true);
        CrateFx.glow(g, tx, ty + 6, 150, 22, 0xFF000000, 0.7F * alpha, false);
        CrateFx.dashRing(g, tx, ty + 6, 150, 22, 28, 0.55F, time / 1400.0F, 2.5F, crateLight(), 0.5F * alpha);
        drawCrate(g, tx, ty, 220, 0, 0xFFFFFFFF, 0.25F + 0.15F * (float) Math.sin(time / 500.0), alpha);

        float y = MODAL_Y0 + 34;
        textSpaced(g, Component.translatable(KEY + "modal.eyebrow"), MODAL_TEXT_X + 150, y, 15, 2.2F,
                GuiFx.fade(TEXT_GOLD, alpha), false);
        textClipped(g, Component.translatable(KEY + "modal.title", crateName()), MODAL_TEXT_X, y + 26, 30,
                MODAL_X1 - MODAL_TEXT_X - 30, GuiFx.fade(TEXT_BRIGHT, alpha), true);
        CrateFx.hairline(g, MODAL_TEXT_X, MODAL_X1 - 30, y + 70, 1, 120, GuiFx.fade(0x55FFFFFF, alpha), false);
        textClipped(g, Component.translatable(KEY + "modal.body", crateName()), MODAL_TEXT_X, y + 84, 18,
                MODAL_X1 - MODAL_TEXT_X - 30, GuiFx.fade(TEXT_BODY, alpha), false);
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        if (entry != null) {
            Component summary = "unified_pool".equals(entry.rewardMode())
                    ? Component.translatable(KEY + "modal.unified", entry.rollCount(), entry.minimumSkinCount())
                    : Component.translatable(KEY + "modal.fixed", entry.skinDrawCount());
            CrateFx.diamond(g, MODAL_TEXT_X + 5, y + 122, 4, 4, GuiFx.fade(TEXT_GOLD, alpha), false);
            textClipped(g, summary, MODAL_TEXT_X + 16, y + 114, 16, MODAL_X1 - MODAL_TEXT_X - 50,
                    GuiFx.fade(TEXT_GOLD, alpha), false);
            if (!entry.extraKinds().isEmpty()) textClipped(g,
                    Component.translatable(KEY + "modal.extras", String.join(", ", entry.extraKinds().stream().map(kind -> Component.translatable(
                            "green_apples".equals(kind) ? "screen.habitrain_lottery.warehouse.green_apples"
                                    : "screen.habitrain_lottery.config.cards." + kind).getString()).toList())),
                    MODAL_TEXT_X + 16, y + 140, 15, MODAL_X1 - MODAL_TEXT_X - 50, GuiFx.fade(TEXT_DIM, alpha), false);
        }
        // 钥匙消耗
        CrateArt.item(g, keyIcon(), MODAL_TEXT_X + 12, MODAL_BTN_Y0 + 25, 1.6F, 0, 0, 1, alpha);
        textLeft(g, Component.translatable(KEY + "key_use", keyName(), keyCount()), MODAL_TEXT_X + 32,
                MODAL_BTN_Y0 + 16, 17, GuiFx.fade(keyCount() <= 0 ? DANGER : TEXT_BODY, alpha), false);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------------
    // 卡片转盘
    // ---------------------------------------------------------------------

    /** 转盘瞬时速度，单位为「卡片/毫秒」。 */
    private float reelSpeed(long time) {
        return Math.max(0, stage.reelPosition(time) - stage.reelPosition(time - 16)) / 16.0F;
    }

    private void renderReel(GuiGraphics g, long time) {
        float entry = stage.wipe(time);
        if (entry <= 0.02F) return;
        float cx = REF_W * .5F;
        float speed = CrateStage.clamp01(reelSpeed(time) / CrateStage.REEL_SPEED);
        boolean stopped = stage.hasResult() && time >= stage.stopAt();
        float flare = stopped ? CrateStage.progress(time, stage.stopAt(), CrateStage.STOP_HOLD_MS) : 0;
        int winnerColor = rarityColor(resultQuality());
        int light = crateLight();

        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, Z_REEL);
        // ---- 轨道底板：两端淡出的暗色玻璃 + 上下发光导轨 ----
        float half = REF_W * .5F * entry;
        CrateFx.hGradient(g, cx - half, BAND_Y0, cx - half + 420, BAND_Y1, 0x000A0C0D, 0xE00A0C0D, false);
        g.fill((int) (cx - half + 420), (int) BAND_Y0, (int) (cx + half - 420), (int) BAND_Y1, 0xE00A0C0D);
        CrateFx.hGradient(g, cx + half - 420, BAND_Y0, cx + half, BAND_Y1, 0xE00A0C0D, 0x000A0C0D, false);
        CrateFx.vGradient(g, cx - half, BAND_Y0, cx + half, BAND_Y0 + 60, GuiFx.fade(light, 0.10F), light & 0xFFFFFF, true);
        for (float railY : new float[]{BAND_Y0, BAND_Y1 - 2}) {
            CrateFx.hairline(g, cx - half, cx + half, railY, 2, 520, GuiFx.fade(light, 0.85F), false);
            CrateFx.hairline(g, cx - half, cx + half, railY - 5, 12, 520, GuiFx.fade(light, 0.28F), true);
        }
        // 高速时的横向流光
        if (speed > 0.05F) {
            for (int i = 0; i < 16; i++) {
                float ry = BAND_Y0 + 14 + ((i * 97) % 23) / 23.0F * (BAND_Y1 - BAND_Y0 - 28);
                float len = 120 + (i * 53) % 220;
                float travel = REF_W + len * 2;
                float x = REF_W + len - ((time * (1.4F + (i % 5) * 0.3F) * speed + i * 211) % travel);
                CrateFx.hairline(g, x, x + len, ry, 1.5F, len * 0.45F, GuiFx.fade(0xFFFFFFFF, 0.22F * speed), true);
            }
        }

        g.enableScissor((int) (canvasX + (cx - half) * unit), (int) (canvasY + (BAND_Y0 + 2) * unit),
                (int) (canvasX + (cx + half) * unit), (int) (canvasY + (BAND_Y1 - 2) * unit));
        for (int index = CrateStage.SLOTS - 1; index >= 0; index--) {
            ItemStack stack = stackAt(index);
            if (stack.isEmpty()) continue;
            float offset = stage.reelOffset(time, index);
            float distance = Math.abs(offset);
            if (distance > CrateStage.VISIBLE_SPAN + 0.7F) continue;
            boolean winner = index == resultSlot && stage.hasResult();
            float scale = CrateStage.cardScale(offset);
            if (winner && stopped) scale *= 1 + 0.06F * (float) Math.sin(Math.min(1, flare * 1.6F) * Math.PI) + 0.03F * flare;
            float tint = CrateStage.cardTint(offset);
            if (stopped && !winner) tint *= 1 - 0.45F * CrateStage.easeOutCubic(Math.min(1, flare * 3));
            float w = REEL_CARD_W * scale, h = REEL_CARD_H * scale;
            float x = cx + offset * REEL_PITCH - w * .5F, y = REEL_CY - h * .5F;
            int rarity = rarityColor(qualityAt(index));
            float near = CrateStage.clamp01(1 - distance / 1.2F);
            float glow = 0.12F + 0.4F * near + (winner && stopped ? 0.9F * CrateStage.easeOutCubic(Math.min(1, flare * 2.5F)) : 0);
            CrateArt.rarityCard(g, x, y, w, h, rarity, tint, Math.min(1, glow), entry);
            if (winner && stopped && flare < 0.8F) CrateArt.sheen(g, x, y, w, h, flare / 0.8F, 1);
            CrateCatalog.RewardPreview preview = index == resultSlot ? resultPreview() : reelRewards.get(index);
            float itemScale = Math.min(w, h) * 0.60F / 16.0F;
            // 运动模糊：沿运动方向的淡残影
            if (speed > 0.12F) {
                for (int k = 3; k >= 1; k--) {
                    float ghost = k * 26 * speed;
                    drawReward(g, preview, stack, x + w * .5F + ghost, y + h * .46F, itemScale, 0, 0,
                            tint * 0.7F, entry * (0.28F - k * 0.06F) * speed);
                }
            }
            drawReward(g, preview, stack, x + w * .5F, y + h * .46F, itemScale, 0, 0, tint, entry);
        }
        g.flush();
        g.disableScissor();
        g.pose().popPose();

        // ---- 箱内光柱透过卡片 ----
        layer(g, Z_REEL_LIGHT, () -> {
            CrateFx.beam(g, cx, BAND_Y0, BAND_Y1 + 60, 180, 260, light, 0.16F * entry);
            if (stopped) CrateFx.glow(g, cx, REEL_CY, 360, 190, winnerColor, 0.30F * CrateStage.easeOutCubic(flare), true);
        });

        layer(g, Z_REEL_OVER, () -> {
            // 两端压暗，让视线收在光标附近
            CrateFx.hGradient(g, 0, BAND_Y0 - 30, 560, BAND_Y1 + 30, 0xF0020303, 0x00020303, false);
            CrateFx.hGradient(g, REF_W - 560, BAND_Y0 - 30, REF_W, BAND_Y1 + 30, 0x00020303, 0xF0020303, false);
            renderNeedle(g, time, entry, speed, stopped, flare, winnerColor);
            if (stopped) renderWinnerFlare(g, time, flare, winnerColor);
        });
    }

    /** 中央光标：金色光针 + 上下三角指针；卡片经过时闪一下。 */
    private void renderNeedle(GuiGraphics g, long time, float entry, float speed, boolean stopped, float flare, int winner) {
        float alpha = CrateStage.clamp01((entry - 0.3F) / 0.7F);
        if (alpha <= 0.01F) return;
        float cx = REF_W * .5F;
        float frac = stage.reelPosition(time) % 1.0F;
        float pass = (float) Math.pow(Math.max(0, 1 - Math.min(frac, 1 - frac) * 5), 2) * (0.4F + 0.6F * speed);
        int color = stopped ? GuiFx.mix(CrateArt.GOLD_LINE, winner, 0.6F * flare) : CrateArt.GOLD_LINE;
        float y0 = BAND_Y0 - 14, y1 = BAND_Y1 + 14;
        CrateFx.lineGlow(g, cx, y0, cx, y1, 3, 14 + 10 * pass, color, alpha * (0.75F + 0.25F * pass));
        CrateFx.glow(g, cx, REEL_CY, 60, 170, color, alpha * (0.10F + 0.25F * pass + 0.3F * flare), true);
        for (int dir : new int[]{-1, 1}) {
            float tipY = dir < 0 ? y0 : y1;
            float baseY = tipY + dir * 20;
            CrateFx.triangle(g, cx - 13, baseY, cx + 13, baseY, cx, tipY + dir * 2, GuiFx.fade(color, alpha), false);
            CrateFx.glow(g, cx, tipY + dir * 8, 30, color, alpha * 0.5F);
        }
    }

    /** 转盘停稳：中奖卡按品质绽放（射线、冲击环、拉丝、星芒）。 */
    private void renderWinnerFlare(GuiGraphics g, long time, float flare, int color) {
        float cx = REF_W * .5F, t = tier();
        float in = CrateStage.easeOutCubic(Math.min(1, flare * 2.2F));
        float rot = time / 3200.0F;
        if (t >= 0.5F) CrateFx.rays(g, cx, REEL_CY, 60, 560, 14, rot, 5, 0.62F, color, 0.35F * in * t);
        float ring = CrateStage.easeOutCubic(flare);
        CrateFx.ring(g, cx, REEL_CY, 120 + 520 * ring, 26, 0.55F, color, 0.7F * (1 - ring));
        CrateFx.streak(g, cx, REEL_CY, 520 * in, 10, color, 0.55F * in);
        CrateFx.streak(g, cx, REEL_CY, 260 * in, 4, 0xFFFFFFFF, 0.6F * in);
    }

    // ---------------------------------------------------------------------
    // 展示页
    // ---------------------------------------------------------------------

    /** 展示舞台：压暗的庭院 + 品质色径向光。 */
    private void renderRevealStage(GuiGraphics g, long time, float reveal) {
        CrateArt.backdrop(g, (int) REF_W, (int) REF_H, 1, 1, 1, 1);
        int color = rarityColor(resultQuality());
        g.fill(0, 0, (int) REF_W, (int) REF_H, 0xC8040506);
        CrateFx.glow(g, REVEAL_X, REVEAL_Y, 1100, 700, GuiFx.mix(color, 0xFF000000, 0.35F), 0.55F, true);
        CrateFx.vGradient(g, 0, 0, REF_W, 260, 0xC0020303, 0x00020303, false);
        CrateFx.vGradient(g, 0, 760, REF_W, REF_H, 0x00020303, 0xE0020303, false);
        CrateFx.vignette(g, REF_W, REF_H, REVEAL_X, REVEAL_Y + 40, 620, 400, 1200, 760, 0xFF010202, 0.8F);
    }

    /** 物品身后的光：旋转射线、光环、展台与符文环。 */
    private void renderRevealBack(GuiGraphics g, long time, float reveal) {
        int color = rarityColor(resultQuality());
        float t = tier();
        float in = CrateStage.easeOutCubic(reveal);
        float rot = time / 9000.0F;
        float breathe = 0.85F + 0.15F * (float) Math.sin(time / 700.0);

        CrateFx.rays(g, REVEAL_X, REVEAL_Y, 40, 760, 16, rot, 5, 1, color, (0.06F + 0.14F * t) * in * breathe);
        if (t >= 0.5F) CrateFx.rays(g, REVEAL_X, REVEAL_Y, 40, 560, 10, -rot * 1.7F, 2, 1, 0xFFFFFFFF, 0.07F * t * in);
        CrateFx.glow(g, REVEAL_X, REVEAL_Y, 420, 380, color, (0.22F + 0.18F * t) * in, true);
        CrateFx.ring(g, REVEAL_X, REVEAL_Y, 250 + 8 * (float) Math.sin(time / 600.0), 18, 1, color, (0.14F + 0.22F * t) * in);
        if (t >= 0.75F) CrateFx.ring(g, REVEAL_X, REVEAL_Y, 330, 8, 1, 0xFFFFFFFF, 0.10F * in * breathe);

        // 展台
        CrateFx.glow(g, REVEAL_X, PEDESTAL_Y, 420, 60, 0xFF000000, 0.8F * in, false);
        CrateFx.glow(g, REVEAL_X, PEDESTAL_Y, 470, 70, color, 0.45F * in, true);
        CrateFx.dashRing(g, REVEAL_X, PEDESTAL_Y, 380, 52, 48, 0.55F, time / 1600.0F, 3.4F, color, 0.75F * in);
        CrateFx.dashRing(g, REVEAL_X, PEDESTAL_Y, 300, 41, 18, 0.35F, -time / 1100.0F, 2.6F, 0xFFFFFFFF, 0.35F * in);
        CrateFx.ring(g, REVEAL_X, PEDESTAL_Y, 420, 8, 0.137F, color, 0.5F * in);
        CrateFx.beam(g, REVEAL_X, 160, PEDESTAL_Y, 280, 200, color, 0.12F * in);
        // 标题区压暗，让物品名读得清
        CrateFx.vGradient(g, 0, 0, REF_W, 230, 0xE0030404, 0x00030404, false);
    }

    /** 选中物品：从卡片位置弹出、放大到舞台中央，之后缓慢漂浮。 */
    private void renderRevealItem(GuiGraphics g, long time, float reveal) {
        float morph = CrateStage.easeOutBack(reveal, 0.9F);
        float settled = CrateStage.clamp01((reveal - 0.7F) / 0.3F);
        float idle = (time - stage.revealAt()) / 1000.0F;
        float fromScale = Math.min(REEL_CARD_W, REEL_CARD_H) * 0.60F / 16.0F;
        float toScale = REF_H * 0.33F / 16.0F;
        float size = fromScale + (toScale - fromScale) * morph;
        float px = REVEAL_X;
        float py = CrateStage.lerp(REEL_CY, REVEAL_Y, CrateStage.easeOutCubic(reveal))
                + 7 * (float) Math.sin(idle * 1.3) * settled;
        float yaw = CrateStage.lerp(-CrateStage.REVEAL_YAW, 0, CrateStage.easeOutCubic(reveal))
                + 7 * (float) Math.sin(idle * 0.8) * settled;
        float roll = 2 * (float) Math.sin(idle * 1.1) * settled;
        int color = rarityColor(resultQuality());
        CrateFx.glow(g, px, py, size * 9, size * 8, color, 0.22F + 0.1F * settled, true);
        CrateFx.glow(g, px, py, size * 4, size * 4, 0xFFFFFFFF, 0.18F * (1 - settled) + 0.06F, true);
        drawReward(g, resultPreview(), resultStack, px, py, size, yaw, roll, 1, 1);
        // 漂浮的星芒点缀
        for (int i = 0; i < 4; i++) {
            float phase = (idle * 0.7F + i * 0.25F) % 1;
            float twinkle = (float) Math.sin(phase * Math.PI);
            double angle = i * 1.7 + 0.6;
            CrateFx.sparkle(g, px + (float) Math.cos(angle) * size * 9, py + (float) Math.sin(angle) * size * 7,
                    18 + 10 * twinkle, 0, i % 2 == 0 ? 0xFFFFFFFF : color, twinkle * settled * 0.9F);
        }
    }

    /** 全屏闪光：开盖白闪、登场的品质闪光与冲击环。 */
    private void renderFlashes(GuiGraphics g, long time) {
        if (!stage.active() || failure) return;
        long sinceOpen = time - stage.spinAt();
        if (sinceOpen >= 0 && sinceOpen < 420) {
            float k = 1 - sinceOpen / 420F;
            CrateFx.flash(g, REF_W, REF_H, 0xFFFFF4DE, 0.30F * k * k * k);
            float r = CrateStage.easeOutCubic(sinceOpen / 420F);
            CrateFx.ring(g, REF_W / 2, 640, 80 + 1000 * r, 34, 0.4F, crateLight(), 0.6F * k * k);
            CrateFx.streak(g, REF_W / 2, 660, 900 * (0.4F + 0.6F * r), 14, 0xFFFFFFFF, 0.7F * k);
        }
        if (!stage.hasResult()) return;
        long sinceReveal = time - stage.revealAt();
        if (sinceReveal >= 0 && sinceReveal < 700) {
            int color = rarityColor(resultQuality());
            float k = 1 - sinceReveal / 700F, t = tier();
            CrateFx.flash(g, REF_W, REF_H, GuiFx.mix(0xFFFFFFFF, color, 0.5F), (0.10F + 0.16F * t) * k * k * k * k);
            float r = CrateStage.easeOutCubic(sinceReveal / 700F);
            CrateFx.ring(g, REVEAL_X, REVEAL_Y, 60 + (700 + 400 * t) * r, 30 + 20 * t, 0.8F, color, (0.35F + 0.4F * t) * k * k);
            if (t >= 0.5F) CrateFx.ring(g, REVEAL_X, REVEAL_Y, 40 + 700 * r, 22, 0.8F, 0xFFFFFFFF, 0.6F * k);
            CrateFx.streak(g, REVEAL_X, REVEAL_Y, 1100 * r, 18 * k + 4, color, 0.7F * k);
        }
    }

    // ---------------------------------------------------------------------
    // 标题、结果名牌、奖励面板、操作栏与提示
    // ---------------------------------------------------------------------

    private void renderChrome(GuiGraphics g, long time) {
        float enter = ScreenSwap.arriveFade(ScreenSwap.arrive(time, enteredAt));
        boolean revealed = stage.cardsGone(time) && !failure;
        float plate = revealed ? CrateStage.easeOutCubic(CrateStage.progress(time, stage.revealAt() + 150, 450)) : 0;

        if (!revealed) renderHeader(g, time, enter);
        else {
            renderResultHeader(g, time, plate);
            renderSummary(g, time, CrateStage.easeOutCubic(CrateStage.progress(time, stage.revealAt() + 400, 500)));
        }

        // ---- 底部操作栏 ----
        CrateFx.vGradient(g, 0, NAV_Y0 - 50, REF_W, NAV_Y0, 0x00030404, GuiFx.fade(0xB0030404, enter), false);
        g.fill(0, (int) NAV_Y0, (int) REF_W, (int) REF_H, GuiFx.fade(0xD8030404, enter));
        CrateFx.hairline(g, 0, REF_W, NAV_Y0, 1, 700, GuiFx.fade(0x70FFFFFF, enter), false);
        CrateFx.hairline(g, 560, REF_W - 560, NAV_Y0, 1, 260, GuiFx.fade(TEAL, 0.8F * enter), false);
        boolean idle = !stage.active() || failure;
        if (idle || revealed) {
            CrateArt.item(g, keyIcon(), 214, 1045, 1.7F, 0, 0, 1, enter);
            textLeft(g, Component.translatable(KEY + "key_use", keyName(), keyCount()), 236, 1036, 18,
                    GuiFx.fade(keyCount() <= 0 ? DANGER : TEXT_BODY, enter), false);
            CrateArt.item(g, crateIcon(), REF_W - 214, 1045, 1.7F, 0, 0, 1, enter);
            textRight(g, Component.translatable(KEY + "nav.crates", crateCount()), REF_W - 236, 1036, 18,
                    GuiFx.fade(crateCount() <= 0 ? DANGER : TEXT_BODY, enter), false);
        } else {
            // 开箱中：一条进度提示
            Component status = Component.translatable(KEY + (stage.phase(time) == CrateStage.Phase.CAROUSEL
                    ? (stage.hasResult() ? "status.rolling" : "status.waiting") : "status.charging"));
            float pulse = 0.65F + 0.35F * (float) Math.sin(time / 260.0);
            textSpaced(g, status, REF_W / 2, 1034, 19, 2, GuiFx.fade(TEXT_GOLD, enter * pulse), true);
        }

        // ---- 状态提示：失败、缺少物品、操作被锁 ----
        Component notice = null;
        if (failure && !message.isEmpty() && (stage.failedVisible(time) || !stage.active())) notice = Component.translatable(message);
        else if (!message.isEmpty() && toastAt >= 0 && time - toastAt < 1800) notice = Component.translatable(message);
        else if (!stage.active() && candidates().isEmpty()) notice = Component.translatable(KEY + "strip.empty");
        else if (!stage.active() && CrateCatalog.find(crateId) != null && !CrateCatalog.find(crateId).enabled())
            notice = Component.translatable("crates.disabled");
        else if (!stage.active() && crateCount() <= 0) notice = Component.translatable(KEY + "missing_crate");
        else if (!stage.active() && keyCount() <= 0) notice = Component.translatable(KEY + "missing_key");
        if (notice != null && enter > 0.5F) {
            float w = textWidth(notice, 20) + 64, y0 = 726;
            CrateFx.rectGlow(g, REF_W / 2 - w / 2, y0, REF_W / 2 + w / 2, y0 + 42, 18, DANGER, 0.18F);
            CrateArt.chip(g, REF_W / 2 - w / 2, y0, REF_W / 2 + w / 2, y0 + 42, DANGER, 0.95F);
            CrateFx.diamond(g, REF_W / 2 - w / 2 + 22, y0 + 21, 5, 5, DANGER, false);
            textCentered(g, notice, REF_W / 2 + 8, y0 + 11, 20, DANGER, true);
        }
    }

    /** 开箱前 / 开箱中的标题：眉题、箱子名、库存标签。 */
    private void renderHeader(GuiGraphics g, long time, float enter) {
        float busy = failure ? 0 : CrateStage.easeOutCubic(stage.dismiss(time));
        float a = enter;
        if (a <= 0.02F) return;
        int accent = GuiFx.mix(accent(), CrateArt.GOLD_LINE, 0.4F);
        float cx = REF_W / 2;
        textSpaced(g, Component.translatable(KEY + "hud.title"), cx, HEAD_EYEBROW_Y, 16, 3.5F,
                GuiFx.fade(GuiFx.mix(TEXT_DIM, accent, 0.5F), a), false);
        CrateFx.hairline(g, cx - 250, cx - 60, HEAD_EYEBROW_Y + 8, 1, 90, GuiFx.fade(accent, 0.8F * a), false);
        CrateFx.hairline(g, cx + 60, cx + 250, HEAD_EYEBROW_Y + 8, 1, 90, GuiFx.fade(accent, 0.8F * a), false);
        Component name = Component.literal(crateName());
        float nameW = textWidth(name, HEAD_TITLE_SIZE);
        CrateFx.glow(g, cx, HEAD_TITLE_Y + 22, nameW * 0.7F + 60, 46, accent, 0.20F * a, true);
        textCentered(g, name, cx, HEAD_TITLE_Y, HEAD_TITLE_SIZE, GuiFx.fade(TEXT_BRIGHT, a), true);

        float chipsA = a * (1 - busy);
        if (chipsA <= 0.02F) return;
        Component crates = Component.translatable(KEY + "chip.crates", crateCount());
        Component keys = Component.translatable(KEY + "chip.keys", keyCount());
        float w1 = textWidth(crates, 17) + 62, w2 = textWidth(keys, 17) + 62, gap = 16;
        float x = cx - (w1 + w2 + gap) / 2;
        renderChip(g, x, crateIcon(), crates, w1, crateCount() > 0 ? accent : DANGER, chipsA);
        renderChip(g, x + w1 + gap, keyIcon(), keys, w2, keyCount() > 0 ? TEAL : DANGER, chipsA);
    }

    private void renderChip(GuiGraphics g, float x, ItemStack icon, Component text, float w, int accent, float a) {
        CrateArt.chip(g, x, HEAD_CHIP_Y0, x + w, HEAD_CHIP_Y1, accent, a);
        CrateArt.item(g, icon, x + 22, (HEAD_CHIP_Y0 + HEAD_CHIP_Y1) / 2, 1.45F, 0, 0, 1, a);
        textLeft(g, text, x + 42, HEAD_CHIP_Y0 + 9, 17, GuiFx.fade(TEXT_BRIGHT, a), false);
    }

    /** 展示页标题：品质眉题、物品名、品质标签与品质横条。 */
    private void renderResultHeader(GuiGraphics g, long time, float a) {
        if (a <= 0.02F) return;
        SkinQuality quality = resultQuality();
        int color = rarityColor(quality);
        float cx = REF_W / 2, drop = 14 * (1 - a);
        textSpaced(g, Component.translatable(KEY + "reveal.eyebrow"), cx, 34 - drop, 16, 3.5F,
                GuiFx.fade(GuiFx.mix(0xFFFFFFFF, color, 0.6F), a), false);
        Component name = resultStack.isEmpty() ? Component.translatable(KEY + "skin_result", "") : resultStack.getHoverName();
        float nameW = textWidth(name, 50);
        CrateFx.glow(g, cx, 84 - drop, nameW * 0.7F + 80, 56, color, 0.35F * a, true);
        textCentered(g, name, cx, 58 - drop, 50, GuiFx.fade(TEXT_BRIGHT, a), true);
        Component chip = Component.translatable(KEY + "reveal.collection", Component.translatable(quality.translationKey()).getString());
        Component source = Component.translatable(KEY + "reveal.from", crateName());
        float w1 = textWidth(chip, 17) + 44, w2 = textWidth(source, 17) + 34, gap = 14;
        float x = cx - (w1 + w2 + gap) / 2, y0 = 124 - drop;
        CrateArt.chip(g, x, y0, x + w1, y0 + 32, color, a);
        CrateFx.diamond(g, x + 17, y0 + 16, 5, 5, GuiFx.fade(color, a), false);
        CrateFx.glow(g, x + 17, y0 + 16, 14, color, 0.6F * a);
        textLeft(g, chip, x + 30, y0 + 8, 17, GuiFx.fade(GuiFx.mix(0xFFFFFFFF, color, 0.45F), a), false);
        CrateArt.chip(g, x + w1 + gap, y0, x + w1 + gap + w2, y0 + 32, 0xFF8A9490, a);
        textLeft(g, source, x + w1 + gap + 17, y0 + 8, 17, GuiFx.fade(TEXT_BODY, a), false);
        CrateArt.rarityRule(g, (int) (cx - 420 * a), (int) (176 - drop), (int) (cx + 420 * a), color, a);
    }

    /** 展示页底部：本次获得的全部奖励（迷你卡片，可翻页）与说明。 */
    private void renderSummary(GuiGraphics g, long time, float a) {
        if (a <= 0.02F) return;
        int color = rarityColor(resultQuality());
        float rise = 24 * (1 - a);
        CrateArt.glassPanel(g, PANEL_X0, PANEL_Y0 + rise, PANEL_X1, PANEL_Y1 + rise, 12, color, a * 0.96F);
        textLeft(g, Component.translatable(KEY + "reward_summary"), PANEL_X0 + 28, PANEL_Y0 + 14 + rise, 20,
                GuiFx.fade(TEXT_GOLD, a), true);
        int pages = Math.max(1, (rewards.size() + MINI_SLOTS - 1) / MINI_SLOTS);
        textRight(g, Component.translatable(KEY + "strip.page", rewards.size(), rewardPage + 1, pages),
                PANEL_X1 - 28, PANEL_Y0 + 16 + rise, 16, GuiFx.fade(TEXT_DIM, a), false);
        List<CrateService.Reward> shown = rewards;
        if (shown.isEmpty() && result != null) shown = List.of(new CrateService.Reward(
                resultPreview().kind(), "skin".equals(resultPreview().kind()) ? result.skinType() + "/" + result.skin()
                : result.skin(), 1));
        int from = rewardPage * MINI_SLOTS, to = Math.min(shown.size(), from + MINI_SLOTS);
        int count = Math.max(0, to - from);
        boolean single = shown.size() == 1;
        float rowW = count * MINI_W + Math.max(0, count - 1) * MINI_GAP;
        float left = single ? PANEL_X0 + 40 : (PANEL_X0 + PANEL_X1 - rowW) / 2;
        for (int i = from; i < to; i++) {
            CrateService.Reward reward = shown.get(i);
            int local = i - from;
            float enter = CrateStage.easeOutCubic(CrateStage.progress(time, stage.revealAt() + 500 + 70L * local, 380));
            float x = left + local * (MINI_W + MINI_GAP), y = MINI_Y + rise + 16 * (1 - enter);
            SkinQuality quality = qualityOf(reward);
            CrateArt.rarityCard(g, x, y, MINI_W, MINI_H, rarityColor(quality), 1, 0.3F, a * enter);
            ItemStack stack;
            if ("skin".equals(reward.kind())) {
                String[] parts = reward.id().split("/", 2);
                stack = parts.length == 2 ? preview(parts[0], parts[1]) : new ItemStack(Items.BARRIER);
            } else stack = previewReward(reward.kind(), reward.id());
            drawReward(g, new CrateCatalog.RewardPreview(reward.kind(), reward.id(), reward.amount(), quality.id()),
                    stack, x + MINI_W / 2, y + 44, 3.4F, 0, 0, 1, a * enter);
            if (reward.amount() > 1) textRight(g, Component.literal("×" + reward.amount()), x + MINI_W - 8, y + 70, 16,
                    GuiFx.fade(TEXT_GOLD, a * enter), true);
            textClipped(g, stack.getHoverName(), x + 7, y + 92, 14, MINI_W - 14, GuiFx.fade(TEXT_BODY, a * enter), false);
        }
        if (single) {
            float tx = left + MINI_W + 36, ty = MINI_Y + rise + 8;
            String[] lines = {
                    Component.translatable(KEY + "reveal.tip1", Component.translatable(resultQuality().translationKey())).getString(),
                    Component.translatable(KEY + "reveal.tip2", crateName(), keyName()).getString(),
                    Component.translatable(KEY + "reveal.tip3", crateName()).getString()};
            int[] colors = {TEXT_BODY, 0xFFE0A060, TEXT_DIM};
            for (int i = 0; i < lines.length; i++) {
                CrateFx.diamond(g, tx + 4, ty + 10 + i * 36, 3.5F, 3.5F, GuiFx.fade(colors[i], a), false);
                textClipped(g, Component.literal(lines[i]), tx + 16, ty + i * 36, 18, PANEL_X1 - tx - 50,
                        GuiFx.fade(colors[i], a), false);
            }
        }
    }

    // =====================================================================
    // 文字工具（在参考空间里按像素字号绘制）
    // =====================================================================

    private float textScale(float size) { return size / font.lineHeight; }

    private void textAt(GuiGraphics g, Component text, float x, float y, float size, int color, boolean shadow) {
        if ((color >>> 24) < 5) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(textScale(size), textScale(size), 1.0F);
        g.drawString(font, text, 0, 0, color, shadow);
        g.pose().popPose();
    }

    private void textClipped(GuiGraphics g, Component text, float x, float y, float size,
                             float maxWidth, int color, boolean shadow) {
        float scale = textScale(size);
        int allowed = Math.max(1, (int) (maxWidth / scale));
        String raw = text.getString();
        String shown = font.width(raw) <= allowed ? raw
                : font.plainSubstrByWidth(raw, Math.max(1, allowed - font.width("…"))) + "…";
        textAt(g, Component.literal(shown), x, y, size, color, shadow);
    }

    private float textWidth(Component text, float size) { return font.width(text) * textScale(size); }

    private void textCentered(GuiGraphics g, Component text, float centerX, float y, float size, int color, boolean shadow) {
        textAt(g, text, centerX - textWidth(text, size) * 0.5F, y, size, color, shadow);
    }

    private void textLeft(GuiGraphics g, Component text, float x, float y, float size, int color, boolean shadow) {
        textAt(g, text, x, y, size, color, shadow);
    }

    private void textRight(GuiGraphics g, Component text, float right, float y, float size, int color, boolean shadow) {
        textAt(g, text, right - textWidth(text, size), y, size, color, shadow);
    }

    /** 带字距的居中文字（眉题）。 */
    private void textSpaced(GuiGraphics g, Component text, float centerX, float y, float size,
                            float spacing, int color, boolean shadow) {
        if ((color >>> 24) < 5) return;
        String raw = text.getString();
        float scale = textScale(size);
        float width = (font.width(raw) + spacing * Math.max(0, raw.length() - 1)) * scale;
        g.pose().pushPose();
        g.pose().translate(centerX - width * 0.5F, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        for (int i = 0; i < raw.length(); i++) {
            String ch = raw.substring(i, i + 1);
            g.drawString(font, ch, 0, 0, color, shadow);
            g.pose().translate(font.width(ch) + spacing, 0.0F, 0.0F);
        }
        g.pose().popPose();
    }

    // =====================================================================
    // 按钮
    // =====================================================================

    private void buildHotspots(long time) {
        hotspots.clear();
        if (departAt >= 0) return;
        CrateStage.Phase phase = stage.phase(time);
        boolean idle = !stage.active() || failure;
        boolean ready = readyToOpen();
        boolean finished = stage.finished(time);

        float modalAlpha = modalAlpha(time);
        if ((idle || phase == CrateStage.Phase.DISMISS) && !modalCancelled && modalAlpha > 0.02F) {
            hotspots.add(new Hotspot("modal-ok", MODAL_OK_X0, MODAL_BTN_Y0, MODAL_OK_X1, MODAL_BTN_Y1,
                    Component.translatable(KEY + "modal.confirm"), true, this::beginOpen).enabled(ready && idle).opacity(modalAlpha));
            hotspots.add(new Hotspot("modal-cancel", MODAL_CANCEL_X0, MODAL_BTN_Y0, MODAL_CANCEL_X1, MODAL_BTN_Y1,
                    Component.translatable(KEY + "modal.cancel"), false, () -> modalCancelled = true).enabled(idle).opacity(modalAlpha));
        }
        if (idle && (modalCancelled || failure)) {
            hotspots.add(new Hotspot("open", PRIMARY_X0, BTN_Y0, PRIMARY_X1, BTN_Y1,
                    Component.translatable(KEY + "open"), true, () -> {
                        if (failure) { failure = false; message = ""; stage = CrateStage.idle(); }
                        modalCancelled = false;
                        modalShownAt = now();
                    }).enabled(ready).pulse(1));
        }
        if (idle && modalCancelled && strip.size() > STRIP_SLOTS) {
            hotspots.add(new Hotspot("strip-prev", STRIP_PREV_X, STRIP_ARROW_Y0, STRIP_PREV_X + ARROW_W, STRIP_ARROW_Y1,
                    Component.literal("‹"), false, () -> stripPage--).enabled(stripPage > 0).opacity(stripAlpha(time)));
            hotspots.add(new Hotspot("strip-next", STRIP_NEXT_X, STRIP_ARROW_Y0, STRIP_NEXT_X + ARROW_W, STRIP_ARROW_Y1,
                    Component.literal("›"), false, () -> stripPage++).enabled((stripPage + 1) * STRIP_SLOTS < strip.size())
                    .opacity(stripAlpha(time)));
        }
        if (phase == CrateStage.Phase.REVEAL && finished) {
            hotspots.add(new Hotspot("again", PRIMARY_X0, BTN_Y0, PRIMARY_X1, BTN_Y1,
                    Component.translatable(KEY + "again"), true, this::beginOpen).enabled(ready).pulse(1));
        }
        if (finished && rewards.size() > MINI_SLOTS) {
            hotspots.add(new Hotspot("reward-prev", PANEL_PREV_X, 870, PANEL_PREV_X + ARROW_W, 918,
                    Component.literal("‹"), false, () -> rewardPage--).enabled(rewardPage > 0));
            hotspots.add(new Hotspot("reward-next", PANEL_NEXT_X, 870, PANEL_NEXT_X + ARROW_W, 918,
                    Component.literal("›"), false, () -> rewardPage++).enabled((rewardPage + 1) * MINI_SLOTS < rewards.size()));
        }
        if ((!locked(time)) && (modalCancelled || stage.active())) {
            float w = CLOSE_X1 - CLOSE_X0;
            float closeX = idle || finished ? CLOSE_X0 : (REF_W - w) / 2;
            hotspots.add(new Hotspot("close", closeX, BTN_Y0, closeX + w, BTN_Y1,
                    Component.translatable(KEY + "reveal.close"), false, this::depart));
        }
    }

    /** 自绘按钮：主按钮（绿色、呼吸光与扫光）与次级按钮（暗色玻璃），支持悬停、按下与键盘焦点。 */
    private final class Hotspot {
        private final String id;
        private final float x0, y0, x1, y1;
        private final Component label;
        private final boolean primary;
        private final Runnable action;
        private boolean enabled = true;
        private float opacity = 1, pulse;

        Hotspot(String id, float x0, float y0, float x1, float y1, Component label, boolean primary, Runnable action) {
            this.id = id;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.label = label;
            this.primary = primary;
            this.action = action;
        }

        Hotspot enabled(boolean value) { enabled = value; return this; }
        Hotspot opacity(float value) { opacity = value; return this; }
        Hotspot pulse(float value) { pulse = value; return this; }

        boolean contains(double mx, double my) {
            return enabled && opacity >= .4F && mx >= x0 && mx < x1 && my >= y0 && my < y1;
        }

        void render(GuiGraphics g, long time) {
            boolean over = enabled && (mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1
                    || hotspots.indexOf(this) == keyboardFocus);
            float hover = GuiFx.approach(hoverAnim.getOrDefault(id, 0F), over ? 1 : 0, frameDelta, 45);
            hoverAnim.put(id, hover);
            float a = opacity * (enabled ? 1 : 0.45F);
            float lift = -2 * hover;
            if (primary) CrateArt.primaryButton(g, x0, y0 + lift, x1, y1 + lift, a, hover, enabled ? pulse : 0, time);
            else CrateArt.ghostButton(g, x0, y0 + lift, x1, y1 + lift, a, hover);
            float size = label.getString().length() <= 1 ? 30 : 20;
            textCentered(g, label, (x0 + x1) / 2, (y0 + y1) / 2 + lift - size * 0.5F, size,
                    GuiFx.fade(enabled ? 0xFFFFFFFF : 0xFFBDBDBD, opacity), true);
        }
    }

    // =====================================================================
    // 输入
    // =====================================================================

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0 || departAt >= 0) return true;
        float cx = (float) ((x - canvasX) / unit), cy = (float) ((y - canvasY) / unit);
        keyboardFocus = -1;
        buildHotspots(now());
        for (Hotspot hotspot : hotspots) {
            if (hotspot.contains(cx, cy)) {
                sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.25F);
                hotspot.action.run();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) {
            onClose();
            return true;
        }
        buildHotspots(now());
        if (key == 258) {
            if (!hotspots.isEmpty()) {
                int direction = (modifiers & 1) == 0 ? 1 : -1;
                for (int i = 0; i < hotspots.size(); i++) {
                    keyboardFocus = Math.floorMod(keyboardFocus + direction, hotspots.size());
                    if (hotspots.get(keyboardFocus).enabled) break;
                }
            }
            return true;
        }
        if (key == 257 || key == 335 || key == 32) {
            if (keyboardFocus >= 0 && keyboardFocus < hotspots.size()) {
                Hotspot selected = hotspots.get(keyboardFocus);
                if (selected.enabled) selected.action.run();
            } else if (!stage.active() && modalCancelled && readyToOpen()) {
                modalCancelled = false;
                modalShownAt = now();
            } else if (!modalCancelled && modalAlpha(now()) >= 0.9F || stage.finished(now())) {
                beginOpen();
            }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean charTyped(char c, int modifiers) { return true; }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        float cy = (float) ((y - canvasY) / unit);
        float cx = (float) ((x - canvasX) / unit);
        if (!stage.active() && modalCancelled && cx >= STRIP_X0 && cx <= STRIP_X1
                && cy >= STRIP_Y0 && cy <= STRIP_Y1 && vertical != 0) {
            stripPage = Math.max(0, Math.min(Math.max(0, (strip.size() - 1) / STRIP_SLOTS),
                    stripPage + (vertical < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    // =====================================================================
    // 开箱
    // =====================================================================

    private void beginOpen() {
        long time = now();
        boolean finished = stage.hasResult() && stage.finished(time);
        if (stage.active() && !finished && !failure) return;
        if (!readyToOpen()) return;
        if (CrateClientNetwork.connected() && !CrateClientNetwork.canOpen()) {
            fail("crates.client_outdated"); return;
        }
        selectedKey = matchingKeyId();
        keyboardFocus = -1;
        stage = CrateStage.opened(time);
        if (modalShownAt < 0) modalShownAt = time - 260;
        result = null;
        rewards = List.of();
        resultStack = ItemStack.EMPTY;
        resultSlot = -1;
        failure = false;
        message = "";
        modalCancelled = false;
        firedHold = firedOpen = firedStop = firedReveal = false;
        lastTickSlot = Integer.MIN_VALUE;
        particles.clear();
        buildReel();
        buildStrip();
        activeOpenId = CrateClientNetwork.open(crateId, selectedKey);
        if (activeOpenId == null) fail("crates.pending");
    }
}

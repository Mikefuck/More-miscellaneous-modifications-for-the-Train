package com.habitrain.lottery.client.gui;

import com.google.gson.Gson;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.CrateNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
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
 * <p>舞台音效/粒子/背景光效见 {@link CrateOpenStageEffects}；
 * 奖池橱窗、转盘丝带、结果面板与跳过提示排版见 {@link CrateOpenRibbonRenderer}。</p>
 */
public final class CrateOpenScreen extends Screen {
    private static final String KEY = CrateOpenRibbonRenderer.KEY;

    // =====================================================================
    // 画布与分层
    // =====================================================================

    private static final float REF_W = CrateOpenRibbonRenderer.REF_W;
    private static final float REF_H = CrateOpenRibbonRenderer.REF_H;
    /** 物品渲染自身会再加 150 的深度，因此覆盖层必须高出物品层。 */
    private static final float Z_PARTICLES_BACK = 10, Z_SCENE = 20, Z_STRIP = 100,
            Z_BRIDGE = 400, Z_REVEAL_ITEM = 420, Z_MODAL = 480, Z_PARTICLES_FRONT = 600,
            Z_UI = 650, Z_HOTSPOT = 700;

    // ---- 顶部标题 ----
    private static final float HEAD_EYEBROW_Y = 34, HEAD_TITLE_Y = 58, HEAD_TITLE_SIZE = 42,
            HEAD_CHIP_Y0 = 120, HEAD_CHIP_Y1 = 154;

    // ---- 奖励橱窗交互边界 ----
    private static final float STRIP_X0 = CrateOpenRibbonRenderer.STRIP_X0,
            STRIP_X1 = CrateOpenRibbonRenderer.STRIP_X1,
            STRIP_Y0 = CrateOpenRibbonRenderer.STRIP_Y0,
            STRIP_Y1 = CrateOpenRibbonRenderer.STRIP_Y1;
    private static final float STRIP_CARD_Y = CrateOpenRibbonRenderer.STRIP_CARD_Y,
            STRIP_CARD_W = CrateOpenRibbonRenderer.STRIP_CARD_W,
            STRIP_CARD_H = CrateOpenRibbonRenderer.STRIP_CARD_H,
            STRIP_GAP = CrateOpenRibbonRenderer.STRIP_GAP;
    private static final int STRIP_SLOTS = CrateOpenRibbonRenderer.STRIP_SLOTS;
    private static final float STRIP_ARROW_Y0 = 870, STRIP_ARROW_Y1 = 918,
            STRIP_PREV_X = 160, STRIP_NEXT_X = 1716, ARROW_W = 44;

    // ---- 底部操作栏 ----
    private static final float NAV_Y0 = 1008, BTN_Y0 = 1022, BTN_Y1 = 1068;
    private static final float PRIMARY_X0 = 730, PRIMARY_X1 = 950, CLOSE_X0 = 970, CLOSE_X1 = 1150;

    // ---- 确认弹窗 ----
    private static final float MODAL_X0 = 540, MODAL_X1 = 1380, MODAL_Y0 = 400, MODAL_Y1 = 680;
    private static final float MODAL_TEXT_X = 850, MODAL_BTN_Y0 = 610, MODAL_BTN_Y1 = 660,
            MODAL_OK_X0 = 1090, MODAL_OK_X1 = 1250, MODAL_CANCEL_X0 = 1264, MODAL_CANCEL_X1 = 1360;

    // ---- 展示页 ----
    private static final float REVEAL_X = CrateOpenStageEffects.REVEAL_X,
            REVEAL_Y = CrateOpenStageEffects.REVEAL_Y;
    private static final int MINI_SLOTS = CrateOpenRibbonRenderer.MINI_SLOTS;
    private static final float PANEL_PREV_X = 404, PANEL_NEXT_X = 1472;

    // ---- 配色 ----
    private static final int TEXT_BRIGHT = CrateOpenRibbonRenderer.TEXT_BRIGHT,
            TEXT_BODY = CrateOpenRibbonRenderer.TEXT_BODY,
            TEXT_DIM = CrateOpenRibbonRenderer.TEXT_DIM,
            TEXT_GOLD = CrateOpenRibbonRenderer.TEXT_GOLD,
            DANGER = 0xFFFF8A8A, TEAL = WarehouseTheme.TEAL;

    private static final Gson GSON = new Gson();

    // =====================================================================
    // 状态（注意：保持字段名与类型不变以兼容 CrateSmoke 反射断言）
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
    private final CrateOpenStageEffects effects = new CrateOpenStageEffects();

    // ---- 渲染期缓存（避免 60~240 FPS 每帧查注册表与重建奖励栈） ----
    private int cachedIconCatalogVer = Integer.MIN_VALUE;
    private ItemStack cachedCrateIcon = ItemStack.EMPTY;
    private ItemStack cachedKeyIcon = ItemStack.EMPTY;
    private List<CrateService.Reward> cachedSummaryRewardsRef;
    private CrateNetwork.OpenResultS2C cachedSummaryResultRef;
    private int cachedSummaryCatalogVer = Integer.MIN_VALUE;
    private List<CrateOpenRibbonRenderer.SummaryEntry> cachedSummaryEntries = List.of();

    // ---- 长按空格跳过 ----
    private static final long SKIP_HOLD_MS = 1000L;
    private static final float SKIP_DECAY_MS = 220.0F;
    private long skipHeldSince = -1;
    private boolean spaceDown;
    private boolean skipQueued;
    private float skipShown;

    public CrateOpenScreen(Screen parent, String crateId) {
        super(Component.translatable(KEY + "hud.title"));
        this.parent = parent;
        this.crateId = crateId;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void init() {
        long now = now();
        if (enteredAt < 0) enteredAt = now;
        lastFrame = now;
        if (inventoryVersion < 0) {
            inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
            inventory = CrateClientNetwork.STATE.inventory;
        }
        selectedKey = matchingKeyId();
        CrateClientNetwork.requestInventory();
        if (reel.isEmpty()) buildReel();
        if (strip.isEmpty()) buildStrip();
    }

    /** 背景只在 {@link #render} 里画一次。 */
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
    }

    @Override
    public void tick() {
        super.tick();
        refreshFromState();
        long time = now();
        if (stage.active() && !stage.hasResult() && !failure
                && time - stage.openedAt() > CrateStage.SPIN_TIMEOUT_MS) {
            fail("screen.habitrain_lottery.crate.timeout");
        }
        if (departAt >= 0 && !leaving) finishLeaving();
    }

    public void refreshFromState() {
        if (catalogVersion != CrateClientNetwork.STATE.catalogVersion && (!stage.active() || stage.finished(now()) || failure)) {
            catalogVersion = CrateClientNetwork.STATE.catalogVersion;
            cachedIconCatalogVer = Integer.MIN_VALUE;
            cachedSummaryCatalogVer = Integer.MIN_VALUE;
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
        failure = false;
        message = "";
        result = payload;
        rewardPage = 0;
        if (parent instanceof WarehouseScreen warehouse) warehouse.expectInventoryRevision(payload.inventoryRevision());
        try {
            CrateService.Reward[] parsed = GSON.fromJson(payload.rewardsJson(), CrateService.Reward[].class);
            rewards = parsed == null ? List.of() : List.of(parsed);
        } catch (RuntimeException error) {
            rewards = List.of();
        }
        cachedSummaryRewardsRef = null;
        stage = stage.result(now);
        resultStack = CrateOpenRibbonRenderer.previewReward(payload.skinType(), payload.skin());
        SkinQuality quality = SkinQuality.fromId(payload.quality());
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
        if (skipQueued) applySkip(now);
    }

    private void fail(String key) {
        skipHeldSince = -1;
        skipQueued = false;
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

    @Override
    public void onClose() {
        if (departAt >= 0) return;
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =====================================================================
    // 数据与缓存
    // =====================================================================

    private static long now() {
        return Util.getMillis();
    }

    private int count(String id) {
        return (int) Math.round(inventory.getOrDefault(id, 0D));
    }

    private String matchingKeyId() {
        return CrateService.keyItemId(crateId);
    }

    private int crateCount() {
        return count(CrateService.crateItemId(crateId));
    }

    private int keyCount() {
        return count(matchingKeyId());
    }

    private int accent() {
        CrateService.Definition definition = CrateService.definition(crateId);
        return definition == null ? 0xFF57C6D6 : 0xFF000000 | definition.color();
    }

    private int crateLight() {
        return GuiFx.mix(CrateArt.WARM_LIGHT, accent(), 0.45F);
    }

    private SkinQuality resultQuality() {
        return result == null ? SkinQuality.WHITE : SkinQuality.fromId(result.quality());
    }

    private float tier() {
        return resultQuality().ordinal() / (float) (SkinQuality.values().length - 1);
    }

    private String crateName(CrateCatalog.Entry entry) {
        return Component.translatable(entry == null ? KEY + crateId : entry.nameKey()).getString();
    }

    private String keyName(CrateCatalog.Entry entry) {
        return Component.translatable(entry == null ? KEY + "key." + crateId : entry.keyName()).getString();
    }

    private static ItemStack iconStack(String id, Item fallback) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != Items.AIR) return new ItemStack(item);
        } catch (RuntimeException ignored) {
        }
        return new ItemStack(fallback);
    }

    private void ensureIconsCached(CrateCatalog.Entry entry) {
        int ver = CrateClientNetwork.STATE.catalogVersion;
        if (cachedIconCatalogVer == ver && !cachedCrateIcon.isEmpty() && !cachedKeyIcon.isEmpty()) {
            return;
        }
        cachedIconCatalogVer = ver;
        cachedCrateIcon = iconStack(entry == null ? "minecraft:chest" : entry.icon(), Items.CHEST);
        cachedKeyIcon = iconStack(entry == null ? "minecraft:tripwire_hook" : entry.keyIcon(), Items.TRIPWIRE_HOOK);
    }

    private ItemStack crateIcon(CrateCatalog.Entry entry) {
        ensureIconsCached(entry);
        return cachedCrateIcon;
    }

    private ItemStack keyIcon(CrateCatalog.Entry entry) {
        ensureIconsCached(entry);
        return cachedKeyIcon;
    }

    private void drawCrate(GuiGraphics g, float x, float baseY, float width, float lid,
                           int ambient, float glow, float alpha) {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        CrateCatalog.Entry builtin = CrateCatalog.builtin(crateId);
        boolean styled = entry != null && (builtin == null || entry.color() != builtin.color()
                || !crateId.equals(entry.appearancePreset()) || !"star".equals(entry.badge()));
        if (styled) {
            CrateArt.crateStyledLit(g, x, baseY, width, lid, accent(), entry.appearancePreset(), entry.badge(),
                    ambient, glow, crateLight(), alpha);
        } else {
            CrateArt.crateLit(g, x, baseY, width, lid, ambient, glow, crateLight(), alpha);
        }
    }

    private List<CrateCatalog.RewardPreview> candidates() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return entry == null ? List.of() : entry.rewards();
    }

    private SkinQuality qualityOf(CrateService.Reward reward, List<CrateCatalog.RewardPreview> candidates) {
        if (result != null && "skin".equals(reward.kind())
                && reward.id().equals(result.skinType() + "/" + result.skin())) {
            return resultQuality();
        }
        for (var candidate : candidates) {
            if (candidate.kind().equals(reward.kind()) && candidate.id().equals(reward.id())) {
                return SkinQuality.fromId(candidate.quality());
            }
        }
        return SkinQuality.WHITE;
    }

    private List<CrateOpenRibbonRenderer.SummaryEntry> summaryEntries() {
        if (cachedSummaryRewardsRef == rewards
                && cachedSummaryResultRef == result
                && cachedSummaryCatalogVer == catalogVersion) {
            return cachedSummaryEntries;
        }
        List<CrateService.Reward> source = rewards;
        if (source.isEmpty() && result != null) {
            CrateCatalog.RewardPreview rp = resultPreview();
            source = List.of(new CrateService.Reward(
                    rp.kind(),
                    "skin".equals(rp.kind()) ? result.skinType() + "/" + result.skin() : result.skin(),
                    1));
        }
        List<CrateCatalog.RewardPreview> pool = candidates();
        List<CrateOpenRibbonRenderer.SummaryEntry> built = new ArrayList<>(source.size());
        for (CrateService.Reward reward : source) {
            SkinQuality q = qualityOf(reward, pool);
            ItemStack stack;
            if ("skin".equals(reward.kind())) {
                String[] parts = reward.id().split("/", 2);
                stack = parts.length == 2
                        ? CrateOpenRibbonRenderer.preview(parts[0], parts[1])
                        : new ItemStack(Items.BARRIER);
            } else {
                stack = CrateOpenRibbonRenderer.previewReward(reward.kind(), reward.id());
            }
            CrateCatalog.RewardPreview preview = new CrateCatalog.RewardPreview(
                    reward.kind(), reward.id(), reward.amount(), q.id());
            built.add(new CrateOpenRibbonRenderer.SummaryEntry(reward, preview, stack, q));
        }
        cachedSummaryRewardsRef = rewards;
        cachedSummaryResultRef = result;
        cachedSummaryCatalogVer = catalogVersion;
        cachedSummaryEntries = List.copyOf(built);
        return cachedSummaryEntries;
    }

    private void buildReel() {
        List<ItemStack> out = new ArrayList<>();
        List<SkinQuality> quality = new ArrayList<>();
        reelRewards = CrateReelSequence.sample(candidates(), CrateStage.SLOTS, ThreadLocalRandom.current());
        for (var reward : reelRewards) {
            out.add(CrateOpenRibbonRenderer.previewCandidate(reward));
            quality.add(SkinQuality.fromId(reward.quality()));
        }
        reel = List.copyOf(out);
        reelQuality = List.copyOf(quality);
    }

    private void buildStrip() {
        List<CrateCatalog.RewardPreview> pool = candidates();
        strip = pool.stream().map(CrateOpenRibbonRenderer::previewCandidate).toList();
        stripQuality = pool.stream().map(r -> SkinQuality.fromId(r.quality())).toList();
        stripPage = Math.min(stripPage, Math.max(0, (strip.size() - 1) / STRIP_SLOTS));
    }

    private CrateCatalog.RewardPreview resultPreview() {
        if (result == null) return null;
        String kind = result.skinType();
        return new CrateCatalog.RewardPreview("green_apples".equals(kind) || "card".equals(kind) ? kind : "skin",
                result.skin(), 1, result.quality());
    }

    private boolean readyToOpen() {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        return entry != null && entry.enabled() && !entry.archived() && !entry.rewards().isEmpty()
                && crateCount() > 0 && keyCount() > 0;
    }

    // =====================================================================
    // 渲染：驱动
    // =====================================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now();
        frameDelta = Math.min(80.0F, Math.max(1.0F, time - lastFrame));
        lastFrame = time;
        g.flush();
        RenderSystem.clear(256, Minecraft.ON_OSX);
        g.fill(0, 0, width, height, 0xFF0B0D0C);
        unit = Math.min(width / REF_W, height / REF_H);
        canvasX = (width - REF_W * unit) * .5F;
        canvasY = (height - REF_H * unit) * .5F;
        mouseX = (mx - canvasX) / unit;
        mouseY = (my - canvasY) / unit;

        SkinQuality rq = resultQuality();
        int rarity = CrateOpenRibbonRenderer.rarityColor(rq);
        float tierVal = tier();

        updateSkip(time);
        effects.driveEvents(time, frameDelta, enteredAt, stage, failure, crateLight(), rq, rarity, tierVal);
        effects.tickParticles(frameDelta);

        float[] shake = CrateOpenStageEffects.shake(time, stage, failure, tierVal);
        g.enableScissor((int) canvasX, (int) canvasY, (int) Math.ceil(canvasX + REF_W * unit),
                (int) Math.ceil(canvasY + REF_H * unit));
        try {
            g.pose().pushPose();
            try {
                g.pose().translate(canvasX, canvasY, 0);
                g.pose().scale(unit, unit, 1.0F);
                g.pose().pushPose();
                try {
                    g.pose().translate(shake[0], shake[1], 0);
                    renderWorld(g, time, rq, rarity, tierVal);
                } finally {
                    g.pose().popPose();
                }
                renderInterface(g, time);
            } finally {
                g.pose().popPose();
            }
        } finally {
            g.disableScissor();
        }

        int hovered = hoveredStripCard(time);
        if (hovered >= 0 && hovered < strip.size()) {
            g.renderTooltip(font, List.of(strip.get(hovered).getHoverName().getVisualOrderText(),
                    Component.translatable(KEY + "preview_note").getVisualOrderText()), mx, my);
        }
        if (departAt >= 0) finishLeaving();
    }

    private void renderWorld(GuiGraphics g, long time, SkinQuality rq, int rarity, float tierVal) {
        boolean revealEnv = stage.cardsGone(time) && !failure;
        float reveal = CrateStage.clamp01(stage.revealing(time));

        if (revealEnv) CrateOpenStageEffects.renderRevealStage(g, rarity);
        else CrateOpenStageEffects.renderCourtyard(g, time, enteredAt, stage, failure);

        CrateOpenRibbonRenderer.layer(g, Z_PARTICLES_BACK, () -> effects.particles().render(g, 1, false));

        if (!revealEnv) CrateOpenRibbonRenderer.layer(g, Z_SCENE, () -> renderCrate(g, time));
        else CrateOpenRibbonRenderer.layer(g, Z_SCENE, () -> CrateOpenStageEffects.renderRevealBack(g, time, reveal, rarity, tierVal));

        CrateOpenRibbonRenderer.layer(g, Z_STRIP, () -> CrateOpenRibbonRenderer.renderStrip(
                g, font, time, enteredAt, stage, failure, stripAlpha(time), frameDelta,
                strip, stripQuality, candidates(), stripPage, hoveredStripCard(time), stripHover));

        boolean cardsVisible = stage.openedAt() >= 0 && !stage.cardsGone(time) && !failure
                && time >= stage.spinAt();
        if (cardsVisible) {
            CrateOpenRibbonRenderer.renderReel(g, time, stage, canvasX, canvasY, unit,
                    reel, reelQuality, reelRewards, resultSlot, resultPreview(), rq, tierVal, crateLight());
        }

        float bridge = stage.bridge(time);
        if (bridge > 0.01F) {
            CrateOpenRibbonRenderer.layer(g, Z_BRIDGE, () ->
                    CrateArt.bridge(g, (int) REF_W, (int) REF_H, ScreenSwap.BRIDGE_COLOR, 0.94F * bridge));
        }

        if (revealEnv && reveal > 0) {
            CrateOpenRibbonRenderer.layer(g, Z_REVEAL_ITEM, () -> renderRevealItem(g, time, reveal, rarity));
        }
        CrateOpenRibbonRenderer.layer(g, Z_PARTICLES_FRONT, () -> {
            effects.particles().render(g, 1, true);
            CrateOpenStageEffects.renderFlashes(g, time, stage, failure, crateLight(), rarity, tierVal);
        });
    }

    private void renderInterface(GuiGraphics g, long time) {
        CrateOpenRibbonRenderer.layer(g, Z_MODAL, () -> renderModal(g, time));
        CrateOpenRibbonRenderer.layer(g, Z_UI, () -> renderChrome(g, time));
        CrateOpenRibbonRenderer.layer(g, Z_HOTSPOT, () -> {
            buildHotspots(time);
            for (Hotspot hotspot : hotspots) hotspot.render(g, time);
        });
    }

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
        try {
            g.pose().translate(cx + jitterX, baseY + drop, 0.0F);
            if (Math.abs(roll) > 0.01F) g.pose().mulPose(Axis.ZP.rotationDegrees(roll));
            if (Math.abs(yaw) > 0.01F) g.pose().scale(Math.max(0.35F, (float) Math.cos(Math.toRadians(yaw))), 1.0F, 1.0F);
            g.pose().translate(-cx, -baseY, 0.0F);
            drawCrate(g, cx, baseY, width, lid, ambient, glow, 1);
            if (!stage.active() && landed) {
                CrateArt.Iso iso = new CrateArt.Iso(cx, baseY, width);
                float sweep = ((time - enteredAt) % 4200L) / 4200.0F;
                float x = iso.px(0, 0, 0) + (iso.px(1, 0, 0) - iso.px(0, 0, 0) + width * 0.4F) * sweep - width * 0.2F;
                float sx = iso.px(0, 1, 0);
                float sy = iso.py(0, 1, 0);
                int[] sc = CrateArt.transformScissorBounds(g.pose().last().pose(),
                        sx, sy, iso.px(1, 0, 0) - sx, iso.py(1, 0, 0) - sy);
                g.enableScissor(sc[0], sc[1], sc[2], sc[3]);
                try {
                    CrateFx.skewBand(g, x, sy, iso.py(0, 0, 0) + width * 0.06F, width * 0.05F, width * 0.12F, 0x1CFFFFFF);
                } finally {
                    g.disableScissor();
                }
            }
        } finally {
            g.pose().popPose();
        }

        if (open > 0.01F) {
            CrateArt.Iso iso = new CrateArt.Iso(cx, baseY, width);
            float mouthY = iso.py(.5F, 1, .5F);
            CrateFx.glow(g, cx, mouthY, width * 0.55F, width * 0.13F, light, 0.85F * open, true);
            CrateFx.glow(g, cx, mouthY, width * 0.28F, width * 0.07F, 0xFFFFFFFF, 0.55F * open, true);
            CrateFx.beam(g, cx, -40, mouthY, width * 0.62F, width * 0.30F, light, (0.30F + 0.5F * burst) * open);
            CrateFx.beam(g, cx, -40, mouthY, width * 0.20F, width * 0.10F, 0xFFFFFFFF, (0.18F + 0.5F * burst) * open);
        }
    }

    private void renderRevealItem(GuiGraphics g, long time, float reveal, int color) {
        float morph = CrateStage.easeOutBack(reveal, 0.9F);
        float settled = CrateStage.clamp01((reveal - 0.7F) / 0.3F);
        float idle = (time - stage.revealAt()) / 1000.0F;
        float fromScale = Math.min(CrateOpenRibbonRenderer.REEL_CARD_W, CrateOpenRibbonRenderer.REEL_CARD_H) * 0.60F / 16.0F;
        float toScale = REF_H * 0.33F / 16.0F;
        float size = fromScale + (toScale - fromScale) * morph;
        float px = REVEAL_X;
        float py = CrateStage.lerp(CrateOpenRibbonRenderer.REEL_CY, REVEAL_Y, CrateStage.easeOutCubic(reveal))
                + 7 * (float) Math.sin(idle * 1.3) * settled;
        float yaw = CrateStage.lerp(-CrateStage.REVEAL_YAW, 0, CrateStage.easeOutCubic(reveal))
                + 7 * (float) Math.sin(idle * 0.8) * settled;
        float roll = 2 * (float) Math.sin(idle * 1.1) * settled;
        CrateFx.glow(g, px, py, size * 9, size * 8, color, 0.22F + 0.1F * settled, true);
        CrateFx.glow(g, px, py, size * 4, size * 4, 0xFFFFFFFF, 0.18F * (1 - settled) + 0.06F, true);
        CrateOpenRibbonRenderer.drawReward(g, resultPreview(), resultStack, px, py, size, yaw, roll, 1, 1);
        for (int i = 0; i < 4; i++) {
            float phase = (idle * 0.7F + i * 0.25F) % 1;
            float twinkle = (float) Math.sin(phase * Math.PI);
            double angle = i * 1.7 + 0.6;
            CrateFx.sparkle(g, px + (float) Math.cos(angle) * size * 9, py + (float) Math.sin(angle) * size * 7,
                    18 + 10 * twinkle, 0, i % 2 == 0 ? 0xFFFFFFFF : color, twinkle * settled * 0.9F);
        }
    }

    // =====================================================================
    // 橱窗悬停与确认弹窗
    // =====================================================================

    private float stripAlpha(long time) {
        float in = CrateStage.stripIn(time, enteredAt);
        if (failure) return in;
        return in * (1 - stage.dismiss(time));
    }

    private int stripCount() {
        return Math.max(0, Math.min(STRIP_SLOTS, strip.size() - stripPage * STRIP_SLOTS));
    }

    private int hoveredStripCard(long time) {
        if (stage.active() && !failure || !modalCancelled || stripAlpha(time) < 0.5F) return -1;
        int count = stripCount();
        float left = CrateOpenRibbonRenderer.stripLeft(count);
        for (int i = 0; i < count; i++) {
            float x = left + i * (STRIP_CARD_W + STRIP_GAP);
            if (mouseX >= x && mouseX < x + STRIP_CARD_W && mouseY >= STRIP_CARD_Y && mouseY < STRIP_CARD_Y + STRIP_CARD_H) {
                return stripPage * STRIP_SLOTS + i;
            }
        }
        return -1;
    }

    private float modalAlpha(long time) {
        if (modalCancelled && !stage.active()) return 0;
        if (failure) return 0;
        float in = modalShownAt < 0 ? 0 : CrateStage.easeOutCubic(CrateStage.progress(time, modalShownAt, 260));
        return in * (1.0F - CrateStage.easeOutCubic(stage.dismiss(time)));
    }

    private void renderModal(GuiGraphics g, long time) {
        float alpha = modalAlpha(time);
        if (alpha <= 0.02F) return;
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        String cName = crateName(entry);
        g.fill(0, 0, (int) REF_W, (int) REF_H, GuiFx.fade(0x99020304, alpha));
        float pop = 0.94F + 0.06F * alpha;
        g.pose().pushPose();
        try {
            g.pose().translate(REF_W / 2, (MODAL_Y0 + MODAL_Y1) / 2, 0);
            g.pose().scale(pop, pop, 1);
            g.pose().translate(-REF_W / 2, -(MODAL_Y0 + MODAL_Y1) / 2, 0);
            int accent = accent();
            CrateFx.rectGlow(g, MODAL_X0, MODAL_Y0, MODAL_X1, MODAL_Y1, 40, accent, 0.18F * alpha);
            CrateArt.glassPanel(g, MODAL_X0, MODAL_Y0, MODAL_X1, MODAL_Y1, 14, GuiFx.mix(accent, CrateArt.GOLD_LINE, 0.3F), alpha);

            float tx = 690, ty = 612;
            CrateFx.glow(g, tx, ty - 90, 170, 150, crateLight(), 0.30F * alpha, true);
            CrateFx.glow(g, tx, ty + 6, 150, 22, 0xFF000000, 0.7F * alpha, false);
            CrateFx.dashRing(g, tx, ty + 6, 150, 22, 28, 0.55F, time / 1400.0F, 2.5F, crateLight(), 0.5F * alpha);
            drawCrate(g, tx, ty, 220, 0, 0xFFFFFFFF, 0.25F + 0.15F * (float) Math.sin(time / 500.0), alpha);

            float y = MODAL_Y0 + 34;
            CrateOpenRibbonRenderer.textSpaced(g, font, Component.translatable(KEY + "modal.eyebrow"),
                    MODAL_TEXT_X + 150, y, 15, 2.2F, GuiFx.fade(TEXT_GOLD, alpha), false);
            CrateOpenRibbonRenderer.textClipped(g, font, Component.translatable(KEY + "modal.title", cName),
                    MODAL_TEXT_X, y + 26, 30, MODAL_X1 - MODAL_TEXT_X - 30, GuiFx.fade(TEXT_BRIGHT, alpha), true);
            CrateFx.hairline(g, MODAL_TEXT_X, MODAL_X1 - 30, y + 70, 1, 120, GuiFx.fade(0x55FFFFFF, alpha), false);
            CrateOpenRibbonRenderer.textClipped(g, font, Component.translatable(KEY + "modal.body", cName),
                    MODAL_TEXT_X, y + 84, 18, MODAL_X1 - MODAL_TEXT_X - 30, GuiFx.fade(TEXT_BODY, alpha), false);
            if (entry != null) {
                Component summary = "unified_pool".equals(entry.rewardMode())
                        ? Component.translatable(KEY + "modal.unified", entry.rollCount(), entry.minimumSkinCount())
                        : Component.translatable(KEY + "modal.fixed", entry.skinDrawCount());
                CrateFx.diamond(g, MODAL_TEXT_X + 5, y + 122, 4, 4, GuiFx.fade(TEXT_GOLD, alpha), false);
                CrateOpenRibbonRenderer.textClipped(g, font, summary, MODAL_TEXT_X + 16, y + 114, 16,
                        MODAL_X1 - MODAL_TEXT_X - 50, GuiFx.fade(TEXT_GOLD, alpha), false);
                if (!entry.extraKinds().isEmpty()) {
                    CrateOpenRibbonRenderer.textClipped(g, font,
                            Component.translatable(KEY + "modal.extras", String.join(", ", entry.extraKinds().stream().map(kind -> Component.translatable(
                                    "green_apples".equals(kind) ? "screen.habitrain_lottery.warehouse.green_apples"
                                            : "screen.habitrain_lottery.config.cards." + kind).getString()).toList())),
                            MODAL_TEXT_X + 16, y + 140, 15, MODAL_X1 - MODAL_TEXT_X - 50, GuiFx.fade(TEXT_DIM, alpha), false);
                }
            }
            int keys = keyCount();
            CrateArt.item(g, keyIcon(entry), MODAL_TEXT_X + 12, MODAL_BTN_Y0 + 25, 1.6F, 0, 0, 1, alpha);
            CrateOpenRibbonRenderer.textLeft(g, font, Component.translatable(KEY + "key_use", keyName(entry), keys),
                    MODAL_TEXT_X + 32, MODAL_BTN_Y0 + 16, 17, GuiFx.fade(keys <= 0 ? DANGER : TEXT_BODY, alpha), false);
        } finally {
            g.pose().popPose();
        }
    }

    // =====================================================================
    // 顶部标题与底部操作栏
    // =====================================================================

    private void renderChrome(GuiGraphics g, long time) {
        CrateCatalog.Entry entry = CrateCatalog.find(crateId);
        String cName = crateName(entry);
        String kName = keyName(entry);
        float enter = ScreenSwap.arriveFade(ScreenSwap.arrive(time, enteredAt));
        boolean revealed = stage.cardsGone(time) && !failure;
        float plate = revealed ? CrateStage.easeOutCubic(CrateStage.progress(time, stage.revealAt() + 150, 450)) : 0;

        if (!revealed) {
            renderHeader(g, time, enter, entry, cName);
        } else {
            SkinQuality rq = resultQuality();
            CrateOpenRibbonRenderer.renderResultHeader(g, font, plate, rq, resultStack, cName);
            CrateOpenRibbonRenderer.renderSummary(g, font, time,
                    CrateStage.easeOutCubic(CrateStage.progress(time, stage.revealAt() + 400, 500)),
                    stage, rq, summaryEntries(), rewards.size(), rewardPage, cName, kName);
        }

        CrateFx.vGradient(g, 0, NAV_Y0 - 50, REF_W, NAV_Y0, 0x00030404, GuiFx.fade(0xB0030404, enter), false);
        g.fill(0, (int) NAV_Y0, (int) REF_W, (int) REF_H, GuiFx.fade(0xD8030404, enter));
        CrateFx.hairline(g, 0, REF_W, NAV_Y0, 1, 700, GuiFx.fade(0x70FFFFFF, enter), false);
        CrateFx.hairline(g, 560, REF_W - 560, NAV_Y0, 1, 260, GuiFx.fade(TEAL, 0.8F * enter), false);
        boolean idle = !stage.active() || failure;
        int keys = keyCount();
        int crates = crateCount();
        if (idle || revealed) {
            CrateArt.item(g, keyIcon(entry), 214, 1045, 1.7F, 0, 0, 1, enter);
            CrateOpenRibbonRenderer.textLeft(g, font, Component.translatable(KEY + "key_use", kName, keys),
                    236, 1036, 18, GuiFx.fade(keys <= 0 ? DANGER : TEXT_BODY, enter), false);
            CrateArt.item(g, crateIcon(entry), REF_W - 214, 1045, 1.7F, 0, 0, 1, enter);
            CrateOpenRibbonRenderer.textRight(g, font, Component.translatable(KEY + "nav.crates", crates),
                    REF_W - 236, 1036, 18, GuiFx.fade(crates <= 0 ? DANGER : TEXT_BODY, enter), false);
        } else {
            Component status = Component.translatable(KEY + (stage.phase(time) == CrateStage.Phase.CAROUSEL
                    ? (stage.hasResult() ? "status.rolling" : "status.waiting") : "status.charging"));
            float pulse = 0.65F + 0.35F * (float) Math.sin(time / 260.0);
            CrateOpenRibbonRenderer.textSpaced(g, font, status, REF_W / 2, 1034, 19, 2,
                    GuiFx.fade(TEXT_GOLD, enter * pulse), true);
        }
        boolean showSkip = canSkip(time) || skipQueued || skipShown > 0.01F;
        CrateOpenRibbonRenderer.renderSkipHint(g, font, time, enter, stage,
                showSkip, skipQueued, skipHeldSince >= 0 || skipQueued, skipShown);

        List<CrateCatalog.RewardPreview> pool = entry == null ? List.of() : entry.rewards();
        Component notice = null;
        if (failure && !message.isEmpty() && (stage.failedVisible(time) || !stage.active())) {
            notice = Component.translatable(message);
        } else if (!message.isEmpty() && toastAt >= 0 && time - toastAt < 1800) {
            notice = Component.translatable(message);
        } else if (!stage.active() && pool.isEmpty()) {
            notice = Component.translatable(KEY + "strip.empty");
        } else if (!stage.active() && entry != null && !entry.enabled()) {
            notice = Component.translatable("crates.disabled");
        } else if (!stage.active() && crates <= 0) {
            notice = Component.translatable(KEY + "missing_crate");
        } else if (!stage.active() && keys <= 0) {
            notice = Component.translatable(KEY + "missing_key");
        }
        if (notice != null && enter > 0.5F) {
            float w = CrateOpenRibbonRenderer.textWidth(font, notice, 20) + 64, y0 = 726;
            CrateFx.rectGlow(g, REF_W / 2 - w / 2, y0, REF_W / 2 + w / 2, y0 + 42, 18, DANGER, 0.18F);
            CrateArt.chip(g, REF_W / 2 - w / 2, y0, REF_W / 2 + w / 2, y0 + 42, DANGER, 0.95F);
            CrateFx.diamond(g, REF_W / 2 - w / 2 + 22, y0 + 21, 5, 5, DANGER, false);
            CrateOpenRibbonRenderer.textCentered(g, font, notice, REF_W / 2 + 8, y0 + 11, 20, DANGER, true);
        }
    }

    private void renderHeader(GuiGraphics g, long time, float enter, CrateCatalog.Entry entry, String cName) {
        float busy = failure ? 0 : CrateStage.easeOutCubic(stage.dismiss(time));
        if (enter <= 0.02F) return;
        int accent = GuiFx.mix(accent(), CrateArt.GOLD_LINE, 0.4F);
        float cx = REF_W / 2;
        CrateOpenRibbonRenderer.textSpaced(g, font, Component.translatable(KEY + "hud.title"), cx, HEAD_EYEBROW_Y,
                16, 3.5F, GuiFx.fade(GuiFx.mix(TEXT_DIM, accent, 0.5F), enter), false);
        CrateFx.hairline(g, cx - 250, cx - 60, HEAD_EYEBROW_Y + 8, 1, 90, GuiFx.fade(accent, 0.8F * enter), false);
        CrateFx.hairline(g, cx + 60, cx + 250, HEAD_EYEBROW_Y + 8, 1, 90, GuiFx.fade(accent, 0.8F * enter), false);
        Component name = Component.literal(cName);
        float nameW = CrateOpenRibbonRenderer.textWidth(font, name, HEAD_TITLE_SIZE);
        CrateFx.glow(g, cx, HEAD_TITLE_Y + 22, nameW * 0.7F + 60, 46, accent, 0.20F * enter, true);
        CrateOpenRibbonRenderer.textCentered(g, font, name, cx, HEAD_TITLE_Y, HEAD_TITLE_SIZE,
                GuiFx.fade(TEXT_BRIGHT, enter), true);

        float chipsA = enter * (1 - busy);
        if (chipsA <= 0.02F) return;
        int crates = crateCount();
        int keys = keyCount();
        Component cratesText = Component.translatable(KEY + "chip.crates", crates);
        Component keysText = Component.translatable(KEY + "chip.keys", keys);
        float w1 = CrateOpenRibbonRenderer.textWidth(font, cratesText, 17) + 62;
        float w2 = CrateOpenRibbonRenderer.textWidth(font, keysText, 17) + 62;
        float gap = 16;
        float x = cx - (w1 + w2 + gap) / 2;
        renderChip(g, x, crateIcon(entry), cratesText, w1, crates > 0 ? accent : DANGER, chipsA);
        renderChip(g, x + w1 + gap, keyIcon(entry), keysText, w2, keys > 0 ? TEAL : DANGER, chipsA);
    }

    private void renderChip(GuiGraphics g, float x, ItemStack icon, Component text, float w, int accent, float a) {
        CrateArt.chip(g, x, HEAD_CHIP_Y0, x + w, HEAD_CHIP_Y1, accent, a);
        CrateArt.item(g, icon, x + 22, (HEAD_CHIP_Y0 + HEAD_CHIP_Y1) / 2, 1.45F, 0, 0, 1, a);
        CrateOpenRibbonRenderer.textLeft(g, font, text, x + 42, HEAD_CHIP_Y0 + 9, 17, GuiFx.fade(TEXT_BRIGHT, a), false);
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
                        if (failure) {
                            failure = false;
                            message = "";
                            stage = CrateStage.idle();
                        }
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
            CrateOpenRibbonRenderer.textCentered(g, font, label, (x0 + x1) / 2,
                    (y0 + y1) / 2 + lift - size * 0.5F, size,
                    GuiFx.fade(enabled ? 0xFFFFFFFF : 0xFFBDBDBD, opacity), true);
        }
    }

    // =====================================================================
    // 输入与开箱控制
    // =====================================================================

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0 || departAt >= 0) return true;
        float cx = (float) ((x - canvasX) / unit), cy = (float) ((y - canvasY) / unit);
        keyboardFocus = -1;
        buildHotspots(now());
        for (Hotspot hotspot : hotspots) {
            if (hotspot.contains(cx, cy)) {
                CrateOpenStageEffects.sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.25F);
                hotspot.action.run();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256) {
            onClose();
            return true;
        }
        if (key == 32) {
            boolean fresh = !spaceDown;
            spaceDown = true;
            if (fresh && canSkip(now())) {
                skipHeldSince = now();
                return true;
            }
            if (!fresh || skipHeldSince >= 0 || skipQueued) return true;
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

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        if (key == 32) {
            spaceDown = false;
            skipHeldSince = -1;
            return true;
        }
        return super.keyReleased(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
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

    private void beginOpen() {
        long time = now();
        boolean finished = stage.hasResult() && stage.finished(time);
        if (stage.active() && !finished && !failure) return;
        if (!readyToOpen()) return;
        if (CrateClientNetwork.connected() && !CrateClientNetwork.canOpen()) {
            fail("crates.client_outdated");
            return;
        }
        selectedKey = matchingKeyId();
        keyboardFocus = -1;
        stage = CrateStage.opened(time);
        if (modalShownAt < 0) modalShownAt = time - 260;
        result = null;
        rewards = List.of();
        cachedSummaryRewardsRef = null;
        resultStack = ItemStack.EMPTY;
        resultSlot = -1;
        failure = false;
        message = "";
        modalCancelled = false;
        skipHeldSince = -1;
        skipQueued = false;
        effects.resetForOpen();
        buildReel();
        buildStrip();
        activeOpenId = CrateClientNetwork.open(crateId, selectedKey);
        if (activeOpenId == null) fail("crates.pending");
    }

    private boolean canSkip(long time) {
        if (!stage.active() || failure || departAt >= 0 || skipQueued) return false;
        CrateStage.Phase phase = stage.phase(time);
        return phase == CrateStage.Phase.DISMISS || phase == CrateStage.Phase.HOLD
                || phase == CrateStage.Phase.CAROUSEL;
    }

    private float skipProgress(long time) {
        if (skipQueued) return 1.0F;
        return skipHeldSince < 0 ? 0.0F : CrateStage.clamp01((time - skipHeldSince) / (float) SKIP_HOLD_MS);
    }

    private void updateSkip(long time) {
        if (spaceDown || skipHeldSince >= 0) {
            boolean held = minecraft != null && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), 32);
            if (!held) spaceDown = false;
            if (skipHeldSince >= 0) {
                if (!held || !canSkip(time)) skipHeldSince = -1;
                else if (time - skipHeldSince >= SKIP_HOLD_MS) {
                    skipHeldSince = -1;
                    if (stage.hasResult()) applySkip(time);
                    else skipQueued = true;
                }
            }
        }
        float target = skipProgress(time);
        skipShown = target >= skipShown ? target : Math.max(target, skipShown - frameDelta / SKIP_DECAY_MS);
    }

    private void applySkip(long time) {
        skipQueued = false;
        if (failure || !stage.hasResult() || time >= stage.swapAt()) return;
        stage = stage.skipTo(stage.swapAt(), time);
        effects.markSkippedToSwap();
    }
}

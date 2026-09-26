package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.CrateNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reference-timed crate animation on a fitted 1920×1080 canvas.
 * Background, case, modal, strip, carousel, dark bridge and HUD have explicit depth layers.
 * Narrow windows keep the complete canvas and its hit targets visible.
 */
public final class CrateOpenScreen extends Screen {
    private static final String KEY = "screen.habitrain_lottery.crate.";

    // =====================================================================
    // 参考视频的基准画布
    // =====================================================================

    /** 参考视频分辨率。所有布局常量都写在这个空间里。 */
    private static final float REF_W = 1920.0F, REF_H = 1080.0F;

    // 分层
    private static final float MODAL_Z = 310.0F;
    private static final float SCENE_Z = 200.0F;
    private static final float STRIP_Z = 320.0F;
    private static final float CARD_Z = 420.0F;
    private static final float CURSOR_Z = 540.0F;
    private static final float UI_Z = 620.0F;
    private static final float HOTSPOT_Z = 660.0F;
    private static final float BRIDGE_Z = 600.0F;
    /** 暗场最浓时的强度：参考视频只把世界压到约 10%，不是纯黑。 */
    private static final float BRIDGE_MAX = 0.88F;

    // 顶部三行标题（参考 f050–f060 淡入、f348 起换成选中物品的名牌）
    private static final float HUD_TITLE_Y = 48.0F, HUD_UNLOCK_Y = 96.0F, HUD_NOTE_Y = 132.0F;

    // 底部物品条（参考 f074：x 40–1590、y 795–965）
    private static final float STRIP_X0 = 40.0F, STRIP_X1 = 1590.0F,
            STRIP_Y0 = 795.0F, STRIP_Y1 = 965.0F;
    private static final float STRIP_HEADER_Y = 800.0F, STRIP_INSPECT_X = 1815.0F;
    private static final float STRIP_CARD_X0 = 74.0F, STRIP_CARD_PITCH = 153.4F,
            STRIP_CARD_W = 134.0F, STRIP_WELL_Y0 = 845.0F, STRIP_WELL_Y1 = 955.0F,
            STRIP_CAPTION_Y = 960.0F;
    private static final int STRIP_SLOTS = 10;

    // 确认弹窗（参考 f104：772×204px）
    private static final float MODAL_X0 = 566.0F, MODAL_X1 = 1338.0F,
            MODAL_Y0 = 440.0F, MODAL_Y1 = 690.0F;
    private static final float MODAL_THUMB_X = 605.0F, MODAL_THUMB_Y = 385.0F,
            MODAL_THUMB_W = 240.0F, MODAL_THUMB_H = 180.0F;
    private static final float MODAL_TEXT_X = 895.0F, MODAL_TITLE_Y = 480.0F, MODAL_BODY_Y = 537.0F;
    private static final float MODAL_BTN_Y0 = 623.0F, MODAL_BTN_Y1 = 675.0F;
    private static final float MODAL_OK_X0 = 1105.0F, MODAL_OK_X1 = 1243.0F;
    private static final float MODAL_CANCEL_X0 = 1255.0F, MODAL_CANCEL_X1 = 1325.0F;

    // 转盘（参考 phase 10：卡片 360×280、间距 360、带宽 y 395–675、圆心 (960,505)）
    private static final float REEL_CENTER_X = REF_W * 0.5F, REEL_CENTER_Y = 535.0F;
    private static final float CARD_W = 360.0F, CARD_H = 280.0F;
    private static final float CURSOR_Y0 = 400.0F, CURSOR_Y1 = 660.0F;
    private static final float VIGNETTE_INNER = 400.0F, VIGNETTE_OUTER = 460.0F, VIGNETTE_ALPHA = 0.65F;
    private static final int GOLD_LINE = 0xFFE8D44D;

    // 展示页（参考 phase 13）
    private static final float BADGE_X = 600.0F, BADGE_Y = 30.0F, BADGE_SIZE = 86.0F;
    private static final float PLATE_X = 712.0F, PLATE_NAME_Y = 48.0F, PLATE_SUB_Y = 118.0F;
    private static final float RARITY_RULE_Y = 175.0F, RARITY_RULE_X0 = 578.0F, RARITY_RULE_X1 = 1345.0F;
    private static final float TIP_X = 520.0F, TIP_Y = 818.0F, TIP_LINE = 34.0F;
    private static final float TIP_HAIRLINE_X = 1390.0F, TIP_HAIRLINE_Y0 = 780.0F, TIP_HAIRLINE_Y1 = 980.0F;
    private static final float REVEAL_ITEM_X = 1005.0F, REVEAL_ITEM_Y = 570.0F;

    // 底部导航条（参考 y 1020–1080）
    private static final float NAV_Y0 = 1020.0F;
    private static final float[] NAV_ICON_X = {490.0F, 546.0F, 604.0F, 662.0F, 722.0F, 782.0F};
    private static final float CLOSE_X0 = 1390.0F, CLOSE_X1 = 1470.0F,
            CLOSE_Y0 = 1036.0F, CLOSE_Y1 = 1064.0F;
    private static final float AGAIN_X0 = 1180.0F, AGAIN_X1 = 1340.0F,
            AGAIN_Y0 = 1036.0F, AGAIN_Y1 = 1064.0F;

    // 颜色（全部取自参考视频 §4 的实测表）
    private static final int TEXT_BRIGHT = 0xFFEDEDED, TEXT_TITLE = 0xFFF2F2F2,
            TEXT_BODY = 0xFFD2D2D2, TEXT_DIM = 0xFFCFCFCF, TEXT_MUTED = 0xFFB0B0B0,
            TEXT_STRIP_HEADER = 0xFFDDDDDD, TEXT_NAV = 0xFFEAEAEA,
            TEXT_GOLD = 0xFFE7D06A, SPECIAL_GOLD = 0xFFD9C93E,
            MODAL_GREEN = 0xFF4CAF50, DANGER = 0xFFFF8A8A,
            NAV_SCRIM = 0x73000000;

    private final Screen parent;
    private final String crateId;
    private String selectedKey = "";
    private Map<String, Double> inventory = Map.of();
    private int inventoryVersion = -1;

    private CrateStage stage = CrateStage.idle();
    private CrateNetwork.OpenResultS2C result;
    private ItemStack resultStack = ItemStack.EMPTY;
    private int resultSlot = -1;
    private List<ItemStack> reel = List.of();
    private List<SkinQuality> reelQuality = List.of();
    private List<ItemStack> strip = List.of();
    private List<SkinQuality> stripQuality = List.of();
    private String message = "";
    private boolean failure;
    private boolean modalCancelled;

    private long enteredAt = -1;
    private long departAt = -1;
    private boolean leaving;
    private long lastFrame;

    /** 画布缩放：GUI 单位 / 参考像素。每个渲染帧重新计算。 */
    private float unit = 1.0F;
    /** 画布在参考空间里的宽度；16:9 时正好是 1920。 */
    private float canvasW = REF_W;
    private float canvasX, canvasY;
    private int keyboardFocus = -1;
    /** 上一帧到这一帧的毫秒数，用于与帧率无关的趋近动画。 */
    private float frameDelta = 16.0F;

    private final List<Hotspot> hotspots = new ArrayList<>();

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
            // 否则 refreshFromState() 会因为版本没变而永远不填 inventory，
            // 箱子与钥匙数量就一直是 0（离线/无服务端时尤其明显）。
            inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
            inventory = CrateClientNetwork.STATE.inventory;
        }
        selectedKey = matchingKeyId();
        CrateClientNetwork.requestInventory();
        if (reel.isEmpty()) buildReel();
        if (strip.isEmpty()) buildStrip();
    }

    /** 背景只在 {@link #render} 里画一次，避免 {@code super.render} 重复叠一层世界模糊。 */
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        /* 由 render 显式调用 super.renderBackground。 */
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
        if (inventoryVersion == CrateClientNetwork.STATE.inventoryVersion) return;
        inventoryVersion = CrateClientNetwork.STATE.inventoryVersion;
        inventory = CrateClientNetwork.STATE.inventory;
        if (!stage.active()) message = "";
    }

    public void receive(CrateNetwork.OpenResultS2C payload) {
        if (!crateId.equals(payload.crateId())) return;
        if (!payload.success()) {
            fail(payload.message());
            return;
        }
        long now = now();
        if (!stage.active()) stage = CrateStage.opened(now - 1L);
        result = payload;
        stage = stage.result(now);
        resultStack = preview(payload.skinType(), payload.skin());
        SkinQuality quality = SkinQuality.fromId(payload.quality());
        // 结果固定落在「转盘停稳」那一刻光标正中的槽位：把中奖卡片换掉，
        // 玩家只会看到转盘停在这一件上，随后它从这个位置起飞到台前。
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
        stage = stage.active() ? stage.failure() : CrateStage.idle();
        if (!stage.active()) result = null;
    }

    private void finishLeaving() {
        if (leaving) return;
        leaving = true;
        if (minecraft != null) {
            Screen target = parent;
            // 在渲染中直接 setScreen 会打断当前帧，交给主线程队列更稳妥。
            minecraft.execute(() -> minecraft.setScreen(target));
        }
    }

    @Override public void onClose() {
        if (departAt >= 0) return;
        // 开盖与锁定过程中不允许半途离开；物品已经登场后就可以直接返回仓库。
        CrateStage.Phase phase = stage.phase(now());
        boolean locked = phase == CrateStage.Phase.DISMISS || phase == CrateStage.Phase.HOLD
                || phase == CrateStage.Phase.CAROUSEL || phase == CrateStage.Phase.BRIDGE;
        if (locked && !failure) {
            message = KEY + "busy";
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

    private SkinQuality resultQuality() {
        return result == null ? SkinQuality.WHITE : SkinQuality.fromId(result.quality());
    }

    private String crateName() {
        return Component.translatable(KEY + crateId).getString();
    }

    private String keyName() {
        return Component.translatable(KEY + "key." + crateId).getString();
    }

    private static ItemStack preview(String type, String id) {
        ItemStack stack = SkinItems.preview(type + "/" + id);
        if (!stack.isEmpty()) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                SkinWardrobeScreen.skinName(type, id));
        return stack.isEmpty() ? new ItemStack(Items.NETHER_STAR) : stack;
    }

    /** 该箱子会掉落的皮肤池；棱彩箱是全部品质，其余按主品质过滤。 */
    private List<SkinDefinition> pool() {
        CrateService.Definition definition = CrateService.definition(crateId);
        SkinQuality primary = definition == null ? SkinQuality.WHITE : definition.primary();
        boolean prism = definition != null && definition.prism();
        List<SkinDefinition> pool = new ArrayList<>();
        for (SkinDefinition skin : HabiSkinApi.registrations()) {
            if (prism || skin.quality() == primary) pool.add(skin);
        }
        if (pool.isEmpty()) pool.addAll(HabiSkinApi.registrations());
        return pool;
    }

    /**
     * 生成转盘上的候选物品。参考视频里转盘滚着好几件战利品、最后才锁定其中一件，
     * 因此这里用该箱子的皮肤池铺满 {@link CrateStage#SLOTS} 张卡片，
     * 结果到达后再替换到光标下的槽位。
     */
    private void buildReel() {
        List<SkinDefinition> pool = pool();
        List<ItemStack> out = new ArrayList<>();
        List<SkinQuality> quality = new ArrayList<>();
        if (!pool.isEmpty()) {
            int size = pool.size();
            int start = Math.floorMod(crateId.hashCode(), size);
            for (int i = 0; i < CrateStage.SLOTS; i++) {
                // 7 与常见皮肤池大小互质，取样比顺序取更分散
                SkinDefinition skin = pool.get(Math.floorMod(start + i * 7, size));
                ItemStack stack = preview(skin.type(), skin.id());
                if (stack.isEmpty()) {
                    stack = filler(i);
                    quality.add(SkinQuality.WHITE);
                } else {
                    quality.add(skin.quality());
                }
                out.add(stack);
            }
        }
        while (out.size() < CrateStage.SLOTS) {
            quality.add(SkinQuality.WHITE);
            out.add(filler(out.size()));
        }
        reel = List.copyOf(out);
        reelQuality = List.copyOf(quality);
    }

    /**
     * 生成底部「开启并获得以下之一」的十连物品条。
     * 前 9 张来自该箱子真实的皮肤池；第 10 张是参考视频里那张金色特殊卡
     * （{@code ★罕见的特殊物品★}），固定用池中最高品质的一件与金色条纹。
     */
    private void buildStrip() {
        List<SkinDefinition> pool = pool();
        List<ItemStack> out = new ArrayList<>();
        List<SkinQuality> quality = new ArrayList<>();
        int start = pool.isEmpty() ? 0 : Math.floorMod(crateId.hashCode() + 3, pool.size());
        for (int i = 0; i < STRIP_SLOTS - 1; i++) {
            if (pool.isEmpty()) {
                out.add(filler(i));
                quality.add(SkinQuality.WHITE);
                continue;
            }
            SkinDefinition skin = pool.get(Math.floorMod(start + i * 5, pool.size()));
            ItemStack stack = preview(skin.type(), skin.id());
            if (stack.isEmpty()) {
                stack = filler(i);
                quality.add(SkinQuality.WHITE);
            } else {
                quality.add(skin.quality());
            }
            out.add(stack);
        }
        SkinDefinition rarest = null;
        for (SkinDefinition skin : pool) {
            if (rarest == null || skin.quality().ordinal() > rarest.quality().ordinal()) rarest = skin;
        }
        ItemStack special = rarest == null ? new ItemStack(Items.NETHER_STAR)
                : SkinItems.preview(rarest.type() + "/" + rarest.id());
        out.add(special.isEmpty() ? filler(STRIP_SLOTS) : special);
        quality.add(SkinQuality.GOLD);
        strip = List.copyOf(out);
        stripQuality = List.copyOf(quality);
    }

    private static final ItemStack[] FILLERS = {
            new ItemStack(Items.IRON_SWORD), new ItemStack(Items.CROSSBOW), new ItemStack(Items.SNOWBALL),
            new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.AMETHYST_SHARD),
            new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.ECHO_SHARD), new ItemStack(Items.STICK)};

    private static ItemStack filler(int index) {
        return FILLERS[Math.floorMod(index, FILLERS.length)].copy();
    }

    private ItemStack stackAt(int index) {
        if (index < 0 || index >= reel.size()) return ItemStack.EMPTY;
        return reel.get(index);
    }

    private SkinQuality qualityAt(int index) {
        if (index < 0 || index >= reelQuality.size()) return SkinQuality.WHITE;
        return reelQuality.get(index);
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long time = now();
        frameDelta = Math.min(80.0F, Math.max(1.0F, time - lastFrame));
        lastFrame = time;
        g.flush();
        // A hard screen cut must not inherit the previous drawer's depth buffer.
        com.mojang.blaze3d.systems.RenderSystem.clear(256, net.minecraft.client.Minecraft.ON_OSX);
        // 世界模糊只应用一次（本类的 renderBackground 是空实现）。
        g.fill(0, 0, width, height, 0xFF171913);
        unit = Math.min(width / REF_W, height / REF_H);
        canvasW = REF_W;
        canvasX = (width - REF_W * unit) * .5F;
        canvasY = (height - REF_H * unit) * .5F;
        g.pose().pushPose();
        g.pose().translate(canvasX, canvasY, 0);
        g.pose().scale(unit, unit, 1.0F);
        renderCanvas(g, time, (mx - canvasX) / unit, (my - canvasY) / unit);
        g.pose().popPose();

        if (departAt >= 0) finishLeaving();
    }

    private void renderCanvas(GuiGraphics g, long time, float mx, float my) {
        float focus = ScreenSwap.arrive(time, enteredAt);
        float reveal = CrateStage.clamp01(stage.revealing(time));
        float nameplate = stage.cardsGone(time) ? 1.0F : 0.0F;
        boolean cardsVisible = stage.openedAt() >= 0 && !stage.cardsGone(time) && !failure;

        // ---- 1. 背景场景 ----
        // 参考视频的「世界」是一处暖色石砌庭院；这里用同一套暖调自绘场景，
        // 保持在 0.94 的不透明度上，让真实世界的模糊画面只透出一点点。
        CrateArt.backdrop(g, (int) canvasW, (int) REF_H, focus,
                CrateStage.easeInOutCubic(CrateStage.progress(time, enteredAt + 1000, 1966)),
                CrateStage.easeOutCubic(reveal));

        // ---- 2. 合焦薄雾：硬切之后的 533ms ----


        // ---- 4. 场景（箱子 + 接地阴影）----
        // 入场动作取自参考视频：箱子从画面上缘线性落下，同时从 65° 侧倾、−40° 偏航自行扶正。
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, SCENE_Z);
        renderScene(g, time, reveal);
        g.pose().popPose();

        g.flush();
        g.pose().pushPose();
        g.pose().translate(0, 0, MODAL_Z);
        renderModal(g, time);
        g.pose().popPose();
        g.flush();

        // ---- 4b. 底部十连物品条 ----
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, STRIP_Z);
        renderStrip(g, time);
        g.pose().popPose();

        // ---- 5. 卡片转盘（只在开盖之后）----
        if (cardsVisible) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, CARD_Z);
            renderReel(g, time, reveal);
            g.pose().popPose();
        }

        // ---- 6. 暗场：内容互换（转盘 → 展示）----
        // 参考视频在这一帧把卡片与圆形暗角一起撤掉、把世界压到约 10%，
        // 但顶部名牌与说明文字仍然可读，所以暗场画在 chrome 之下。
        float bridge = stage.bridge(time);
        if (bridge > 0.01F) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, BRIDGE_Z);
            CrateArt.bridge(g, (int) canvasW, (int) REF_H, ScreenSwap.BRIDGE_COLOR,
                    BRIDGE_MAX * bridge);
            g.pose().popPose();
        }

        // ---- 7. 界面 chrome ----
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, UI_Z);
        renderChrome(g, time, nameplate, reveal);
        g.pose().popPose();

        // ---- 8. 按钮 ----
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, HOTSPOT_Z);
        buildHotspots(time);
        for (Hotspot hotspot : hotspots) hotspot.render(g, font, mx, my, frameDelta);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------------
    // 场景
    // ---------------------------------------------------------------------

    private void renderScene(GuiGraphics g, long time, float reveal) {
        // 参考视频 f344（暗场那一帧）起箱子永久消失，只剩展台上的物品。
        if (!stage.cardsGone(time)) {
            renderCrate(g, time, reveal);
        }
        // ---- 选中物品：暗场之后从卡片位置升到台前并放大 ----
        if (reveal > 0.0F && resultSlot >= 0) {
            renderRevealItem(g, time, reveal);
        }
    }

    private void renderCrate(GuiGraphics g, long time, float reveal) {
        float bandCenter = REF_H * CrateStage.CARD_BAND_CENTER;
        // 箱子底面：参考视频里落位后箱体中心约在 (967, 603)，高约 640px
        float width = 330.0F * stage.crateScale(time, enteredAt);
        float baseY = 570.0F + (width - 330.0F) * .67F;
        if (stage.phase(time) == CrateStage.Phase.CAROUSEL) baseY = 745.0F;
        float centreX = canvasW * 0.5F;

        float drop = -REF_H * 1.15F * (1.0F - CrateStage.dropProgress(time, enteredAt));
        float roll = CrateStage.entryRoll(time, enteredAt);
        float yaw = CrateStage.entryYaw(time, enteredAt);
        float lid = stage.lidAngle(time);
        float open = CrateStage.clamp01(lid / 100.0F);

        if (open < .01F && drop > -1) {
            CrateArt.groundShadow(g, centreX, baseY + width * .03F, width,
                    .7F * CrateStage.righting(time, enteredAt));
        }

        g.pose().pushPose();
        g.pose().translate(centreX, baseY + drop, 0.0F);
        if (Math.abs(roll) > 0.01F) g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(roll));
        if (Math.abs(yaw) > 0.01F) {
            g.pose().scale(Math.max(0.35F, (float) Math.cos(Math.toRadians(yaw))), 1.0F, 1.0F);
        }
        g.pose().translate(-centreX, -baseY, 0.0F);

        // Shadow belongs to the ground, not the rotating model.
        CrateArt.crate(g, centreX, baseY, width, lid, accent(), open * (1.0F - 0.55F * reveal));
        g.pose().popPose();
    }

    /** 选中物品从卡片位置起飞、放大到占据 0.31 屏高，随后只剩呼吸。 */
    private void renderRevealItem(GuiGraphics g, long time, float reveal) {
        float morph = CrateStage.easeOutCubic(reveal);
        float settled = CrateStage.clamp01((reveal - 0.85F) / 0.15F);

        float cardW = cardWidth();
        float cardH = cardW * CrateStage.CARD_ASPECT;
        float fromX = REEL_CENTER_X;
        float fromY = REEL_CENTER_Y;
        float fromScale = Math.min(cardW, cardH) * 0.88F / 16.0F;
        float stageScale = REF_H * CrateStage.REVEAL_SIZE_RATIO / 16.0F;

        float px = CrateStage.lerp(fromX, REVEAL_ITEM_X, morph);
        float py = CrateStage.lerp(fromY, REVEAL_ITEM_Y, morph);
        float size = CrateStage.lerp(fromScale, stageScale, morph);
        // 出场时从侧面转正（实测偏航收拢约 25°），随后极缓慢地呼吸
        float idlePhase = (time - Math.max(0L, stage.revealAt())) / 1000.0F;
        float yaw = CrateStage.lerp(-CrateStage.REVEAL_YAW, 0.0F, morph)
                + 2.0F * (float) Math.sin(idlePhase * Math.PI) * settled;
        float roll = 1.5F * (float) Math.sin(idlePhase * Math.PI) * settled;
        py += 4.0F * (float) Math.sin(idlePhase * Math.PI) * settled;

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 20.0F);
        CrateArt.item(g, resultStack, px, py, size, yaw, roll, 1.0F);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------------
    // 底部十连物品条
    // ---------------------------------------------------------------------

    private void renderStrip(GuiGraphics g, long time) {
        if (stage.openedAt() >= 0 && stage.dismiss(time) >= 1.0F) return;
        float enter = CrateStage.stripIn(time, enteredAt);
        float dismiss = stage.dismiss(time);
        float alpha = enter * (1.0F - dismiss);
        if (alpha <= 0.02F) return;
        float rise = -CrateStage.stripOffset(time, enteredAt);
        float right = Math.min(STRIP_X1, canvasW - STRIP_X0);

        CrateArt.stripPanel(g, (int) STRIP_X0, (int) (STRIP_Y0 - rise), (int) right,
                (int) (STRIP_Y1 - rise), alpha);

        // 表头：参考视频居中于屏幕、字距 2px
        textSpaced(g, Component.translatable(KEY + "strip.header"), canvasW * 0.5F, STRIP_HEADER_Y - rise,
                20.0F, 2.0F, GuiFx.fade(TEXT_STRIP_HEADER, alpha), true);
        textRight(g, Component.translatable(KEY + "strip.inspect"),
                Math.min(STRIP_INSPECT_X, canvasW - 10.0F), STRIP_HEADER_Y - rise,
                20.0F, GuiFx.fade(TEXT_DIM, alpha), true);

        // 卡片：参考视频里退场时会以左端为轴横向压扁到 0.72
        float collapse = 1.0F - (1.0F - CrateStage.STRIP_COLLAPSE) * CrateStage.easeOutCubic(dismiss);
        g.pose().pushPose();
        g.pose().translate(STRIP_CARD_X0, 0.0F, 0.0F);
        g.pose().scale(collapse, 1.0F, 1.0F);
        g.pose().translate(-STRIP_CARD_X0, 0.0F, 0.0F);
        for (int i = 0; i < STRIP_SLOTS && i < strip.size(); i++) {
            float x = STRIP_CARD_X0 + STRIP_CARD_PITCH * i;
            if (x + STRIP_CARD_W > canvasW) break;
            int stripe = i == STRIP_SLOTS - 1 ? SPECIAL_GOLD : stripQuality.get(i).color();
            CrateArt.card(g, x, STRIP_WELL_Y0 - rise, STRIP_CARD_W, STRIP_WELL_Y1 - STRIP_WELL_Y0,
                    1.0F, 0.0F, stripe, alpha);
            float artScale = Math.min(STRIP_CARD_W, STRIP_WELL_Y1 - STRIP_WELL_Y0) * 0.86F / 16.0F;
            boolean special = i == STRIP_SLOTS - 1;
            if (special) {
                CrateArt.specialMedallion(g,x,STRIP_WELL_Y0-rise,STRIP_CARD_W,STRIP_WELL_Y1-STRIP_WELL_Y0,alpha);
                textCentered(g,Component.literal("?"),x+STRIP_CARD_W*.5F,STRIP_WELL_Y0-rise+22,48,GuiFx.fade(TEXT_GOLD,alpha),false);
            } else {
                CrateArt.item(g, strip.get(i), x + STRIP_CARD_W * 0.5F,
                        (STRIP_WELL_Y0 + STRIP_WELL_Y1) * 0.5F - rise - 3.0F, artScale, 0.0F, 0.0F, 1.0F, alpha);
            }
            Component caption = special
                    ? Component.translatable(KEY + "strip.special")
                    : strip.get(i).getHoverName();
            textClipped(g, caption, x, STRIP_CAPTION_Y - rise, 16.0F, STRIP_CARD_PITCH * 0.94F,
                    GuiFx.fade(special ? TEXT_GOLD : 0xFFE6E6E6, alpha), false);
            if (!special) {
                textClipped(g, Component.translatable(stripQuality.get(i).translationKey()),
                        x, STRIP_CAPTION_Y - rise + textScale(15.0F) * font.lineHeight * 1.15F,
                        15.0F, STRIP_CARD_PITCH * 0.94F, GuiFx.fade(TEXT_MUTED, alpha), false);
            }
        }
        g.pose().popPose();
    }

    // ---------------------------------------------------------------------
    // 确认弹窗
    // ---------------------------------------------------------------------

    private void renderModal(GuiGraphics g, long time) {
        float alpha = CrateStage.modalIn(time, enteredAt) * (1.0F - stage.dismiss(time));
        if (modalCancelled && !stage.active()) alpha = 0.0F;
        if (failure) alpha = Math.min(alpha, 1.0F - CrateStage.easeOutCubic(stage.dismiss(time)));
        if (alpha <= 0.02F) return;
        // 参考视频在弹窗下面压了一层约 18% 的整屏黑
        g.fill(0, 0, (int) canvasW, (int) REF_H, GuiFx.fade(0x2E000000, alpha));
        CrateArt.modalPanel(g, (int) MODAL_X0, (int) MODAL_Y0, (int) MODAL_X1, (int) MODAL_Y1, alpha);

        // 左缩略图：150×120 的箱子
        CrateArt.crate(g, MODAL_THUMB_X + MODAL_THUMB_W * 0.5F, MODAL_THUMB_Y + MODAL_THUMB_H,
                MODAL_THUMB_W * 0.92F, 0.0F, accent(), 0.0F);

        textLeft(g, Component.translatable(KEY + "modal.title", crateName()), MODAL_TEXT_X,
                MODAL_TITLE_Y, 26.0F, GuiFx.fade(TEXT_TITLE, alpha), true);
        textLeft(g, Component.translatable(KEY + "modal.body", crateName()), MODAL_TEXT_X,
                MODAL_BODY_Y, 17.0F, GuiFx.fade(TEXT_BODY, alpha), true);
    }

    // ---------------------------------------------------------------------
    // 卡片转盘
    // ---------------------------------------------------------------------

    private float cardWidth() {
        return Math.min(CARD_W, canvasW * CrateStage.CARD_WIDTH_RATIO);
    }

    private void renderReel(GuiGraphics g, long time, float reveal) {
        float entry = stage.wipe(time);
        if (entry <= 0.02F) return;
        float cardW = cardWidth();
        float cardH = cardW * CrateStage.CARD_ASPECT;
        float cx = canvasW * 0.5F;
        float cy = REEL_CENTER_Y;
        float pitch = cardW * CrateStage.CARD_PITCH;
        int winner = resultSlot;

        g.enableScissor((int)(canvasX + (cx - canvasW * .5F * entry) * unit),
                (int)(canvasY + 370 * unit),
                (int)(canvasX + (cx + canvasW * .5F * entry) * unit),
                (int)(canvasY + 680 * unit));
        g.pose().pushPose();
        // 卡片按「由黄线向两侧擦入」入场：偏移量随擦入进度展开
        for (int index = CrateStage.SLOTS - 1; index >= 0; index--) {
            ItemStack stack = stackAt(index);
            if (stack.isEmpty()) continue;
            float offset = stage.reelOffset(time, index);
            float distance = Math.abs(offset);
            if (distance > CrateStage.VISIBLE_SPAN + 0.6F) continue;
            boolean chosen = index == winner && stage.hasResult() && reveal > 0.0F;
            float scale = CrateStage.cardScale(offset);
            float tint = CrateStage.cardTint(offset);
            float haze = CrateStage.cardHaze(offset) * entry;
            float w = cardW * scale;
            float h = cardH * scale;
            float x = cx + offset * pitch - w * 0.5F;
            float y = cy - h * 0.5F;

            CrateArt.card(g, x, y, w, h, tint, haze, qualityAt(index).color(), entry);
            if (!chosen) {
                float roll = 0.0F;
                // 皮肤美术在参考视频里横向占满卡片；Minecraft 的物品图是正方形，
                // 因此按较短边适配，保证完整落在井内而不是溢出到相邻卡片上。
                CrateArt.item(g, stack, x + w * 0.5F, y + h * 0.46F,
                        Math.min(w, h) * 0.88F / 16.0F, 0.0F, roll, tint, entry);
            }
        }
        g.pose().popPose();

        g.flush();
        g.disableScissor();
        // 圆形暗角：内径 400px 通透、460px 处压到 α0.65，把视线锁在黄线附近
        float close = stage.vignette(time);
        g.pose().pushPose();
        g.pose().translate(0, 0, 160);
        CrateArt.circleVignette(g, (int) canvasW, (int) REF_H, cx, 505.0F,
                VIGNETTE_INNER, VIGNETTE_OUTER, 0xFF000000, VIGNETTE_ALPHA * close * entry);
        g.pose().popPose();

        // 固定黄线
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, CURSOR_Z);
        CrateArt.cursor(g, cx, CURSOR_Y0, CURSOR_Y1, entry);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------------
    // 界面 chrome
    // ---------------------------------------------------------------------

    private void renderChrome(GuiGraphics g, long time, float nameplate, float reveal) {
        float enter = ScreenSwap.arriveFade(ScreenSwap.arrive(time, enteredAt));
        float dismiss = CrateStage.easeOutCubic(stage.dismiss(time));
        // 入场标题块与「使用钥匙」行会被确认动作淡出；底部导航条属于展示页，
        // 不吃 dismiss，否则它会在开盖的那一刻永久消失。
        float entryHud = enter * (1.0F - nameplate);
        float page = enter;

        // ---- 顶部三行标题（参考 f050–f060 / 展示页换成名牌）----
        if (entryHud > 0.02F) {
            textCentered(g, Component.translatable(KEY + "hud.title"), canvasW * 0.5F, HUD_TITLE_Y,
                    34.0F, GuiFx.fade(TEXT_BRIGHT, entryHud), true);
            textCentered(g, Component.translatable(KEY + "hud.unlock", crateName()), canvasW * 0.5F,
                    HUD_UNLOCK_Y, 21.0F, GuiFx.fade(0xFFD8D8D8, entryHud), true);
            textCentered(g, Component.translatable(KEY + "hud.note", crateCount(), keyCount()),
                    canvasW * 0.5F, HUD_NOTE_Y, 18.0F, GuiFx.fade(TEXT_DIM, entryHud), true);
        }
        if (nameplate > 0.02F) renderPlate(g, nameplate);

        // ---- 底部导航条 ----
        // 参考视频在前半段（开箱流程）是「使用钥匙 / 开启 / 关闭」的窄条，
        // 到展示页才换成六个白色图标 + 关闭。这里按 nameplate 切换。
        g.fill(0, (int) NAV_Y0, (int) canvasW, (int) REF_H, GuiFx.fade(NAV_SCRIM, page));
        g.fill(0, (int) NAV_Y0, (int) canvasW, (int) NAV_Y0 + 1, GuiFx.fade(0x2EFFFFFF, page));
        if (nameplate > 0.02F) {
            for (int i = 0; i < NAV_ICON_X.length; i++) {
                navIcon(g, NAV_ICON_X[i], NAV_Y0 + 12.0F, i, page * nameplate);
            }
            // 第一个图标是当前页
            g.fill((int) NAV_ICON_X[0], (int) (REF_H - 6.0F), (int) (NAV_ICON_X[0] + 26.0F),
                    (int) (REF_H - 3.0F), GuiFx.fade(0xFFFFFFFF, page * nameplate));
        }

        // ---- 底部左侧：使用中的钥匙（参考「使用 反恐精英武器箱钥匙」）----
        if (entryHud > 0.02F) {
            int color = keyCount() <= 0 ? DANGER : TEXT_NAV;
            textLeft(g, Component.translatable(KEY + "key_use", keyName(), keyCount()), 570.0F,
                    1034.0F, 20.0F, GuiFx.fade(color, entryHud), true);
        }

        // ---- 失败提示 ----
        CrateStage.Phase phase = stage.phase(time);
        if (failure && !message.isEmpty() && stage.failedVisible(time)) {
            textCentered(g, Component.translatable(message), canvasW * 0.5F, 640.0F, 22.0F,
                    GuiFx.fade(DANGER, 1.0F), true);

        } else if (!stage.active() && crateCount() <= 0) {
            textCentered(g, Component.translatable(KEY + "missing_crate"), canvasW * 0.5F, 640.0F, 22.0F,
                    GuiFx.fade(DANGER, page), true);
        } else if (!stage.active() && keyCount() <= 0) {
            textCentered(g, Component.translatable(KEY + "missing_key"), canvasW * 0.5F, 640.0F, 22.0F,
                    GuiFx.fade(DANGER, page), true);
        }
    }

    /** 展示页的左上名牌：箱子徽标 + 物品名 + 收藏品行 + 品质横条。 */
    private void renderPlate(GuiGraphics g, float alpha) {
        SkinQuality quality = resultQuality();
        CrateArt.crateBadge(g, (int) BADGE_X, (int) BADGE_Y, (int) BADGE_SIZE, accent(), alpha);
        Component name = resultStack.isEmpty()
                ? Component.translatable(KEY + "skin_result", "") : resultStack.getHoverName();
        textLeft(g, name, PLATE_X, PLATE_NAME_Y, 34.0F, GuiFx.fade(TEXT_BRIGHT, alpha), true);
        textLeft(g, Component.translatable(KEY + "reveal.collection",
                        Component.translatable(quality.translationKey()).getString()),
                PLATE_X, PLATE_SUB_Y, 20.0F, GuiFx.fade(0xFFC9C9C9, alpha), true);
        CrateArt.rarityRule(g, (int) Math.min(RARITY_RULE_X0, canvasW - 10.0F), (int) RARITY_RULE_Y,
                (int) Math.min(RARITY_RULE_X1, canvasW - 10.0F), quality.color(), alpha);

        // ---- 说明文字块（参考 phase 13 的三段 body text）----
        float tipAlpha = GuiFx.clamp01((alpha - 0.4F) / 0.6F);
        if (tipAlpha <= 0.02F) return;
        g.fill((int) TIP_HAIRLINE_X, (int) TIP_HAIRLINE_Y0, (int) TIP_HAIRLINE_X + 1,
                (int) TIP_HAIRLINE_Y1, GuiFx.fade(0x40FFFFFF, tipAlpha));
        String[] lines = {
                Component.translatable(KEY + "reveal.tip1",
                        Component.translatable(quality.translationKey())).getString(),
                Component.translatable(KEY + "reveal.tip2", crateName(), keyName()).getString(),
                Component.translatable(KEY + "reveal.tip3", crateName()).getString()};
        int[] colors = {0xFFCFCFCF, 0xFFD98A4A, 0xFFCFCFCF};
        float tipWidth = Math.max(120.0F, TIP_HAIRLINE_X - TIP_X - 40.0F);
        float y = TIP_Y;
        for (int i = 0; i < lines.length; i++) {
            for (String line : wrap(lines[i], tipWidth, 19.0F)) {
                textLeft(g, Component.literal(line), TIP_X, y, 19.0F, GuiFx.fade(colors[i], tipAlpha), false);
                y += TIP_LINE;
            }
            y += TIP_LINE * 0.6F;
        }
    }

    /** 按参考空间里的像素宽度折行；CJK 逐字符测量，足够应付说明文字。 */
    private List<String> wrap(String text, float maxWidth, float size) {
        List<String> out = new ArrayList<>();
        float scale = textScale(size);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (line.length() > 0 && font.width(line.toString() + c) * scale > maxWidth) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** 底部导航条的六个白色图标：视线 / 手枪 / 奔跑 / ⓘ / 图片 / ⋮。 */
    private void navIcon(GuiGraphics g, float x, float y, int index, float alpha) {
        int white = GuiFx.fade(0xFFF0F0F0, alpha);
        int ix = (int) x, iy = (int) y;
        switch (index) {
            case 0 -> { // 眼睛
                g.fill(ix + 2, iy + 6, ix + 24, iy + 20, white);
                g.fill(ix + 8, iy + 9, ix + 18, iy + 17, GuiFx.fade(0xFF101010, alpha));
            }
            case 1 -> { // 手枪
                g.fill(ix + 2, iy + 8, ix + 24, iy + 14, white);
                g.fill(ix + 16, iy + 13, ix + 22, iy + 24, white);
            }
            case 2 -> { // 奔跑的人
                g.fill(ix + 11, iy + 2, ix + 17, iy + 8, white);
                g.fill(ix + 9, iy + 9, ix + 19, iy + 17, white);
                g.fill(ix + 6, iy + 17, ix + 12, iy + 26, white);
                g.fill(ix + 16, iy + 17, ix + 22, iy + 26, white);
            }
            case 3 -> { // ⓘ
                g.fill(ix + 4, iy + 4, ix + 22, iy + 6, white);
                g.fill(ix + 4, iy + 20, ix + 22, iy + 22, white);
                g.fill(ix + 4, iy + 4, ix + 6, iy + 22, white);
                g.fill(ix + 20, iy + 4, ix + 22, iy + 22, white);
                g.fill(ix + 12, iy + 8, ix + 14, iy + 12, white);
                g.fill(ix + 12, iy + 14, ix + 14, iy + 20, white);
            }
            case 4 -> { // 图片
                g.fill(ix + 2, iy + 4, ix + 24, iy + 24, white);
                g.fill(ix + 5, iy + 7, ix + 21, iy + 21, GuiFx.fade(0xFF101010, alpha));
                g.fill(ix + 6, iy + 18, ix + 13, iy + 20, white);
                g.fill(ix + 10, iy + 13, ix + 20, iy + 20, white);
            }
            default -> { // ⋮
                g.fill(ix + 11, iy + 3, ix + 15, iy + 7, white);
                g.fill(ix + 11, iy + 11, ix + 15, iy + 15, white);
                g.fill(ix + 11, iy + 19, ix + 15, iy + 23, white);
            }
        }
    }

    // =====================================================================
    // 文字工具（在参考空间里按像素字号绘制）
    // =====================================================================

    private float textScale(float size) {
        // The canvas already applies the window/GUI scale exactly once.
        return size / font.lineHeight;
    }

    private void textAt(GuiGraphics g, Component text, float x, float y, float size, int color, boolean shadow) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(textScale(size), textScale(size), 1.0F);
        g.drawString(font, text, 0, 0, color, shadow);
        g.pose().popPose();
    }

    /** 左对齐并截断到 {@code maxWidth} 参考像素，用于卡片下方那两行窄说明。 */
    private void textClipped(GuiGraphics g, Component text, float x, float y, float size,
                             float maxWidth, int color, boolean shadow) {
        float scale = textScale(size);
        int allowed = Math.max(1, (int) (maxWidth / scale));
        String shown = font.plainSubstrByWidth(text.getString(), allowed);
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, shown, 0, 0, color, shadow);
        g.pose().popPose();
    }

    private float textWidth(Component text, float size) { return font.width(text) * textScale(size); }

    private void textCentered(GuiGraphics g, Component text, float centerX, float y, float size,
                              int color, boolean shadow) {
        textAt(g, text, centerX - textWidth(text, size) * 0.5F, y, size, color, shadow);
    }

    private void textLeft(GuiGraphics g, Component text, float x, float y, float size,
                          int color, boolean shadow) {
        textAt(g, text, x, y, size, color, shadow);
    }

    private void textRight(GuiGraphics g, Component text, float right, float y, float size,
                           int color, boolean shadow) {
        textAt(g, text, right - textWidth(text, size), y, size, color, shadow);
    }

    /** 带字距的居中文字：用来还原参考视频表头那 2px 的 letter-spacing。 */
    private void textSpaced(GuiGraphics g, Component text, float centerX, float y, float size,
                            float spacing, int color, boolean shadow) {
        String raw = text.getString();
        float scale = textScale(size);
        float width = (font.width(raw) + spacing * Math.max(0, raw.length() - 1)) * scale;
        float x = centerX - width * 0.5F;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
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
        // 对应参考视频的确认弹窗：有对应钥匙即可直接点击“开启并保留”，
        // 不要求玩家先点一次底部钥匙文字；selectedKey 只作为网络请求的明确参数。
        boolean ready = crateCount() > 0 && keyCount() > 0;
        boolean finished = stage.finished(time);

        // 弹窗按钮（画在箱子之前，但热区统一处理）
        float modalAlpha = CrateStage.modalIn(time, enteredAt) * (1.0F - stage.dismiss(time));
        if ((idle || phase == CrateStage.Phase.DISMISS) && !modalCancelled && modalAlpha > 0.02F) {
            hotspots.add(new Hotspot(MODAL_OK_X0, MODAL_BTN_Y0, MODAL_OK_X1 - MODAL_OK_X0,
                    MODAL_BTN_Y1 - MODAL_BTN_Y0, Component.translatable(KEY + "modal.confirm"),
                    Kind.MODAL_PRIMARY, this::beginOpen).enabled(ready && idle).opacity(modalAlpha));
            hotspots.add(new Hotspot(MODAL_CANCEL_X0, MODAL_BTN_Y0, MODAL_CANCEL_X1 - MODAL_CANCEL_X0,
                    MODAL_BTN_Y1 - MODAL_BTN_Y0, Component.translatable(KEY + "modal.cancel"),
                    Kind.MODAL_GHOST, () -> modalCancelled = true).enabled(idle).opacity(modalAlpha));
        }
        // 取消之后重新叫出弹窗
        if (idle && (modalCancelled || failure)) {
            hotspots.add(new Hotspot(AGAIN_X0, AGAIN_Y0, AGAIN_X1 - AGAIN_X0, AGAIN_Y1 - AGAIN_Y0,
                    Component.translatable(KEY + "open"), Kind.TEXT, () -> {
                        modalCancelled = false;
                        if (failure) { failure = false; message = ""; stage = CrateStage.idle(); }
                    }));
        }
        // 钥匙：点击选中对应钥匙
        if (idle && keyCount() > 0 && !selectedKey.equals(matchingKeyId())) {
            hotspots.add(new Hotspot(STRIP_X0, 1026.0F, 340.0F, 30.0F,
                    Component.translatable(KEY + "key_use", keyName(), keyCount()),
                    Kind.TEXT, () -> selectedKey = matchingKeyId()));
        }
        // 展示结束后可以再来一次
        if (phase == CrateStage.Phase.REVEAL && finished) {
            hotspots.add(new Hotspot(AGAIN_X0, AGAIN_Y0, AGAIN_X1 - AGAIN_X0, AGAIN_Y1 - AGAIN_Y0,
                    Component.translatable(KEY + "again"), Kind.TEXT, this::beginOpen));
        }
        // 关闭：任何未锁定的时刻都能返回仓库
        boolean locked = phase == CrateStage.Phase.DISMISS || phase == CrateStage.Phase.HOLD
                || phase == CrateStage.Phase.CAROUSEL || phase == CrateStage.Phase.BRIDGE;
        if (!locked || failure) {
            hotspots.add(new Hotspot(CLOSE_X0, CLOSE_Y0, CLOSE_X1 - CLOSE_X0, CLOSE_Y1 - CLOSE_Y0,
                    Component.translatable(KEY + "reveal.close"), Kind.TEXT, this::depart));
        }
    }


    private enum Kind { MODAL_PRIMARY, MODAL_GHOST, TEXT }

    /** 自绘热区：扁平芯片式按钮与纯文字按钮，支持悬停、按下反馈与键盘操作。 */
    private final class Hotspot {
        private final float x, y, w, h;
        private final Component label;
        private final Kind kind;
        private final Runnable action;
        private boolean enabled = true;
        private float opacity = 1;
        private float hover, press;

        Hotspot(float x, float y, float w, float h, Component label, Kind kind, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.kind = kind;
            this.action = action;
        }

        Hotspot enabled(boolean value) { this.enabled = value; return this; }
        Hotspot opacity(float value) { opacity = value; return this; }

        boolean contains(double mx, double my) {
            return enabled && opacity >= .4F && mx >= x && mx < x + w && my >= y && my < y + h;
        }

        void render(GuiGraphics g, Font font, float mx, float my, float delta) {
            boolean over = enabled && (mx >= x && mx < x + w && my >= y && my < y + h
                    || hotspots.indexOf(this) == keyboardFocus);
            hover = over ? 1.0F : 0.0F;
            press = GuiFx.approach(press, 0.0F, delta, 110.0F);
            float top = y + press;
            switch (kind) {
                case MODAL_PRIMARY -> {
                    CrateArt.primaryButton(g, (int) x, (int) top, (int) (x + w), (int) (top + h),
                            opacity * (enabled || stage.active() ? 1.0F : 0.45F), hover);
                    textCentered(g, label, x + w * 0.5F, top + h * 0.5F - 10.0F, 19.0F,
                            GuiFx.fade(enabled || stage.active() ? 0xFFFFFFFF : 0xFFBDBDBD, opacity), true);
                }
                case MODAL_GHOST -> {
                    CrateArt.ghostButton(g, (int) x, (int) top, (int) (x + w), (int) (top + h), opacity, hover);
                    textCentered(g, label, x + w * 0.5F, top + h * 0.5F - 10.0F, 19.0F, GuiFx.fade(0xFFE0E0E0, opacity), true);
                }
                default -> {
                    int color = enabled ? (over ? 0xFFFFFFFF : TEXT_NAV) : 0xFF6E7780;
                    textLeft(g, label, x, y, 20.0F, color, true);
                    if (over) {
                        float width = textWidth(label, 20.0F);
                        g.fill((int) x, (int) (y + 22.0F), (int) (x + width), (int) (y + 23.0F),
                                GuiFx.fade(0xFFFFFFFF, 0.7F));
                    }
                }
            }
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
                hotspot.press = 1.0F;
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
            } else if (CrateStage.modalIn(now(), enteredAt) >= 1 && !modalCancelled
                    || stage.finished(now())) {
                beginOpen();
            }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean charTyped(char c, int modifiers) { return true; }

    // =====================================================================
    // 开箱
    // =====================================================================

    private void beginOpen() {
        long time = now();
        boolean finished = stage.hasResult() && stage.finished(time);
        if (stage.active() && !finished && !failure) return;
        if (crateCount() <= 0 || keyCount() <= 0) return;
        // 每个箱子只有一把对应钥匙，因此直接选中它，不需要玩家再点一次。
        selectedKey = matchingKeyId();
        keyboardFocus = -1;
        stage = CrateStage.opened(time);
        result = null;
        resultStack = ItemStack.EMPTY;
        resultSlot = -1;
        failure = false;
        message = "";
        modalCancelled = false;
        buildReel();
        CrateClientNetwork.open(crateId, selectedKey);
    }
}

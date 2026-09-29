package com.habitrain.lottery.client;

import com.habitrain.lottery.client.gui.CrateFx;
import com.habitrain.lottery.client.gui.GuiFx;
import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.client.gui.WarehouseTheme;
import com.habitrain.lottery.network.WarehouseNetwork;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.BooleanSupplier;

/**
 * 全局「长按背包键打开账户仓库」。
 *
 * <p>背包键（默认 E）的点击在 {@code Minecraft#handleKeybinds} 里被
 * {@code WarehouseHoldKeyMixin} 转交给 {@link #filterInventoryClick}：</p>
 * <ul>
 *   <li>轻按（{@value #TAP_MS}ms 内松开）→ 放行一次点击，仍由原版（以及上游的受限背包替换）打开背包；</li>
 *   <li>按住满 {@value #HOLD_MS}ms → 打开账户仓库，准星下方有进度环指引；</li>
 *   <li>介于两者之间松开 → 取消，什么都不打开。</li>
 * </ul>
 * <p>服务端没有本模组（发不出仓库请求）时完全不拦截，背包键保持原版行为。</p>
 */
public final class WarehouseHoldKey {
    /** 轻按窗口：在此之前松开视为普通背包键。进度环也从这一刻起才出现。 */
    static final long TAP_MS = 250L;
    /** 按住多久打开仓库。 */
    static final long HOLD_MS = 2000L;
    private static final long FADE_IN_MS = 150L, CANCEL_MS = 320L;

    /** 本次按下的时刻；−1 表示没有在蓄力。 */
    private static long pressedAt = -1;
    /** 轻按松开后放行给原版的一次点击，只在当 tick 的 handleKeybinds 里有效。 */
    private static boolean releaseToVanilla;
    private static long cancelledAt = -1;
    private static float cancelledProgress;
    private static boolean registered;

    private WarehouseHoldKey() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        // START：先于同一 tick 里的 handleKeybinds，轻按松开的那一 tick 就能打开背包。
        ClientTickEvents.START_CLIENT_TICK.register(WarehouseHoldKey::tick);
        HudRenderCallback.EVENT.register(WarehouseHoldKey::render);
    }

    private static long now() { return Util.getMillis(); }

    private static boolean available(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        try {
            return ClientPlayNetworking.canSend(WarehouseNetwork.Request.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code handleKeybinds} 里 {@code options.keyInventory.consumeClick()} 的替身。 */
    public static boolean filterInventoryClick(BooleanSupplier original) {
        if (releaseToVanilla) {
            releaseToVanilla = false;
            return true;
        }
        if (!available(Minecraft.getInstance())) return original.getAsBoolean();
        // 蓄力期间的连发点击一并吞掉，只有第一次按下开始计时。
        if (original.getAsBoolean() && pressedAt < 0) {
            pressedAt = now();
            cancelledAt = -1;
        }
        return false;
    }

    private static void tick(Minecraft mc) {
        // 上一 tick 放行的点击如果没被 handleKeybinds 取走（比如恰好开了别的界面），就作废。
        releaseToVanilla = false;
        if (pressedAt < 0) return;
        if (mc.screen != null || !available(mc)) {
            pressedAt = -1;
            return;
        }
        long held = now() - pressedAt;
        if (!mc.options.keyInventory.isDown()) {
            if (held < TAP_MS) {
                releaseToVanilla = true;
            } else {
                cancelledAt = now();
                cancelledProgress = progress(held);
            }
            pressedAt = -1;
        } else if (held >= HOLD_MS) {
            pressedAt = -1;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENDER_CHEST_OPEN, 1.1F, 0.6F));
            mc.setScreen(new WarehouseScreen(null));
        }
    }

    private static float progress(long held) {
        return GuiFx.clamp01((held - TAP_MS) / (float) (HOLD_MS - TAP_MS));
    }

    // =====================================================================
    // 视觉指引：准星下方的进度环 + 仓库标签
    // =====================================================================

    private static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null) return;
        long time = now();
        float p, alpha, scale;
        boolean cancelled = false;
        if (pressedAt >= 0) {
            long held = time - pressedAt;
            if (held < TAP_MS) return;
            p = progress(held);
            alpha = GuiFx.easeOutCubic(GuiFx.clamp01((held - TAP_MS) / (float) FADE_IN_MS));
            scale = 0.85F + 0.15F * alpha;
        } else if (cancelledAt >= 0 && time - cancelledAt < CANCEL_MS) {
            float t = (time - cancelledAt) / (float) CANCEL_MS;
            p = cancelledProgress * (1 - GuiFx.easeOutCubic(t));
            alpha = 1 - t;
            scale = 1 - 0.12F * t;
            cancelled = true;
        } else {
            return;
        }

        float cx = g.guiWidth() / 2.0F, cy = g.guiHeight() / 2.0F + 34;
        float r = 13 * scale, w = 2.5F;
        int accent = WarehouseTheme.TEAL;

        // 底盘：暗色圆底 + 灰色轨道
        CrateFx.glow(g, cx, cy, r + 10, r + 10, 0xFF000000, 0.55F * alpha, false);
        CrateFx.arc(g, cx, cy, r - w, r, 0, (float) (Math.PI * 2), GuiFx.fade(0x40FFFFFF, alpha), false);
        // 进度弧：从正上方顺时针走，接近满格时外发光逐渐变强
        if (p > 0.001F) {
            int arcColor = cancelled ? GuiFx.fade(0xFFB0B8B6, alpha) : GuiFx.fade(accent, alpha);
            CrateFx.arc(g, cx, cy, r - w, r, (float) (-Math.PI / 2), (float) (Math.PI * 2 * p), arcColor, false);
            if (!cancelled) {
                double head = -Math.PI / 2 + Math.PI * 2 * p;
                CrateFx.glow(g, cx + (float) Math.cos(head) * (r - w / 2), cy + (float) Math.sin(head) * (r - w / 2),
                        5, 5, 0xFFFFFFFF, 0.8F * alpha, true);
                CrateFx.ring(g, cx, cy, r - w / 2, 3 + 3 * p, 1, accent, 0.35F * p * p * alpha);
            }
        }

        // 中心：末影箱图标，一眼区别于原版背包
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale * 0.75F, scale * 0.75F, 1);
        g.renderItem(new ItemStack(Items.ENDER_CHEST), -8, -8);
        g.pose().popPose();

        // 标签：标题 + 操作说明
        int textAlpha = Math.max(5, (int) (alpha * 255)) << 24;
        Component title = Component.translatable(cancelled ? "hud.habitrain_lottery.warehouse_hold.cancelled"
                : "hud.habitrain_lottery.warehouse_hold.title");
        g.drawCenteredString(mc.font, title, (int) cx, (int) (cy + r + 6),
                textAlpha | ((cancelled ? 0xB0B8B6 : accent) & 0xFFFFFF));
        if (!cancelled) {
            Component hint = Component.translatable("hud.habitrain_lottery.warehouse_hold.hint",
                    mc.options.keyInventory.getTranslatedKeyMessage());
            g.pose().pushPose();
            g.pose().translate(cx, cy + r + 17, 0);
            g.pose().scale(0.75F, 0.75F, 1);
            g.drawCenteredString(mc.font, hint, 0, 0, textAlpha | (WarehouseTheme.TEXT_DIM & 0xFFFFFF));
            g.pose().popPose();
        }
    }
}

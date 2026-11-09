package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.SkinQuality;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 开箱舞台时间轴声效、粒子爆发、震屏与背景/登场光效驱动器（从 {@link CrateOpenScreen} 拆出）。
 */
final class CrateOpenStageEffects {
    static final float REF_W = 1920.0F, REF_H = 1080.0F;
    static final float REEL_CY = 520.0F;
    static final float REVEAL_X = 960.0F, REVEAL_Y = 478.0F, PEDESTAL_Y = 712.0F;

    private final CrateParticles particles = new CrateParticles();
    private float emitClock;
    private boolean firedHold, firedOpen, firedStop, firedReveal, firedLanding;
    private int lastTickSlot = Integer.MIN_VALUE;
    private long lastTickAt;

    CrateParticles particles() {
        return particles;
    }

    void tickParticles(float frameDelta) {
        particles.tick(frameDelta);
    }

    void resetForOpen() {
        firedHold = firedOpen = firedStop = firedReveal = false;
        lastTickSlot = Integer.MIN_VALUE;
        particles.clear();
    }

    void markSkippedToSwap() {
        firedHold = firedOpen = firedStop = true;
        lastTickSlot = Integer.MIN_VALUE;
        particles.clear();
        sound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.25F, 0.35F);
    }

    static void sound(SoundEvent event, float pitch, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
        }
    }

    void driveEvents(long time, float frameDelta, long enteredAt, CrateStage stage,
                     boolean failure, int light, SkinQuality resultQuality, int rarityColor, float tier) {
        emitClock += frameDelta;
        boolean emit = emitClock >= 33;
        if (emit) emitClock = 0;
        CrateStage.Phase phase = stage.phase(time);
        float cx = REF_W * .5F;

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
            particles.burst(cx, REEL_CY, 30 + (int) (70 * tier), 200, 700, 120, rarityColor, true);
            particles.stars(cx, REEL_CY, 200, 130, 6 + (int) (10 * tier), rarityColor, true);
            sound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F + 0.5F * tier, 0.55F);
        }
        if (stage.hasResult() && !firedReveal && time >= stage.revealAt()) {
            firedReveal = true;
            particles.burst(REVEAL_X, REVEAL_Y, 60 + (int) (140 * tier), 260, 1100, 200, rarityColor, true);
            particles.stars(REVEAL_X, REVEAL_Y, 420, 260, 10 + (int) (22 * tier), rarityColor, true);
            if (resultQuality.ordinal() >= SkinQuality.GOLD.ordinal()) {
                particles.confetti(200, 1720, 0, 90, new int[]{rarityColor, 0xFFFFFFFF, CrateArt.GOLD_LINE,
                        GuiFx.shade(rarityColor, 0.3F), 0xFF57D5C8});
            }
            switch (resultQuality) {
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
            particles.embers(REVEAL_X - 260, REVEAL_X + 260, PEDESTAL_Y, tier > 0.4F ? 2 : 1, rarityColor);
            if (particles.random().nextFloat() < 0.15F + 0.35F * tier) {
                particles.stars(REVEAL_X, REVEAL_Y, 300, 220, 1, rarityColor, true);
            }
        }
    }

    /** 震屏：蓄力时越来越强，开盖时猛震，金 / 红登场时再震一次。 */
    static float[] shake(long time, CrateStage stage, boolean failure, float tier) {
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
            float strength = Math.max(0, tier - 0.5F) * 2;
            if (sinceReveal >= 0 && sinceReveal < 450) amp = Math.max(amp, 12 * strength * (1 - sinceReveal / 450F));
        }
        if (amp <= 0.05F) return new float[]{0, 0};
        return new float[]{(float) (Math.sin(time * 0.091) + Math.sin(time * 0.057)) * 0.5F * amp,
                (float) (Math.sin(time * 0.073 + 1.3) + Math.sin(time * 0.049)) * 0.5F * amp};
    }

    static void renderCourtyard(GuiGraphics g, long time, long enteredAt, CrateStage stage, boolean failure) {
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

    static void renderRevealStage(GuiGraphics g, int rarityColor) {
        CrateArt.backdrop(g, (int) REF_W, (int) REF_H, 1, 1, 1, 1);
        g.fill(0, 0, (int) REF_W, (int) REF_H, 0xC8040506);
        CrateFx.glow(g, REVEAL_X, REVEAL_Y, 1100, 700, GuiFx.mix(rarityColor, 0xFF000000, 0.35F), 0.55F, true);
        CrateFx.vGradient(g, 0, 0, REF_W, 260, 0xC0020303, 0x00020303, false);
        CrateFx.vGradient(g, 0, 760, REF_W, REF_H, 0x00020303, 0xE0020303, false);
        CrateFx.vignette(g, REF_W, REF_H, REVEAL_X, REVEAL_Y + 40, 620, 400, 1200, 760, 0xFF010202, 0.8F);
    }

    static void renderRevealBack(GuiGraphics g, long time, float reveal, int color, float t) {
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

    static void renderFlashes(GuiGraphics g, long time, CrateStage stage, boolean failure,
                              int crateLight, int rarityColor, float tier) {
        if (!stage.active() || failure) return;
        long sinceOpen = time - stage.spinAt();
        if (sinceOpen >= 0 && sinceOpen < 420) {
            float k = 1 - sinceOpen / 420F;
            CrateFx.flash(g, REF_W, REF_H, 0xFFFFF4DE, 0.30F * k * k * k);
            float r = CrateStage.easeOutCubic(sinceOpen / 420F);
            CrateFx.ring(g, REF_W / 2, 640, 80 + 1000 * r, 34, 0.4F, crateLight, 0.6F * k * k);
            CrateFx.streak(g, REF_W / 2, 660, 900 * (0.4F + 0.6F * r), 14, 0xFFFFFFFF, 0.7F * k);
        }
        if (!stage.hasResult()) return;
        long sinceReveal = time - stage.revealAt();
        if (sinceReveal >= 0 && sinceReveal < 700) {
            float k = 1 - sinceReveal / 700F;
            CrateFx.flash(g, REF_W, REF_H, GuiFx.mix(0xFFFFFFFF, rarityColor, 0.5F), (0.10F + 0.16F * tier) * k * k * k * k);
            float r = CrateStage.easeOutCubic(sinceReveal / 700F);
            CrateFx.ring(g, REVEAL_X, REVEAL_Y, 60 + (700 + 400 * tier) * r, 30 + 20 * tier, 0.8F, rarityColor, (0.35F + 0.4F * tier) * k * k);
            if (tier >= 0.5F) CrateFx.ring(g, REVEAL_X, REVEAL_Y, 40 + 700 * r, 22, 0.8F, 0xFFFFFFFF, 0.6F * k);
            CrateFx.streak(g, REVEAL_X, REVEAL_Y, 1100 * r, 18 * k + 4, rarityColor, 0.7F * k);
        }
    }
}

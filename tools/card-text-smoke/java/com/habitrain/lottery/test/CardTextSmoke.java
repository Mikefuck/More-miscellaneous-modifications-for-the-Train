package com.habitrain.lottery.test;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.*;
import com.habitrain.lottery.client.SkinClient;
import com.habitrain.lottery.client.gui.SkinWardrobeScreen;
import com.habitrain.lottery.skin.SkinNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Exercises the actual wardrobe renderer without changing a world or a player's assets. */
public final class CardTextSmoke implements ClientModInitializer, SkinRegistrar {
    private final List<String> checks = new ArrayList<>();
    private int ticks;
    private SkinWardrobeScreen screen;
    private boolean finished;

    @Override public void registerSkins() {
        for (String id : List.of("card_text_short", "card_text_long", "card_text_plain")) {
            HabiSkinApi.register(SkinDefinition.builder("knife", id, SkinQuality.BLUE)
                    .model("minecraft", "item/diamond_sword").build());
        }
        HabiSkinApi.register(SkinDefinition.builder("back", "card_text_short", SkinQuality.RED)
                .model("minecraft", "block/diamond_block").build());
        SkinCardText.register("knife", "card_text_short",
                Component.translatable("card_text_smoke.label").withColor(0xE9BD76));
        SkinCardText.register("knife", "card_text_long",
                Component.literal("本皮肤由 ").withColor(0x8AD7BC)
                        .append(Component.literal("示例模组提供且只显示在指定的皮肤卡片上").withColor(0xFF7777)));
    }

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (finished || mc.getOverlay() != null || mc.screen == null) return;
            try { tick(mc); }
            catch (Throwable error) {
                HabiLotteryMod.LOGGER.error("CARD_TEXT_SMOKE_FAILED", error);
                checks.add("FAIL " + error);
                finish(mc);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        switch (++ticks) {
            case 30 -> {
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn"); mc.reloadResourcePacks();
            }
            case 70 -> { size(mc, 960, 720, 2); wardrobe(mc); }
            case 95 -> {
                check(card("限定测试匕首").getMessage().getString().contains("活动限定"), "label-in-narration");
                check(!card("普通测试匕首").getMessage().getString().contains("活动限定"), "no-label-on-unselected-id");
                shot(mc, "01-card-text-normal");
            }
            case 96 -> { size(mc, 960, 720, 3); wardrobe(mc); }
            case 120 -> {
                check(mc.getWindow().getGuiScaledWidth() == 320 && mc.getWindow().getGuiScaledHeight() == 240,
                        "minimum-gui-320x240-render");
                shot(mc, "02-card-text-minimum");
            }
            case 121 -> {
                SkinCardText.unregister("knife", "card_text_short");
                SkinCardText.register("knife", "card_text_short", "运行时替换");
            }
            case 125 -> {
                check(card("限定测试匕首").getMessage().getString().contains("运行时替换"), "runtime-replacement-without-reopening");
                SkinCardText.unregister("knife", "card_text_short");
            }
            case 130 -> {
                check(!card("限定测试匕首").getMessage().getString().contains("运行时替换"), "runtime-removal-updates-narration");
                SkinCardText.register("knife", "card_text_short",
                        Component.translatable("card_text_smoke.label").withColor(0xE9BD76));
                size(mc, 1440, 900, 2); wardrobe(mc);
            }
            case 155 -> shot(mc, "03-card-text-wide");
            case 156 -> {
                mc.options.languageCode = "en_us";
                mc.getLanguageManager().setSelected("en_us"); mc.reloadResourcePacks();
                size(mc, 960, 720, 3); wardrobe(mc);
            }
            case 185 -> {
                check(card("Limited blade").getMessage().getString().contains("Limited event"), "translated-component-and-language-reload");
                shot(mc, "04-card-text-english-minimum");
            }
            case 186 -> {
                size(mc, 960, 640, 2);
                ((Button) screen.children().stream().filter(child -> child instanceof Button button
                        && button.getMessage().getString().equals("Back")).findFirst().orElseThrow()).onPress();
            }
            case 205 -> {
                check(!card("Back with same ID").getMessage().getString().contains("Limited event"), "same-id-other-type-not-labeled");
                shot(mc, "05-card-text-other-type");
            }
            case 220 -> { checks.add("PASS render-completed-without-exception"); finish(mc); }
        }
    }

    private Button card(String prefix) {
        return (Button) screen.children().stream().filter(child -> child instanceof Button button
                && button.getMessage().getString().startsWith(prefix + " · ")).findFirst().orElseThrow();
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private void wardrobe(Minecraft mc) throws Exception {
        var entries = new ArrayList<SkinNetwork.Entry>();
        for (String id : List.of("card_text_short", "card_text_long", "card_text_plain")) {
            entries.add(new SkinNetwork.Entry("knife", id, true, "card_text_short".equals(id), SkinQuality.BLUE));
        }
        entries.add(new SkinNetwork.Entry("back", "card_text_short", false, false, SkinQuality.RED));
        var field = SkinClient.class.getDeclaredField("entries"); field.setAccessible(true); field.set(null, entries);
        screen = new SkinWardrobeScreen(null);
        field(screen, "initialized", true); field(screen, "received", true); field(screen, "view3d", false);
        mc.setScreen(screen); screen.refresh();
    }

    private static void size(Minecraft mc, int width, int height, int scale) {
        mc.options.guiScale().set(scale);
        GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), width, height);
        mc.resizeDisplay();
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message ->
                HabiLotteryMod.LOGGER.info("CARD_TEXT_CAPTURE {} {}", name, message.getString()));
    }

    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks.add("PASS " + name);
    }

    private void finish(Minecraft mc) {
        finished = true;
        try {
            Files.write(mc.gameDirectory.toPath().resolve("card-text-smoke-results.txt"), checks);
        } catch (Exception error) { throw new RuntimeException(error); }
        HabiLotteryMod.LOGGER.info("CARD_TEXT_SMOKE_DONE {}", checks);
        mc.stop();
    }
}

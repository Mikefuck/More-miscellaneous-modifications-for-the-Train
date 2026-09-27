package com.habitrain.lottery.test;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.gui.CrateManageScreen;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real-client clicks, keyboard entry, resize checks and isolated server persistence round trips. */
final class RewardEditorSmoke {
    private static final Gson GSON = new Gson();
    private static final String UI = "screen.habitrain_lottery.reward_editor.";
    private static final String MANAGE = "screen.habitrain_lottery.crate_manage.";
    private final List<String> checks = new ArrayList<>();
    private final java.util.Map<Integer, String> shots = new java.util.LinkedHashMap<>();
    private CrateManageScreen screen;
    private Path output;
    private int captured = -1;
    private int resizeDelay, requestedScale;

    void tick(Minecraft mc, int tick) throws Exception {
        if (resizeDelay > 0 && --resizeDelay == 0) {
            mc.options.guiScale().set(requestedScale); mc.resizeDisplay();
            if (screen != null && mc.screen == screen) bounds();
            checks.add("PASS resolution " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                    + " requestedScale=" + requestedScale + " effective=" + mc.getWindow().getGuiScale());
        }
        switch (tick) {
            case 30 -> {
                mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn"); mc.reloadResourcePacks();
            }
            case 50 -> resize(mc, 1280, 720, 2);
            case 70 -> {
                output = mc.gameDirectory.toPath().resolve(System.getProperty("crateSmoke.out", "reward-checks"));
                Files.createDirectories(output);
                WorldLotteryPaths.initForTests(Files.createTempDirectory(output, "server-state-"));
                CrateService.onServerStarted();
                var state = GSON.fromJson(CrateService.configJson(), CrateService.State.class);
                check(state.crates.values().stream().allMatch(p -> p.customPool && !p.enabled && p.skinWeights.isEmpty() && p.extraRewards.isEmpty()), "fresh-server-empty-pools");
                var p = state.crates.get("gilded");
                p.name = "庆典补给箱"; p.keyName = "庆典钥匙"; p.enabled = true; p.rollCount = 3; p.minimumSkinCount = 0;
                p.skinWeights.put("knife/capture_0", 30); p.skinWeights.put("revolver/capture_1", 20);
                p.skinWeights.put("revolver/capture_2", 10); p.skinWeights.put("knife/capture_3", 10);
                var apples = new CrateService.ExtraReward(); apples.amount = 80; apples.weight = 20; apples.chance = .25; apples.maxPerOpen = 2;
                p.extraRewards.add(apples);
                var cards = new CrateService.ExtraReward(); cards.type = "card"; cards.cardKind = "civilian"; cards.weight = 10; cards.chance = .5;
                p.extraRewards.add(cards);
                check(CrateService.applyConfigJson(GSON.toJson(state)), "fixture-accepted-by-server");
                publish("");
                screen = new CrateManageScreen(mc.screen);
                set(screen, "selected", new ArrayList<>(state.crates.keySet()).indexOf("gilded"));
                mc.setScreen(screen);
                click(MANAGE + "rewards");
                bounds(); shots.put(80, "01-overview-1280.png");
            }
            case 90 -> {
                select("extra.green_apples", false);
                edit(UI + "amount", "120"); edit(UI + "weight", "50");
                check(Math.abs(Double.parseDouble(probability("extra.green_apples").replace("%", "")) - 38.46) < .01, "live-weight-probability");
                shots.put(100, "02-item-settings.png");
            }
            case 110 -> { click(UI + "add"); shots.put(115, "03-add-catalog.png"); }
            case 120 -> { edit(UI + "search", "capture_4"); shots.put(125, "04-skin-search.png"); }
            case 130 -> {
                select("skin.revolver/capture_4", true);
                check(proposal().crates.get("gilded").skinWeights.get("revolver/capture_4") == 100, "add-skin");
                click(UI + "replace"); edit(UI + "search", "capture_5"); select("skin.revolver/capture_5", true);
                var p = proposal().crates.get("gilded");
                check(!p.skinWeights.containsKey("revolver/capture_4") && p.skinWeights.get("revolver/capture_5") == 100, "replace-skin-preserves-weight");
                click(UI + "replace"); edit(UI + "search", "capture_1"); select("skin.revolver/capture_1", true);
                p = proposal().crates.get("gilded");
                check(p.skinWeights.get("revolver/capture_1") == 20 && p.skinWeights.get("revolver/capture_5") == 100, "existing-skin-selection-does-not-overwrite");
                select("skin.revolver/capture_5", false);
                shots.put(135, "05-replaced-skin.png");
            }
            case 140 -> {
                click(UI + "remove");
                check(!proposal().crates.get("gilded").skinWeights.containsKey("revolver/capture_5"), "remove-does-not-resurrect-on-save");
                click(UI + "rules"); edit(UI + "rolls", "0");
                check(!(Boolean)call(screen, "validDraftInput"), "invalid-draw-count-blocked");
                shots.put(145, "06-rule-validation.png");
            }
            case 150 -> {
                edit(UI + "rolls", "3");
                select("extra.green_apples", false); edit(UI + "weight", "NaN");
                check(!(Boolean)call(screen, "validDraftInput"), "nan-weight-blocked"); shots.put(155, "07-invalid-input.png");
            }
            case 160 -> {
                edit(UI + "weight", "25");
                check((Boolean)call(screen, "validDraftInput"), "valid-unified-weight");
                check(proposal().crates.get("gilded").extraRewards.getFirst().weight == 25, "weight-serialization");
                shots.put(165, "08-bonus-probability.png");
            }
            case 170 -> {
                select("extra.green_apples", false);
                resize(mc, 854, 480, 2); bounds();
                edit(UI + "max", "3");
                check(proposal().crates.get("gilded").extraRewards.getFirst().maxPerOpen == 3, "small-window-scroll-edit");
                shots.put(180, "09-small-854.png");
            }
            case 190 -> {
                click(UI + "add"); edit(UI + "search", ""); bounds(); shots.put(195, "10-small-catalog.png");
            }
            case 200 -> {
                click(UI + "rules"); edit(UI + "rolls", "3"); bounds();
                resize(mc, 1280, 720, 3); bounds(); shots.put(205, "11-scale3-rules.png");
            }
            case 210 -> {
                resize(mc, 1920, 1080, 4); select("extra.green_apples", false); bounds(); shots.put(215, "12-scale4.png");
            }
            case 220 -> {
                resize(mc, 1920, 1080, 0); bounds(); shots.put(225, "13-auto-scale.png");
            }
            case 230 -> {
                resize(mc, 1280, 720, 2);
                var expected = proposal();
                check(CrateService.applyConfigJson(GSON.toJson(expected)), "server-save-accepted");
                CrateService.onServerStopping(); CrateService.onServerStarted();
                set(screen, "pending", true); publish("crates.config_saved"); screen.refreshFromState();
                var saved = proposal().crates.get("gilded");
                check(saved.extraRewards.getFirst().amount == 120 && saved.extraRewards.getFirst().weight == 25
                        && saved.extraRewards.getFirst().maxPerOpen == 3,
                        "server-disk-reload-retains-edits");
                check(!(Boolean)get(screen, "dirty"), "save-acknowledgement-clears-draft");
                click(MANAGE + "copy"); click(MANAGE + "rewards");
                var copied = proposal().crates.values().stream().filter(p -> p.name.contains("copy")).findFirst().orElseThrow();
                check(copied.extraRewards.getFirst().amount == 120, "copy-keeps-reward-settings");
                click(MANAGE + "reset_current");
            }
            case 240 -> {
                click(MANAGE + "new"); click(MANAGE + "rewards");
                var created = proposal().crates.get("new_crate");
                check(created.skinWeights.isEmpty() && created.extraRewards.isEmpty(), "new-crate-empty-after-opening-rewards");
                shots.put(245, "14-empty-new-crate.png");
            }
            case 250 -> {
                click(UI + "add"); edit(UI + "search", "");
                click(UI + "filter_1");
                select("extra.civilian", true); edit(UI + "amount", "2");
                check(proposal().crates.get("new_crate").extraRewards.size() == 1, "explicit-add-only-one-item");
                click(UI + "remove"); check(proposal().crates.get("new_crate").extraRewards.isEmpty(), "remove-last-item-keeps-pool-empty");
                click(MANAGE + "reset_current");
                check(!proposal().crates.containsKey("new_crate"), "reset-discards-new-crate");
                // Reset does not mutate the persisted server pool.
                check(GSON.fromJson(CrateService.configJson(), CrateService.State.class).crates.get("gilded").extraRewards.getFirst().amount == 120, "reset-preserves-server-data");
                mc.options.languageCode = "en_us"; mc.getLanguageManager().setSelected("en_us"); mc.reloadResourcePacks();
            }
            case 280 -> {
                set(screen, "selected", new ArrayList<>(proposal().crates.keySet()).indexOf("gilded")); call(screen, "rebuild");
                click(UI + "rules"); bounds(); shots.put(285, "15-english-rules.png");
                screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
                check(screen.getFocused() != null, "tab-keyboard-navigation");
            }
            case 300 -> {
                mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn"); mc.reloadResourcePacks();
            }
            case 330 -> {
                select("skin.knife/capture_0", false); edit(UI + "weight", "10");
                click(UI + "only_reward");
                check("100.00%".equals(probability("skin.knife/capture_0")), "single-skin-weight-ten-is-100-percent");
                var expected = proposal();
                check(expected.crates.get("gilded").extraRewards.stream().allMatch(e -> e.weight == 0), "only-reward-disables-items");
                check(CrateService.applyConfigJson(GSON.toJson(expected)), "single-reward-save");
                set(screen, "pending", true); publish("crates.config_saved"); screen.refreshFromState();
                edit(UI + "weight", "11");
                var stale = proposal(); stale.revision--;
                check(!CrateService.applyConfigJson(GSON.toJson(stale)), "stale-save-rejected");
                set(screen, "pending", true); publish(CrateService.configError()); screen.refreshFromState();
                check((Boolean)get(screen, "dirty") && proposal().crates.get("gilded").skinWeights.get("knife/capture_0") == 11,
                        "failed-save-retains-draft");
                bounds(); shots.put(335, "16-final-overview.png");
            }
            case 350 -> {
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); check(mc.screen != screen, "escape-returns-parent");
                Files.write(output.resolve("checks.txt"), checks);
                HabiLotteryMod.LOGGER.info("REWARD_EDITOR_SMOKE PASS {} checks; {}", checks.size(), output);
                WorldLotteryPaths.clear(); mc.stop();
            }
        }
    }
    void capture(Minecraft mc, int tick) throws Exception {
        if (output == null || captured == tick || !shots.containsKey(tick)) return;
        captured = tick;
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(shots.get(tick))); }
    }
    private void resize(Minecraft mc, int w, int h, int scale) {
        mc.options.guiScale().set(scale); GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h); mc.resizeDisplay();
        requestedScale = scale; resizeDelay = 3;
    }
    private void publish(String message) {
        CrateClientNetwork.STATE.configJson = CrateService.configJson(); CrateClientNetwork.STATE.configMessage = message; CrateClientNetwork.STATE.configVersion++;
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("REWARD_EDITOR_SMOKE FAIL " + label);
        checks.add("PASS " + label); HabiLotteryMod.LOGGER.info("REWARD_EDITOR_SMOKE PASS {}", label);
    }
    private void bounds() {
        List<AbstractWidget> widgets = screen.children().stream().filter(c -> c instanceof AbstractWidget).map(c -> (AbstractWidget)c).filter(w -> w.visible).toList();
        for (var w : widgets) if (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > screen.width || w.getY() + w.getHeight() > screen.height)
            throw new IllegalStateException("Out-of-bounds widget: " + w.getMessage().getString());
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            var a = widgets.get(i); var b = widgets.get(j);
            if (a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                    && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY())
                throw new IllegalStateException("Overlapping widgets: " + a.getMessage().getString() + " / " + b.getMessage().getString());
        }
        checks.add("PASS widget-bounds " + screen.width + "x" + screen.height);
    }
    private void click(String translation) {
        String name = Component.translatable(translation).getString();
        var button = screen.children().stream().filter(c -> c instanceof Button b && b.visible && b.active && b.getMessage().getString().equals(name))
                .map(c -> (Button)c).findFirst().orElseThrow(() -> new IllegalStateException("Button not reachable: " + name));
        screen.mouseClicked(button.getX() + button.getWidth() / 2D, button.getY() + button.getHeight() / 2D, 0);
    }
    private void select(String key, boolean catalog) throws Exception {
        Object editor = get(screen, "rewardEditor");
        for (int attempt = 0; attempt < 25; attempt++) {
            for (var child : screen.children()) if (child instanceof Button b && b.getClass().getSimpleName().equals("RewardButton")
                    && get(b, "key").equals(key) && (Boolean)get(b, "catalogItem") == catalog) {
                screen.mouseClicked(b.getX() + b.getWidth() / 2D, b.getY() + b.getHeight() / 2D, 0); return;
            }
            double at = (Integer)get(editor, catalog ? "bodyY" : "stripY") + 4;
            screen.mouseScrolled(30, at, 0, -1);
        }
        throw new IllegalStateException("Reward not reachable: " + key);
    }
    private void edit(String translation, String text) throws Exception {
        String name = Component.translatable(translation).getString();
        Object editor = get(screen, "rewardEditor");
        for (int attempt = 0; attempt < 20; attempt++) {
            for (var child : screen.children()) if (child instanceof EditBox b && b.visible && b.getMessage().getString().equals(name)) {
                screen.mouseClicked(b.getX() + 4, b.getY() + 10, 0);
                int length = b.getValue().length();
                screen.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
                for (int i = 0; i < length; i++) screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                for (char c : text.toCharArray()) screen.charTyped(c, 0);
                return;
            }
            screen.mouseScrolled(30, (Integer)get(editor, "bodyY") + 4, 0, attempt < 10 ? -1 : 1);
        }
        throw new IllegalStateException("Input not reachable: " + name);
    }
    private String probability(String key) throws Exception {
        var e = get(screen, "rewardEditor"); var m = e.getClass().getDeclaredMethod("probability", String.class); m.setAccessible(true); return (String)m.invoke(e, key);
    }
    private CrateService.State proposal() throws Exception {
        var state = GSON.fromJson(GSON.toJson(get(screen, "draft")), CrateService.State.class);
        var m = screen.getClass().getDeclaredMethod("applyFields", CrateService.State.class); m.setAccessible(true); m.invoke(screen, state); return state;
    }
    private static Object get(Object target, String field) throws Exception { var f = target.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(target); }
    private static void set(Object target, String field, Object value) throws Exception { var f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value); }
    private static Object call(Object target, String method) throws Exception { var m = target.getClass().getDeclaredMethod(method); m.setAccessible(true); return m.invoke(target); }
}

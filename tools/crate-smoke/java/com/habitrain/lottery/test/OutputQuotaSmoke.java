package com.habitrain.lottery.test;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.gui.CrateManageScreen;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.storage.WorldLotteryPaths;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only real-client quota editing, live counters, language, keyboard and size coverage. */
final class OutputQuotaSmoke {
    private static final Gson GSON = new Gson();
    private static final String KEY = "screen.habitrain_lottery.crate_manage.";
    private final List<String> checks = new ArrayList<>();
    private final Map<Integer, String> shots = new LinkedHashMap<>();
    private CrateManageScreen screen;
    private CrateService.State fixture;
    private Path output;
    private int captured = -1, resizeDelay, scale;

    void tick(Minecraft mc, int tick) throws Exception {
        if (resizeDelay > 0 && --resizeDelay == 0) {
            mc.options.guiScale().set(scale); mc.resizeDisplay(); bounds();
        }
        switch (tick) {
            case 30 -> language(mc, "zh_cn");
            case 50 -> resize(mc, 1280, 720, 2);
            case 70 -> {
                output = mc.gameDirectory.toPath().resolve(System.getProperty("crateSmoke.out", "quota-checks-1.1.40"));
                Files.createDirectories(output);
                WorldLotteryPaths.initForTests(Files.createTempDirectory(output, "server-state-"));
                CrateService.onServerStarted();
                fixture = GSON.fromJson(CrateService.configJson(), CrateService.State.class);
                var pool = fixture.crates.get("gilded"); pool.name = "庆典物资箱";
                int[] limits = {2000, 1000, 80, 12, 0}; int[] used = {800, 100, 76, 12, 0};
                Map<String, Long> counts = new LinkedHashMap<>();
                for (int i = 0; i < 5; i++) {
                    String q = SkinQuality.values()[i].id();
                    pool.outputLimits.get(q).limit = limits[i];
                    pool.outputLimits.get(q).period = i % 2 == 0 ? "weekly" : "monthly";
                    counts.put(q, (long)used[i]);
                }
                check(CrateService.applyConfigJson(GSON.toJson(fixture)), "server-accepts-independent-limits");
                fixture = GSON.fromJson(CrateService.configJson(), CrateService.State.class);
                fixture.outputWeeklyUsed.put("gilded", new LinkedHashMap<>(counts));
                fixture.outputMonthlyUsed.put("gilded", new LinkedHashMap<>(counts));
                var pending = new CrateService.PendingOpen(); pending.crateId = "gilded";
                pending.weekKey = fixture.weekKey; pending.monthKey = fixture.monthKey; pending.outputDelta.put("purple", 2L);
                fixture.pendingOpens.put("fixture-reservation", pending);
                publish(""); screen = new CrateManageScreen(mc.screen);
                set(screen, "selected", new ArrayList<>(fixture.crates.keySet()).indexOf("gilded"));
                mc.setScreen(screen); click(KEY + "limits"); bounds(); shots.put(80, "01-five-qualities-1280.png");
            }
            case 90 -> {
                edit(SkinQuality.WHITE, "100");
                check(proposal().crates.get("gilded").outputLimits.get("white").limit == 100, "quantity-edit-serialized");
                check(proposal().crates.get("woodland").outputLimits.get("white").limit == 2000, "editing-does-not-affect-other-crate");
                var box = input(SkinQuality.WHITE);
                var period = screen.children().stream().filter(c -> c instanceof Button b && b.getY() == box.getY() && b.getX() < box.getX())
                        .map(c -> (Button)c).findFirst().orElseThrow();
                screen.mouseClicked(period.getX() + 5, period.getY() + 5, 0);
                check(proposal().crates.get("gilded").outputLimits.get("white").period.equals("monthly"), "independent-period-toggle");
                check(proposal().crates.get("gilded").outputLimits.get("purple").period.equals("weekly"), "other-quality-period-preserved");
                edit(SkinQuality.WHITE, "-2"); check(!(Boolean)call(screen, "validDraftInput"), "invalid-negative-limit-blocked");
                shots.put(95, "02-validation.png");
            }
            case 100 -> {
                edit(SkinQuality.WHITE, "1000000001"); check(!(Boolean)call(screen, "validDraftInput"), "oversized-limit-blocked");
                edit(SkinQuality.WHITE, "-1"); check((Boolean)call(screen, "validDraftInput"), "unlimited-valid");
                edit(SkinQuality.RED, "5");
                fixture.outputWeeklyUsed.get("gilded").put("purple", 78L);
                publish(""); screen.refreshFromState();
                check(proposal().crates.get("gilded").outputLimits.get("red").limit == 5, "usage-refresh-preserves-draft");
                var latest = (CrateService.State)get(screen, "latestQuotaState");
                check(latest.outputWeeklyUsed.get("gilded").get("purple") == 78L, "dirty-editor-gets-live-usage");
                shots.put(105, "03-live-progress.png");
            }
            case 110 -> { resize(mc, 854, 480, 2); shots.put(120, "04-small-first-page.png"); }
            case 130 -> {
                edit(SkinQuality.RED, "7"); bounds();
                check(proposal().crates.get("gilded").outputLimits.get("red").limit == 7, "small-window-red-reachable");
                shots.put(135, "05-small-last-page.png");
            }
            case 140 -> {
                resize(mc, 1280, 720, 3); shots.put(150, "06-scale3.png");
            }
            case 160 -> {
                resize(mc, 1920, 1080, 4); shots.put(170, "07-scale4.png");
            }
            case 180 -> {
                resize(mc, 1920, 1080, 0); shots.put(190, "08-auto.png");
            }
            case 200 -> { resize(mc, 1280, 720, 2); language(mc, "en_us"); }
            case 230 -> {
                call(screen, "rebuild"); bounds(); shots.put(240, "09-english.png");
                screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); check(screen.getFocused() != null, "keyboard-tab-focus");
            }
            case 250 -> {
                var expected = proposal();
                check(CrateService.applyConfigJson(GSON.toJson(expected)), "save-draft-to-service");
                CrateService.onServerStopping(); CrateService.onServerStarted();
                fixture = GSON.fromJson(CrateService.configJson(), CrateService.State.class);
                set(screen, "pending", true); publish("crates.config_saved"); screen.refreshFromState();
                var saved = proposal().crates.get("gilded");
                check(saved.outputLimits.get("white").limit == -1 && saved.outputLimits.get("white").period.equals("monthly")
                        && saved.outputLimits.get("red").limit == 7, "limits-persist-through-service-restart");
                check(!(Boolean)get(screen, "dirty"), "save-ack-clears-draft");
                edit(SkinQuality.RED, "9"); click(KEY + "reset_current");
                check(proposal().crates.get("gilded").outputLimits.get("red").limit == 7, "reset-restores-saved-limit");
                click(KEY + "copy");
                check(proposal().crates.get("gilded_copy").outputLimits.get("red").limit == 7, "copy-keeps-independent-caps");
                click(KEY + "reset_current");
                language(mc, "zh_cn");
            }
            case 280 -> {
                click(KEY + "limits"); bounds(); shots.put(285, "10-saved.png");
            }
            case 300 -> {
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); check(mc.screen != screen, "escape-returns-parent");
                Files.write(output.resolve("checks.txt"), checks);
                HabiLotteryMod.LOGGER.info("OUTPUT_QUOTA_SMOKE PASS {} checks; {}", checks.size(), output);
                CrateService.onServerStopping(); WorldLotteryPaths.clear(); mc.stop();
            }
        }
    }

    void capture(Minecraft mc, int tick) throws Exception {
        if (output == null || captured == tick || !shots.containsKey(tick)) return;
        captured = tick;
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(shots.get(tick))); }
    }
    private void language(Minecraft mc, String language) { mc.options.languageCode = language; mc.getLanguageManager().setSelected(language); mc.reloadResourcePacks(); }
    private void resize(Minecraft mc, int w, int h, int guiScale) {
        mc.options.guiScale().set(guiScale); GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h); mc.resizeDisplay();
        scale = guiScale; resizeDelay = 3;
    }
    private void publish(String message) {
        CrateClientNetwork.STATE.configJson = GSON.toJson(fixture); CrateClientNetwork.STATE.configMessage = message; CrateClientNetwork.STATE.configVersion++;
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("OUTPUT_QUOTA_SMOKE FAIL " + label);
        checks.add("PASS " + label); HabiLotteryMod.LOGGER.info("OUTPUT_QUOTA_SMOKE PASS {}", label);
    }
    private void bounds() {
        if (screen == null) return;
        List<AbstractWidget> widgets = screen.children().stream().filter(c -> c instanceof AbstractWidget).map(c -> (AbstractWidget)c).filter(w -> w.visible).toList();
        for (var w : widgets) if (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > screen.width || w.getY() + w.getHeight() > screen.height)
            throw new IllegalStateException("Out of bounds: " + w.getMessage().getString());
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            var a = widgets.get(i); var b = widgets.get(j);
            if (a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                    && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY())
                throw new IllegalStateException("Overlapping widgets: " + a.getMessage().getString() + " / " + b.getMessage().getString());
        }
        checks.add("PASS widget bounds " + screen.width + "x" + screen.height);
    }
    private EditBox input(SkinQuality quality) {
        String name = Component.translatable(KEY + "output_limit_label", Component.translatable(quality.translationKey())).getString();
        for (int attempt = 0; attempt < 10; attempt++) {
            for (var child : screen.children()) if (child instanceof EditBox box && box.getMessage().getString().equals(name)) return box;
            screen.mouseScrolled(30, screen.height / 2.0, 0, attempt < 5 ? -1 : 1);
        }
        throw new IllegalStateException("Input unreachable: " + name);
    }
    private void edit(SkinQuality quality, String value) {
        var box = input(quality); screen.mouseClicked(box.getX() + 4, box.getY() + 8, 0);
        int length = box.getValue().length(); screen.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
        for (int i = 0; i < length; i++) screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
        for (char c : value.toCharArray()) screen.charTyped(c, 0);
    }
    private void click(String key) {
        String name = Component.translatable(key).getString();
        var button = screen.children().stream().filter(c -> c instanceof Button b && b.visible && b.active && b.getMessage().getString().equals(name))
                .map(c -> (Button)c).findFirst().orElseThrow(() -> new IllegalStateException("Button unreachable: " + name));
        screen.mouseClicked(button.getX() + 4, button.getY() + 8, 0);
    }
    private CrateService.State proposal() throws Exception {
        var state = GSON.fromJson(GSON.toJson(get(screen, "draft")), CrateService.State.class);
        var method = screen.getClass().getDeclaredMethod("applyFields", CrateService.State.class); method.setAccessible(true); method.invoke(screen, state); return state;
    }
    private static Object get(Object target, String field) throws Exception { var f = target.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(target); }
    private static void set(Object target, String field, Object value) throws Exception { var f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value); }
    private static Object call(Object target, String method) throws Exception { var m = target.getClass().getDeclaredMethod(method); m.setAccessible(true); return m.invoke(target); }
}

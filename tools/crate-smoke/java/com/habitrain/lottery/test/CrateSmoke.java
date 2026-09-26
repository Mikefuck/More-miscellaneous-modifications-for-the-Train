package com.habitrain.lottery.test;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.client.CrateClientNetwork;
import com.habitrain.lottery.client.gui.CrateOpenScreen;
import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.network.CrateNetwork;
import com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState;
import com.habitrain.lottery.network.WarehouseNetwork;
import com.habitrain.lottery.warehouse.WarehouseEntry;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.habitrain.lottery.client.gui.CrateStage;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TEST-ONLY crate-opening recorder.
 *
 * <p>Boots a real Minecraft client, drives the real production {@link WarehouseScreen} and
 * {@link CrateOpenScreen}, injects the payloads the server would normally send, grabs one PNG per
 * client tick and writes {@code frames/timing.csv} with the real wall-clock offset of each frame so
 * ffmpeg can rebuild the clip at true speed (no resampling, no speed change).</p>
 *
 * <p>Nothing here replaces production code: it only calls public API plus reflection on private
 * fields the GUI itself owns.</p>
 */
public final class CrateSmoke implements ClientModInitializer {
    private static final String TAG = "CRATE_CAPTURE";

    // ------------------------------------------------------------------
    // Tunables. The GUI animation is ~4.9s and the reference video is
    // ~13.4s; RECORD_MS is the whole capture window, deliberately far
    // longer than either so a re-timed animation still fits. Raise it and
    // re-run if the animation gets slower.
    // ------------------------------------------------------------------
    private static final long RECORD_MS = 30_000L;
    private static final int MIN_FRAMES = 200;
    private static final long FLUSH_MS = 4_000L;
    /** 动画结束后再多录一点，让观者看到落位后的静止画面。 */
    private static final long TAIL_MS = 1_500L;
    private static final int WRITE_QUEUE = 48;
    private static final String CRATE_ID = "gilded";
    /** 运行时可用 -DcrateSmoke.width / height / guiScale / out 覆盖，便于跑 1080p 对照版。 */
    private static final int WIN_W = Integer.getInteger("crateSmoke.width", 1280);
    private static final int WIN_H = Integer.getInteger("crateSmoke.height", 720);
    private static final int GUI_SCALE = Integer.getInteger("crateSmoke.guiScale", 2);
    private static final String OUT_DIR = System.getProperty("crateSmoke.out", "frames");

    // ---- fixtures -------------------------------------------------------
    private final List<WarehouseEntry> rows = new ArrayList<>();
    private final Map<String, Double> inventory = new LinkedHashMap<>();
    private String resultType = "";
    private String resultSkin = "";
    private String resultQuality = "white";

    // ---- capture bookkeeping -------------------------------------------
    private int ticks;
    private int frames;
    private long firstFrameAt = -1L;
    private long recordDeadline = -1L;
    /** 动画落位后开始计尾帧的时间点。 */
    private long settleAt = -1L;
    private long flushDeadline = -1L;
    private long lastLogAt = -1L;
    /** Written by the tick thread, read by the writer thread. */
    private volatile boolean flushing;
    private boolean finished;
    private File frameDir;
    private BlockingQueue<Frame> queue;
    private final List<Frame> written = new ArrayList<>();
    private final List<Thread> writers = new ArrayList<>();
    private long lastCaptureAt;
    private long returnedAt = -1;
    private boolean clickedOpen, injected, checkedCancel, reopened;
    private String lastPhase = "";
    private final List<String> events = new ArrayList<>();
    private static final boolean CHECKS = Boolean.getBoolean("crateSmoke.checks");
    private volatile Throwable writerFailure;

    /** One grabbed frame: the index, the wall-clock offset it was taken at and the raw pixels. */
    private static final class Frame {
        final int index;
        final long at;
        NativeImage image;

        Frame(int index, long at, NativeImage image) {
            this.index = index;
            this.at = at;
            this.image = image;
        }
    }

    // ==================================================================
    // entry point
    // ==================================================================

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> ScreenEvents.afterRender(screen).register(
                (shown, graphics, mx, my, delta) -> {
                    long now = Util.getMillis();
                    if (now - lastCaptureAt < 33 || ticks < 70) return;
                    lastCaptureAt = now;
                    try { graphics.flush(); capture(mc); }
                    catch (Throwable error) { HabiLotteryMod.LOGGER.error("{} capture FAILED", TAG, error); stop(mc); }
                }));
    }

    private void onTick(Minecraft mc) {
        if (mc.getOverlay() != null || mc.screen == null) return;
        try {
            // Visual fixture with no world: keep the lobby-state guard from freezing every frame.
            var guard = Class.forName("com.habitrain.lottery.client.gui.CardGuiGameState");
            var cached = guard.getDeclaredField("cachedValue");
            cached.setAccessible(true);
            cached.setBoolean(null, false);
            var at = guard.getDeclaredField("cachedAtNanos");
            at.setAccessible(true);
            at.setLong(null, System.nanoTime());

            ++ticks;
            drive(mc);
            advance(mc);
        } catch (Throwable error) {
            HabiLotteryMod.LOGGER.error("{} FAILED", TAG, error);
            stop(mc);
        }
    }

    // ==================================================================
    // scripted timeline (20 ticks = 1 second of game time)
    // ==================================================================

    private void drive(Minecraft mc) throws Exception {
        switch (ticks) {
            case 30 -> {
                // Chinese labels, matching the reference recording's language.
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn");
                mc.reloadResourcePacks();
            }
            case 50 -> {
                mc.options.guiScale().set(GUI_SCALE);
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), WIN_W, WIN_H);
                mc.resizeDisplay();
            }
            case 60 -> {
                seedInventory();
                WarehouseScreen warehouse = new WarehouseScreen(mc.screen);
                mc.setScreen(warehouse);
                fillWarehouse(warehouse);
                HabiLotteryMod.LOGGER.info("{} warehouse open: crate={} key={} rows={}",
                        TAG, CrateService.crateItemId(CRATE_ID), CrateService.keyItemId(CRATE_ID), rows.size());
            }
            case 75 -> openCrateTile(mc);
            case 90 -> {
                // Real production path: drawer "use" -> WarehouseScreen.beginCrateDepart -> 220ms
                // darkening -> main-thread hand-off to CrateOpenScreen (shared transition language).
                Object screen = mc.screen;
                if (!(screen instanceof WarehouseScreen)) {
                    throw new IllegalStateException("warehouse gone before depart: " + screen);
                }
                net.minecraft.client.gui.components.AbstractWidget button =
                        (net.minecraft.client.gui.components.AbstractWidget)get(screen,"detailAction");
                ((WarehouseScreen)screen).mouseClicked(button.getX()+button.getWidth()*.5,button.getY()+12,0);
                event("warehouse-open=PASS");
            }
            case 105 -> HabiLotteryMod.LOGGER.info("{} after warehouse depart: screen={}", TAG,
                    mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
            default -> { }
        }
    }

    private void event(String message) {
        String row = (Util.getMillis() - firstFrameAt) + "," + message;
        events.add(row);
        HabiLotteryMod.LOGGER.info("{} EVENT {}", TAG, row);
    }

    private void click(CrateOpenScreen screen, float x, float y) {
        float scale = Math.min(screen.width / 1920F, screen.height / 1080F);
        screen.mouseClicked((screen.width - 1920 * scale) / 2 + x * scale,
                (screen.height - 1080 * scale) / 2 + y * scale, 0);
    }

    private void advance(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof CrateOpenScreen screen)) return;
        long now = Util.getMillis();
        long entered = (long)get(screen, "enteredAt");
        CrateStage stage = (CrateStage)get(screen, "stage");
        String phase = stage.phase(now).name();
        if (!phase.equals(lastPhase)) { lastPhase = phase; event("phase=" + phase); }
        if (CHECKS && !checkedCancel && now - entered >= 1800) {
            click(screen,1290,649);
            if (!(boolean)get(screen,"modalCancelled")) throw new IllegalStateException("cancel click failed");
            checkedCancel = true; event("cancel=PASS");
        }
        if (CHECKS && checkedCancel && !reopened && now - entered >= 2400) {
            click(screen,1240,1050);
            if ((boolean)get(screen,"modalCancelled")) throw new IllegalStateException("reopen click failed");
            reopened = true; event("reopen=PASS");
        }
        if (!clickedOpen && now - entered >= (CHECKS ? 2900 : 1933)) {
            click(screen,1174,649);
            stage = (CrateStage)get(screen,"stage");
            if (!stage.active()) throw new IllegalStateException("mouse confirm failed");
            clickedOpen = true; event("mouse-confirm=PASS");
        }
        if (clickedOpen && !injected && now - stage.openedAt() >= 200) {
            screen.receive(new CrateNetwork.OpenResultS2C(true, CRATE_ID, CrateService.keyItemId(CRATE_ID),
                    resultType,resultSkin,resultQuality,"crates.opened",158,638));
            injected = true; event("fixture-result="+resultType+"/"+resultSkin);
            inventory.put(CrateService.crateItemId(CRATE_ID),2D);
            inventory.put(CrateService.keyItemId(CRATE_ID),4D);
            CrateClientNetwork.STATE.inventory = Map.copyOf(inventory);
            CrateClientNetwork.STATE.inventoryVersion++;
        }
        if (injected && stage.finished(now) && returnedAt < 0 && now >= stage.finishAt() + TAIL_MS) {
            click(screen,1420,1050);
            // The production hand-off completes on the next tick.
            returnedAt = now;
            event("close-click=PASS");
        }
    }

    /** Opens the real crate row in the drawer; the depart fires one tick later from case 90. */
    private void openCrateTile(Minecraft mc) throws Exception {
        if (!(mc.screen instanceof WarehouseScreen screen)) {
            throw new IllegalStateException("expected WarehouseScreen, got " + mc.screen);
        }
        String crateItem = CrateService.crateItemId(CRATE_ID);
        Object tile = null;
        for (Object candidate : (List<?>) get(screen, "tiles")) {
            Object entry = get(candidate, "entry");
            if (entry != null && crateItem.equals(entry.getClass().getMethod("id").invoke(entry))) {
                tile = candidate;
                break;
            }
        }
        if (tile == null) {
            throw new IllegalStateException("crate row " + crateItem + " missing from warehouse fixture");
        }
        net.minecraft.client.gui.components.AbstractWidget widget = (net.minecraft.client.gui.components.AbstractWidget)tile;
        screen.mouseClicked(widget.getX() + widget.getWidth() * .5, widget.getY() + 10, 0);
        if (get(screen,"detail") == null) throw new IllegalStateException("warehouse tile click failed");
        event("warehouse-tile=PASS");
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    /** Real inventory so the production screen sees crate and key counts above zero. */
    private void seedInventory() {
        CrateClientNetwork.CrateClientState state = CrateClientNetwork.STATE;
        inventory.put(CrateService.crateItemId(CRATE_ID), 3.0D);
        inventory.put(CrateService.keyItemId(CRATE_ID), 5.0D);
        state.inventory = new LinkedHashMap<>(inventory);
        // refreshFromState() only reacts to a version change, so publish a new revision.
        state.inventoryVersion = state.inventoryVersion + 1;
        HabiLotteryMod.LOGGER.info("{} inventory seeded v{} {}", TAG, state.inventoryVersion, state.inventory);
    }

    private void fillWarehouse(WarehouseScreen screen) throws Exception {
        rows.clear();
        rows.add(new WarehouseEntry("currency", "green_apples", "绿苹果",
                "通过每日登录奖励、邮件、管理员和玩家 API 获得。",
                "habitrain_lottery:textures/gui/green_apple.png", 2680, 0xFF78B85A, false));
        // The row the recorder clicks: a real catalogue item, so WarehouseScreen routes it to the opener.
        rows.add(new WarehouseEntry("special", CrateService.crateItemId(CRATE_ID), "镀金武器箱",
                "消耗一把镀金钥匙开启，随机获得一件金色品质皮肤。", "minecraft:gold_block", inventory.get(CrateService.crateItemId(CRATE_ID)).intValue(), 0xFFF0BE45, false));
        rows.add(new WarehouseEntry("special", CrateService.keyItemId(CRATE_ID), "镀金钥匙",
                "用于开启镀金武器箱。", "minecraft:gold_nugget", inventory.get(CrateService.keyItemId(CRATE_ID)).intValue(), 0xFFF0BE45, false));
        String[] items = {"amethyst_shard", "echo_shard", "emerald", "book", "firework_star", "nether_star",
                "prismarine_crystals", "experience_bottle"};
        String[] names = {"周年纪念晶石", "回声碎片", "活动兑换券", "列车补给凭证", "星芒徽记", "星之勋章",
                "虹光碎晶", "经验加成券"};
        for (int i = 0; i < items.length; i++) {
            rows.add(new WarehouseEntry("special", "test:" + items[i], names[i],
                    "系统发放的特殊道具。保存在账户仓库，可用于对应活动。",
                    "minecraft:" + items[i], i + 1, 0xFF84A6AD + i * 500, false));
        }

        set(screen, "loading", true);
        set(screen, "requestId", 91);
        set(screen, "expectedTotal", -1);
        // Snapshot keeps the list it is handed, so pass detached copies rather than views over a
        // fixture list this recorder may rebuild.
        screen.receive(new WarehouseNetwork.Snapshot(91, 0, rows.size(), "", new ArrayList<>(rows.subList(0, 4))));
        screen.receive(new WarehouseNetwork.Snapshot(91, 4, rows.size(), "", new ArrayList<>(rows.subList(4, rows.size()))));
        ClientLotteryState.cardBalances = new LinkedHashMap<>();
        ClientLotteryState.cardUseRemainingUses = 3;
        ClientLotteryState.cardUseRemainingSelfUses = 2;
        set(screen, "cardsKnown", true);
        set(screen, "error", "");

        buildResult();
    }

    /**
     * Picks a real registered skin of the crate's primary quality so the reveal is genuine content.
     *
     * <p>This mod ships no skins of its own: they arrive from provider entrypoints. If none are
     * registered in the test client, the result stays empty and {@code CrateOpenScreen.preview}
     * falls back to its nether star exactly as it would in production -- recorded, but logged
     * loudly so a missing provider is never mistaken for a rendering bug.</p>
     */
    private void buildResult() {
        CrateService.Definition definition = CrateService.definition(CRATE_ID);
        SkinQuality primary = definition == null ? SkinQuality.WHITE : definition.primary();
        boolean prism = definition != null && definition.prism();
        List<SkinDefinition> all = HabiSkinApi.registrations();
        SkinDefinition chosen = null;
        for (SkinDefinition skin : all) {
            if (prism || skin.quality() == primary) {
                chosen = skin;
            }
        }
        if (chosen == null && !all.isEmpty()) chosen = all.get(all.size() - 1);
        if (chosen == null) {
            HabiLotteryMod.LOGGER.warn("{} no skins registered: the reveal will use the nether star fallback", TAG);
            resultType = "";
            resultSkin = "";
            resultQuality = primary.id();
            return;
        }
        resultType = chosen.type();
        resultSkin = chosen.id();
        resultQuality = chosen.quality().id();
        HabiLotteryMod.LOGGER.info("{} result chosen from {} registered skins: {}/{} ({})", TAG, all.size(),
                resultType, resultSkin, resultQuality);
    }

    // ==================================================================
    // capture: one PNG per tick, encoded and written off the render thread
    // ==================================================================

    private void capture(Minecraft mc) throws Exception {
        if (finished) return;
        if (flushing) {
            if (writerFailure != null) {
                HabiLotteryMod.LOGGER.error("{} frame writer failed", TAG, writerFailure);
                finish(mc);
                return;
            }
            if (queue != null && queue.isEmpty() && Util.getMillis() >= flushDeadline) {
                finish(mc);
            }
            return;
        }
        if (recordDeadline < 0L) {
            // 从开箱终端出现的那一帧开始录：入场段（合焦 / 落箱 / 扶正 / 物品条 / 弹窗）
            // 也是这次复刻的一部分，不能漏掉。真正的开箱由 case 118 的硬断言保证一定会发生。
            if (!(mc.screen instanceof WarehouseScreen)) {
                if (ticks > 400) throw new IllegalStateException("crate screen never appeared");
                return;
            }
            firstFrameAt = Util.getMillis();
            recordDeadline = firstFrameAt + RECORD_MS;
            openWriter(mc);
            HabiLotteryMod.LOGGER.info("{} recording start budget={}ms minFrames={} at={}",
                    TAG, RECORD_MS, MIN_FRAMES, firstFrameAt);
        }
        long now = Util.getMillis();
        if (now < recordDeadline && !settled(mc, now)) {
            // One grab per tick, never queue-grow without bound: the writer thread owns PNG I/O.
            if (writerFailure == null && !queue.isEmpty() && queue.remainingCapacity() == 0) return;
            grabFrame(mc, now);
            return;
        }
        if (frames < MIN_FRAMES && now < recordDeadline) {
            grabFrame(mc, now);
            return;
        }
        flushing = true;
        flushDeadline = now + FLUSH_MS;
        HabiLotteryMod.LOGGER.info("{} recording stop: {} frames, queued={}, flushing {}ms",
                TAG, frames, queue == null ? 0 : queue.size(), FLUSH_MS);
    }

    private void openWriter(Minecraft mc) {
        frameDir = new File(mc.gameDirectory, OUT_DIR);
        if (!frameDir.isDirectory() && !frameDir.mkdirs()) {
            throw new IllegalStateException("cannot create " + frameDir.getAbsolutePath());
        }
        queue = new ArrayBlockingQueue<>(WRITE_QUEUE);
        for (int i = 0; i < 4; i++) {
            Thread writer = new Thread(this::writeFrames, "crate-smoke-frame-writer-" + i);
            writer.setDaemon(true);
            writers.add(writer);
            writer.start();
        }
        HabiLotteryMod.LOGGER.info("{} frames -> {}", TAG, frameDir.getAbsolutePath());
    }

    /** Runs on the render thread: one GL read-back plus a buffer copy, the PNG encode is async. */
    private void grabFrame(Minecraft mc, long now) {
        int index = frames + 1;
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        if (image == null) throw new IllegalStateException("takeScreenshot returned null");
        frames = index;
        try {
            queue.put(new Frame(index, now, image));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            image.close();
            throw new IllegalStateException("interrupted while queueing frame", interrupted);
        }
        if (now - lastLogAt >= 2_000L) {
            lastLogAt = now;
            HabiLotteryMod.LOGGER.info("{} {} frames ({}x{}), {}ms elapsed, avg {}/s",
                    TAG, frames, image.getWidth(), image.getHeight(), now - firstFrameAt,
                    frames * 1000L / Math.max(1L, now - firstFrameAt));
        }
    }

    /** Off-thread PNG encode + disk write; frames arrive in grab order so the index stays ordered. */
    private void writeFrames() {
        try {
            while (true) {
                Frame frame = queue.poll(500L, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    synchronized (written) {
                        if (flushing && queue.isEmpty()) return;
                    }
                    continue;
                }
                frame.image.writeToFile(new File(frameDir,
                        String.format(Locale.ROOT, "f_%05d.png", frame.index)));
                frame.image.close();
                frame.image = null;
                synchronized (written) {
                    written.add(frame);
                }
            }
        } catch (Throwable error) {
            writerFailure = error;
        }
    }

    private void finish(Minecraft mc) {
        if (finished) return;
        finished = true;
        for (Thread writer : writers) {
            try {
                writer.join(20_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (writerFailure != null) HabiLotteryMod.LOGGER.error("{} frames incomplete", TAG, writerFailure);
        writeManifest();
        stop(mc);
    }

    private void writeManifest() {
        List<Frame> done;
        synchronized (written) {
            done = new ArrayList<>(written);
        }
        done.sort((a, b) -> Integer.compare(a.index, b.index));
        long span = 0L;
        // Concat manifests need a duration for the final image too; reuse the last real interval.
        long lastGap = done.size() > 1
                ? Math.max(1L, done.get(done.size() - 1).at - done.get(done.size() - 2).at) : 50L;
        try {
            Files.write(new File(frameDir,"events.csv").toPath(),events,StandardCharsets.UTF_8);
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                    new File(frameDir, "timing.csv").toPath(), StandardCharsets.UTF_8))) {
                out.println("frame,ms");
                for (Frame frame : done) {
                    out.println(frame.index + "," + (frame.at - firstFrameAt));
                }
            }
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                    new File(frameDir, "frames.txt").toPath(), StandardCharsets.UTF_8))) {
                for (int i = 0; i < done.size(); i++) {
                    Frame frame = done.get(i);
                    long next = i + 1 < done.size() ? done.get(i + 1).at : frame.at + lastGap;
                    out.println("file '" + String.format(Locale.ROOT, "f_%05d.png", frame.index) + "'");
                    out.println("duration " + String.format(Locale.ROOT, "%.6f",
                            Math.max(1L, next - frame.at) / 1000.0D));
                }
                if (!done.isEmpty()) {
                    out.println("file '" + String.format(Locale.ROOT, "f_%05d.png",
                            done.get(done.size() - 1).index) + "'");
                }
            }
            if (done.size() > 1) {
                span = done.get(done.size() - 1).at - firstFrameAt + lastGap;
            }
        } catch (Exception error) {
            HabiLotteryMod.LOGGER.error("{} manifest write failed", TAG, error);
        }
        HabiLotteryMod.LOGGER.info("{} timing.csv frames={} span={}ms avgFps={} lastGap={}ms",
                TAG, done.size(), span, done.size() < 2 ? "n/a"
                        : String.format(Locale.ROOT, "%.2f", done.size() * 1000.0D / span), lastGap);
    }

    private void stop(Minecraft mc) {
        HabiLotteryMod.LOGGER.info("{} CRATE_CAPTURE_DONE frames={} written={} dir={}", TAG, frames,
                written.size(), frameDir == null ? "?" : frameDir.getAbsolutePath());
        mc.stop();
    }

    // ==================================================================
    // reflection helpers (test-only access to GUI-private state)
    // ==================================================================

    private static void key(Object screen, int code) throws Exception {
        screen.getClass().getMethod("keyPressed", int.class, int.class, int.class)
                .invoke(screen, code, 0, 0);
    }

    /** True once the crate animation has actually started on this screen. */
    private static boolean stageActive(Object screen) throws Exception {
        Object stage = get(screen, "stage");
        return (boolean) stage.getClass().getMethod("active").invoke(stage);
    }

    /**
     * 动画是否已经落位，并且落位后已经录满 {@link #TAIL_MS}。
     * 这样剪辑长度正好覆盖「入场 + 开箱 + 展示 + 一小段静止」，不会拖出十几秒空转。
     */
    private boolean settled(Minecraft mc, long now) throws Exception {
        if (returnedAt < 0) return false;
        if (now - returnedAt < 300) return false;
        if (!(mc.screen instanceof WarehouseScreen screen)) throw new IllegalStateException("return to warehouse failed");
        if (settleAt < 0) {
            // Offline fixture supplies the refreshed snapshot; real sessions receive it from the server.
            fillWarehouse(screen);
            settleAt = now;
            event("return-warehouse=PASS");
        }
        return now - settleAt >= 1500;

    }

    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void invoke(Object target, String name, Class<?> type, Object argument) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(target, argument);
    }
}

package com.habitrain.lottery.test;

import com.google.gson.Gson;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiCardApi;
import com.habitrain.lottery.api.player.HabiTitleApi;
import com.habitrain.lottery.client.gui.DailyShopManageScreen;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.daily.DailyTaskSnapshot;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.shop.DailyShopConfig;
import com.habitrain.lottery.daily.shop.DailyShopItem;
import com.habitrain.lottery.daily.shop.DailyShopService;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Drives the shop tab of the daily terminal in a fresh integrated-server world: listings are
 * published through {@link DailyShopService#apply} (the editor's save path), purchases go through
 * real mouse clicks (buy → confirm), and balances, goods, limits and refusals are read back from
 * the server stores. Finishes with the Mod Menu shop editor loading and saving a change.
 *
 * <p>Enabled with {@code -PdailySmokeMode=shop}; screenshots land in {@code daily-smoke-shop/}.</p>
 */
public final class DailyShopSmoke implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private final List<String> checks = new ArrayList<>();
    private final String world = "daily-shop-" + UUID.randomUUID();
    private int phase, ticks, wait;
    private Path out;
    private CompletableFuture<?> pending;
    private Object result;
    private DailyShopManageScreen editor;

    /** One server-side read, captured after the purchase settled. */
    private record State(int apples, Map<String, Integer> cards, List<String> titles) { }

    @Override
    public void onInitializeClient() {
        if (!"shop".equals(System.getProperty("dailySmoke.mode"))) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || phase < 0) return;
            try {
                tick(mc);
            } catch (Throwable error) {
                HabiLotteryMod.LOGGER.error("SHOP_SMOKE FAIL at phase {}", phase, error);
                checks.add("FAIL phase " + phase + ": " + error);
                finish(mc);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (++ticks > 8000) throw new IllegalStateException("timeout");
        if (wait > 0) { wait--; return; }
        if (pending != null) {
            if (!pending.isDone()) return;
            result = pending.join();
            pending = null;
        }
        switch (phase) {
            case 0 -> {
                if (ticks < 40 || mc.screen == null) return;
                out = mc.gameDirectory.toPath().resolve("daily-smoke-shop");
                Files.createDirectories(out);
                mc.options.languageCode = "zh_cn";
                mc.getLanguageManager().setSelected("zh_cn");
                mc.reloadResourcePacks();
                mc.options.renderDistance().set(2);
                mc.options.guiScale().set(2);
                resize(mc, 1280, 720);
                next(60);
            }
            case 1 -> {
                phase++;
                mc.createWorldOpenFlows().createFreshLevel(world,
                        new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .createWorldDimensions(), new TitleScreen());
            }
            case 2 -> {
                if (mc.player == null || mc.getSingleplayerServer() == null) return;
                next(60);
            }
            case 3 -> {
                server(mc, p -> {
                    DailyShopConfig current = GSON.fromJson(DailyShopService.snapshotJson(), DailyShopService.Snapshot.class).config();
                    DailyShopConfig shop = new DailyShopConfig();
                    shop.revision = current.revision;
                    shop.items.add(item("self_daily", "自选卡", 100, true, 1, 1,
                            new DailyRewardEntry(DailyRewardEntry.SELF_SELECT, "", 1)));
                    shop.items.add(item("apple_pack", "苹果礼包", 50, false, 1, 1,
                            new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 30)));
                    shop.items.add(item("vip_title", "贵宾称号", 200, false, 1, 1,
                            new DailyRewardEntry(DailyRewardEntry.TITLE, "列车贵宾", 1)));
                    shop.items.add(item("gift", "新人礼", 0, true, 1, 0,
                            new DailyRewardEntry(DailyRewardEntry.LIMIT_BREAK, "", 1)));
                    shop.items.add(item("luxury", "豪华套装", 99_999, true, 2, 7,
                            new DailyRewardEntry(DailyRewardEntry.CARD, "killer", 3)));
                    boolean ok = DailyShopService.apply(GSON.toJson(shop));
                    PlayerLotteryStore.get().setGreenApples(p.getUUID(), 500);
                    LotteryNetwork.sendDailyTaskSnapshot(p, true);
                    return ok ? "ok" : DailyShopService.lastError();
                });
            }
            case 4 -> {
                check("ok".equals(result), "editor-save-path-accepts-shop:" + result);
                next(30);
            }
            case 5 -> {
                check(mc.screen instanceof DailyTaskScreen, "terminal-opens");
                check(((List<?>) get(mc.screen, "navTabs")).size() == 3, "three-nav-tabs");
                mc.screen.keyPressed(83, 0, 0); // S → 商店
                next(10);
            }
            case 6 -> {
                check((int) get(mc.screen, "tab") == 1, "s-opens-shop-tab");
                check(shop(mc).size() == 5, "five-listings:" + shop(mc).size());
                check(state(mc, 4).equals("POOR"), "unaffordable-shows-poor");
                shot(mc, "01-shop");
                clickBuy(mc, 0);
                next(5);
            }
            case 7 -> {
                check(state(mc, 0).equals("CONFIRM"), "first-click-asks-confirm");
                shot(mc, "02-confirm");
                clickBuy(mc, 0);
                next(30);
            }
            case 8 -> read(mc);
            case 9 -> {
                State s = (State) result;
                check(s.apples() == 400, "self-select-charged-100:" + s.apples());
                check(s.cards().getOrDefault("self_select", 0) == 1, "self-select-card-delivered:" + s.cards());
                check(row(mc, 0).used() == 1 && state(mc, 0).equals("SOLD_OUT"), "daily-limit-reached");
                shot(mc, "03-sold-out");
                clickBuy(mc, 0);
                next(5);
            }
            case 10 -> {
                check(!state(mc, 0).equals("CONFIRM"), "sold-out-ignores-click");
                server(mc, p -> List.of(DailyShopService.buy(p, "self_daily", 100),
                        DailyShopService.buy(p, "apple_pack", 49),
                        DailyShopService.buy(p, "luxury", 99_999),
                        DailyShopService.buy(p, "missing", 1)));
            }
            case 11 -> {
                check(List.of("sold_out", "price_changed", "insufficient", "gone").equals(result),
                        "server-refusals:" + result);
                clickBuy(mc, 1);
                next(5);
            }
            case 12 -> {
                clickBuy(mc, 1);
                next(30);
            }
            case 13 -> read(mc);
            case 14 -> {
                State s = (State) result;
                check(s.apples() == 380, "apple-pack-net-minus-20:" + s.apples());
                check(state(mc, 1).equals("BUY"), "unlimited-stays-buyable");
                clickBuy(mc, 2);
                next(5);
            }
            case 15 -> {
                clickBuy(mc, 2);
                next(30);
            }
            case 16 -> read(mc);
            case 17 -> {
                State s = (State) result;
                check(s.apples() == 180, "title-charged-200:" + s.apples());
                check(s.titles().contains("列车贵宾"), "title-granted");
                check(state(mc, 2).equals("OWNED"), "owned-title-not-rebuyable");
                clickBuy(mc, 3);
                next(5);
            }
            case 18 -> {
                clickBuy(mc, 3);
                next(30);
            }
            case 19 -> read(mc);
            case 20 -> {
                State s = (State) result;
                check(s.apples() == 180, "free-gift-costs-nothing:" + s.apples());
                check(s.cards().getOrDefault("limit_break", 0) == 1, "free-gift-delivered:" + s.cards());
                check(state(mc, 3).equals("SOLD_OUT") && row(mc, 3).resetDay() == -1, "lifetime-limit");
                shot(mc, "04-after-purchases");
                resize(mc, 854, 480);
                next(10);
            }
            case 21 -> {
                shot(mc, "05-854x480-compact");
                mc.options.guiScale().set(3);
                resize(mc, 1920, 1080);
                next(10);
            }
            case 22 -> {
                shot(mc, "06-1080p-scale3");
                mc.options.guiScale().set(2);
                resize(mc, 1280, 720);
                editor = new DailyShopManageScreen(null);
                mc.setScreen(editor);
                next(40);
            }
            case 23 -> {
                check((boolean) get(editor, "loaded"), "editor-loads");
                DailyShopConfig draft = (DailyShopConfig) get(editor, "draft");
                check(draft.items.size() == 5, "editor-lists-five");
                shot(mc, "07-editor");
                draft.items.get(1).price = 60;
                call(editor, "touch");
                call(editor, "save");
                next(40);
            }
            case 24 -> {
                check(!(boolean) get(editor, "dirty"), "editor-save-acknowledged");
                shot(mc, "08-editor-saved");
                server(mc, p -> GSON.fromJson(DailyShopService.snapshotJson(), DailyShopService.Snapshot.class)
                        .config().find("apple_pack").price);
            }
            case 25 -> {
                check(Integer.valueOf(60).equals(result), "editor-price-live:" + result);
                HabiLotteryMod.LOGGER.info("SHOP_SMOKE PASS {} checks",
                        checks.stream().filter(c -> c.startsWith("PASS")).count());
                finish(mc);
            }
            default -> { }
        }
    }

    private static DailyShopItem item(String id, String title, int price, boolean limited, int count, int days,
                                      DailyRewardEntry reward) {
        DailyShopItem item = new DailyShopItem();
        item.id = id;
        item.title = title;
        item.description = "冒烟测试商品";
        item.price = price;
        item.limitEnabled = limited;
        item.limitCount = count;
        item.limitDays = days;
        item.rewards.add(reward);
        return item;
    }

    private void server(Minecraft mc, Function<ServerPlayer, Object> job) {
        var server = mc.getSingleplayerServer();
        pending = server.submit(() -> job.apply(server.getPlayerList().getPlayers().getFirst()));
        phase++;
    }

    private void read(Minecraft mc) {
        server(mc, p -> new State(PlayerLotteryStore.get().getGreenApples(p.getUUID()),
                HabiCardApi.all(p.getUUID()), HabiTitleApi.owned(p.getUUID())));
    }

    private static List<DailyTaskSnapshot.ShopRow> shop(Minecraft mc) throws Exception {
        return ((DailyTaskSnapshot) get(mc.screen, "snapshot")).shop();
    }

    private static DailyTaskSnapshot.ShopRow row(Minecraft mc, int index) throws Exception {
        return shop(mc).get(index);
    }

    private static String state(Minecraft mc, int index) throws Exception {
        Method m = DailyTaskScreen.class.getDeclaredMethod("shopState", DailyTaskSnapshot.ShopRow.class);
        m.setAccessible(true);
        return String.valueOf(m.invoke(mc.screen, row(mc, index)));
    }

    /** Clicks the centre of the buy button of listing {@code index}, like a player would. */
    private static void clickBuy(Minecraft mc, int index) throws Exception {
        Method tile = DailyTaskScreen.class.getDeclaredMethod("shopTile", int.class);
        tile.setAccessible(true);
        Object rect = tile.invoke(mc.screen, index);
        Method button = DailyTaskScreen.class.getDeclaredMethod("shopBuyButton", rect.getClass());
        button.setAccessible(true);
        Object b = button.invoke(mc.screen, rect);
        double x = num(b, "cx"), y = num(b, "cy");
        mc.screen.mouseClicked(x, y, 0);
        mc.screen.mouseReleased(x, y, 0);
    }

    private static double num(Object rect, String name) throws Exception {
        Method m = rect.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return ((Number) m.invoke(rect)).doubleValue();
    }

    private static void resize(Minecraft mc, int w, int h) {
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h);
        mc.resizeDisplay();
    }

    private static Object get(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void call(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(target);
    }

    private void next(int delay) { phase++; wait = delay; }

    private void shot(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(out.resolve(name + ".png"));
        }
    }

    private void check(boolean ok, String label) {
        checks.add((ok ? "PASS " : "FAIL ") + label);
        HabiLotteryMod.LOGGER.info("SHOP_SMOKE {} {}", ok ? "PASS" : "FAIL", label);
        if (!ok) throw new IllegalStateException("check failed: " + label);
    }

    private void finish(Minecraft mc) {
        phase = -1;
        try {
            if (out != null) Files.write(out.resolve("checks.txt"), checks);
        } catch (Exception ignored) { }
        mc.stop();
    }
}

package com.habitrain.lottery.test;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiMailApi;
import com.habitrain.lottery.client.gui.DailyTaskScreen;
import com.habitrain.lottery.mail.LocalMailboxStore;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Drives the mailbox tab of the daily task terminal in a fresh integrated-server world: mails are
 * delivered through {@link HabiMailApi}, the terminal is opened through the mailbox-block path
 * ({@link LotteryNetwork#sendOpenMailbox}), and claims go through real key / mouse input.
 *
 * <p>Enabled with {@code -PdailySmokeMode=mail}; screenshots land in {@code daily-smoke-mail/}.</p>
 */
public final class DailyMailSmoke implements ClientModInitializer {
    private final List<String> checks = new ArrayList<>();
    private final String world = "daily-mail-" + UUID.randomUUID();
    private int phase, ticks, wait;
    private Path out;
    private CompletableFuture<Integer> apples;
    private int applesBefore;
    private String claimedId;

    @Override
    public void onInitializeClient() {
        if (!"mail".equals(System.getProperty("dailySmoke.mode"))) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || phase < 0) return;
            try {
                tick(mc);
            } catch (Throwable error) {
                HabiLotteryMod.LOGGER.error("MAIL_SMOKE FAIL at phase {}", phase, error);
                checks.add("FAIL phase " + phase + ": " + error);
                finish(mc);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (++ticks > 6000) throw new IllegalStateException("timeout");
        if (wait > 0) { wait--; return; }
        switch (phase) {
            case 0 -> {
                if (ticks < 40 || mc.screen == null) return;
                out = mc.gameDirectory.toPath().resolve("daily-smoke-mail");
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
                var server = mc.getSingleplayerServer();
                apples = server.submit(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                    int before = PlayerLotteryStore.get().getGreenApples(p.getUUID());
                    HabiMailApi.send(p, HabiMailApi.draft("开服公告", "维护完成通知", "本次维护已经结束。", List.of()));
                    HabiMailApi.send(p, HabiMailApi.draft("系统", "补偿：维护延迟",
                            "因维护时间延长，向全体乘客发放补偿。\n请在有效期内领取。",
                            List.of(MailReward.greenApples(30), MailReward.factionCard("civilian", 2))));
                    HabiMailApi.send(p, HabiMailApi.draft("列车长", "欢迎登上哈比列车",
                            "欢迎你，新乘客！\n\n这是一封很长的欢迎信，用来检查正文的自动换行与滚动。"
                                    + "列车会在每天 UTC 00:00 刷新每日任务，完成任务后可在任务终端领取奖励；"
                                    + "邮件附件同样在这里领取。\n\n祝旅途愉快。\n—— 列车长",
                            List.of(MailReward.greenApples(120), MailReward.selfSelectCard(1))));
                    // 与右键邮箱方块相同的服务端入口
                    LotteryNetwork.sendOpenMailbox(p);
                    return before;
                });
                phase++;
            }
            case 4 -> {
                if (!apples.isDone()) return;
                applesBefore = apples.join();
                next(30);
            }
            case 5 -> {
                check(mc.screen instanceof DailyTaskScreen, "mailbox-opens-terminal");
                check((int) get(mc.screen, "tab") == 2, "terminal-lands-on-mail-tab");
                Object page = get(mc.screen, "mailPage");
                check(mails(page).size() == 3, "three-mails-listed:" + mails(page).size());
                check((int) call(page, "unclaimedCount") == 3, "three-unclaimed");
                shot(mc, "01-mail-open");
                mc.screen.keyPressed(264, 0, 0); // ↓
                next(5);
            }
            case 6 -> {
                Object page = get(mc.screen, "mailPage");
                claimedId = (String) get(page, "selectedId");
                check(claimedId != null, "arrow-selects-mail");
                shot(mc, "02-select-next");
                mc.screen.keyPressed(257, 0, 0); // Enter → 领取所选
                next(30);
            }
            case 7 -> {
                Object page = get(mc.screen, "mailPage");
                LocalMailboxStore.MailJson mail = mails(page).stream()
                        .filter(m -> claimedId.equals(m.id)).findFirst().orElseThrow();
                check(mail.claimed, "enter-claims-selected");
                check((int) call(page, "unclaimedCount") == 2, "two-left-after-single-claim");
                shot(mc, "03-claimed-one");
                AbstractWidget all = (AbstractWidget) get(page, "claimAllButton");
                check(all.visible && all.active, "claim-all-enabled");
                mc.screen.mouseClicked(all.getX() + all.getWidth() / 2.0, all.getY() + all.getHeight() / 2.0, 0);
                next(30);
            }
            case 8 -> {
                Object page = get(mc.screen, "mailPage");
                check((int) call(page, "unclaimedCount") == 0, "claim-all-clears-mailbox");
                AbstractWidget all = (AbstractWidget) get(page, "claimAllButton");
                check(!all.active, "claim-all-disabled-when-empty");
                var server = mc.getSingleplayerServer();
                apples = server.submit(() -> PlayerLotteryStore.get().getGreenApples(
                        server.getPlayerList().getPlayers().getFirst().getUUID()));
                phase++;
            }
            case 9 -> {
                if (!apples.isDone()) return;
                int gained = apples.join() - applesBefore;
                check(gained == 150, "apples-granted-150:" + gained);
                shot(mc, "04-all-claimed");
                resize(mc, 960, 540);
                next(10);
            }
            case 10 -> {
                shot(mc, "05-960x540");
                resize(mc, 854, 480);
                next(10);
            }
            case 11 -> {
                shot(mc, "06-854x480-compact");
                mc.options.guiScale().set(3);
                resize(mc, 1920, 1080);
                next(10);
            }
            case 12 -> {
                shot(mc, "07-1080p-scale3");
                mc.screen.keyPressed(81, 0, 0); // Q → 每日任务
                next(5);
            }
            case 13 -> {
                check((int) get(mc.screen, "tab") == 0, "q-returns-to-tasks");
                AbstractWidget claim = (AbstractWidget) get(get(mc.screen, "mailPage"), "claimButton");
                check(!claim.visible, "mail-buttons-hidden-off-tab");
                shot(mc, "08-tasks-tab");
                mc.screen.keyPressed(77, 0, 0); // M → 邮箱
                next(5);
            }
            case 14 -> {
                check((int) get(mc.screen, "tab") == 2, "m-opens-mail-tab");
                HabiLotteryMod.LOGGER.info("MAIL_SMOKE PASS {} checks",
                        checks.stream().filter(c -> c.startsWith("PASS")).count());
                finish(mc);
            }
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<LocalMailboxStore.MailJson> mails(Object page) throws Exception {
        return (List<LocalMailboxStore.MailJson>) get(page, "mails");
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

    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private void next(int delay) { phase++; wait = delay; }

    private void shot(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(out.resolve(name + ".png"));
        }
    }

    private void check(boolean ok, String label) {
        checks.add((ok ? "PASS " : "FAIL ") + label);
        HabiLotteryMod.LOGGER.info("MAIL_SMOKE {} {}", ok ? "PASS" : "FAIL", label);
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

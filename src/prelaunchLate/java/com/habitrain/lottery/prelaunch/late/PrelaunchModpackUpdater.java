package com.habitrain.lottery.prelaunch.late;

import com.habitrain.lottery.prelaunch.GameRestart;
import com.habitrain.lottery.prelaunch.PrelaunchCooldown;
import com.habitrain.lottery.prelaunch.PrelaunchLog;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.compat.crashassistant.ProcessSignalIO;
import pl.skidam.automodpack_loader_core.screen.PreloadScreenImpl;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.screen.ScreenService;

/** Download/apply using AutoModpack, then restart a fresh JVM instead of loading mods into this one. */
final class PrelaunchModpackUpdater extends ModpackUpdater {
    private final Path configDir;
    private final GameRestart restart;
    private final ProgressWindow progress;
    private final Jsons.ModpackContentFields content;
    private boolean successful;

    PrelaunchModpackUpdater(Jsons.ModpackContentFields content, Jsons.ModpackAddresses addresses,
                           Secrets.Secret secret, Path directory, ProgressWindow progress) {
        super(content, addresses, secret, directory);
        this.content = content;
        this.progress = progress;
        FabricLoader loader = FabricLoader.getInstance();
        configDir = loader.getConfigDir();
        GameRestart captured = null;
        try {
            String currentLoader = loader.getModContainer("fabricloader").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Jsons.ModpackContentFields launchContent = content != null ? content : ConfigTools.loadModpackContent(
                    directory.resolve(GlobalVariables.hostModpackContentFile.getFileName()));
            if (launchContent != null && launchContent.loaderVersion != null && !launchContent.loaderVersion.isBlank()
                    && !launchContent.loaderVersion.equals(currentLoader)) {
                throw new IllegalStateException("Loader version changes require a new launch from the launcher");
            }
            if (launchContent != null && launchContent.mcVersion != null && !launchContent.mcVersion.isBlank()
                    && !launchContent.mcVersion.equals(GlobalVariables.MC_VERSION)) {
                throw new IllegalStateException("Minecraft version changes require a new launch from the launcher");
            }
            captured = GameRestart.capture(loader.getGameDir(), configDir, loader.getLaunchArguments(false));
        } catch (Exception e) {
            PrelaunchLog.warn("Cannot capture automatic restart (%s); successful updates will request a launcher restart",
                    e.getClass().getSimpleName());
        }
        restart = captured;
    }

    boolean successful() {
        return successful;
    }

    @Override
    public void processModpackUpdate(ModpackUtils.UpdateCheckResult ignored) {
        successful = false;
        try {
            Path directory = directory();
            Jsons.ModpackContentFields latest = effectiveContent(this, content);
            var result = latest == null ? null : ModpackFileAudit.check(latest, directory);
            if (result != null && result.requiresUpdate()) {
                // Preserve AutoModpack's public-JAR approval policy on its automatic/default path.
                super.processModpackUpdate(result);
                return;
            }
            var before = installedState();
            initializeDownload(this);
            if (latest != null) {
                Files.writeString((Path) field("modpackContentFile").get(this),
                        (String) field("serverModpackContentJson").get(this));
            }
            applyInstalled(this);
            successful = true;
            completeOrLoad(before);
        } catch (Exception e) {
            successful = false;
            PrelaunchLog.error("Could not apply the installed modpack", e);
        }
    }

    @Override
    public void startUpdate(Set<Jsons.ModpackContentFields.ModpackContentItem> files) {
        successful = false;
        ProgressWindow window = progress != null ? progress : ProgressWindow.open("正在准备更新整合包…");
        boolean completed = false;
        Map<String, String> before = null;
        try {
            before = installedState();
            initializeDownload(this);
            window.follow(() -> downloadManager);
            completed = !window.isCancelled() && runUpdate(() -> super.startUpdate(files)) && failedDownloads.isEmpty();
            Jsons.ModpackContentFields latest = effectiveContent(this, content);
            if (completed && latest != null && ModpackFileAudit.check(latest, directory()).requiresUpdate()) {
                PrelaunchLog.warn("AutoModpack completed, but installed files do not match the server manifest");
                completed = false;
            }
        } catch (Exception e) {
            completed = false;
            PrelaunchLog.error("Prelaunch installation failed", e);
        } finally {
            window.close();
        }
        if (!completed) {
            if (!window.isCancelled()) {
                Ui.error("整合包更新未成功完成，游戏不会自动重启。<br>"
                        + "请检查网络后重试；详情见 logs/latest.log。<br>本次不会开启 5 分钟免弹窗。");
            }
            return;
        }
        successful = true;
        try {
            completeOrLoad(before);
        } catch (Exception e) {
            successful = false;
            PrelaunchLog.error("Could not finish the installed modpack", e);
        }
    }

    static Jsons.ModpackContentFields effectiveContent(ModpackUpdater updater, Jsons.ModpackContentFields fallback) {
        if (updater != null) {
            try {
                Jsons.ModpackContentFields refreshed = (Jsons.ModpackContentFields) field("serverModpackContent").get(updater);
                if (refreshed != null) {
                    return refreshed;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return fallback;
    }

    private Path directory() throws ReflectiveOperationException {
        return (Path) field("modpackDir").get(this);
    }

    private void completeOrLoad(Map<String, String> before) throws Exception {
        Path directory = directory();
        if (!before.equals(installedState())) {
            finishUpdate();
            return;
        }
        // A callback or a rewritten manifest alone is not evidence that a mod was changed.
        PrelaunchLog.info("No installed file contents changed; loading the modpack without exiting or renewing cooldown");
        loadInstalledMods(directory, GlobalVariables.MODS_DIR);
    }

    private Map<String, String> installedState() throws Exception {
        Map<String, String> state = new HashMap<>(ModpackFileAudit.snapshot(directory(), GlobalVariables.MODS_DIR));
        // Switching to an already cached pack can replace the loaded mod set without downloading.
        if (GlobalVariables.clientConfig != null) {
            state.put("@selected", String.valueOf(GlobalVariables.clientConfig.selectedModpack));
        }
        Path manifest = directory().resolve(GlobalVariables.hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields installed = Files.isRegularFile(manifest) ? ConfigTools.loadModpackContent(manifest) : null;
        state.put("@loader", version(installed == null ? null : installed.loader, GlobalVariables.LOADER));
        state.put("@loaderVersion", version(installed == null ? null : installed.loaderVersion, GlobalVariables.LOADER_VERSION));
        state.put("@mcVersion", version(installed == null ? null : installed.mcVersion, GlobalVariables.MC_VERSION));
        return state;
    }

    private static String version(String requested, String current) {
        return requested == null || requested.isBlank() ? String.valueOf(current) : requested;
    }

    /** Keep the upstream apply step, but never enter its own blocking restart/exit dialog. */
    static void applyInstalled(ModpackUpdater updater) throws Exception {
        var apply = ModpackUpdater.class.getDeclaredMethod("applyModpack");
        apply.setAccessible(true);
        try {
            apply.invoke(updater);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    /** Mirrors 4.0.6 CheckAndLoadModpack's load-only tail, after apply has already succeeded. */
    static void loadInstalledMods(Path directory, Path modsDirectory) throws Exception {
        Set<String> installedNames = new HashSet<>();
        if (modsDirectory != null && Files.isDirectory(modsDirectory)) {
            try (var paths = Files.list(modsDirectory)) {
                paths.map(path -> path.getFileName().toString()).forEach(installedNames::add);
            }
        }
        List<Path> mods = List.of();
        Path packMods = directory.resolve("mods");
        if (Files.isDirectory(packMods)) {
            try (var paths = Files.list(packMods)) {
                mods = paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jar"))
                        .filter(path -> !installedNames.contains(path.getFileName().toString())).toList();
            }
        }
        GlobalVariables.MODPACK_LOADER.loadModpack(mods);
    }

    /** startUpdate() is also called directly after the player has approved non-public JARs. */
    static void initializeDownload(ModpackUpdater updater) throws Exception {
        Path directory = (Path) field("modpackDir").get(updater);
        Object content = field("serverModpackContent").get(updater);
        Files.createDirectories(directory);
        field("modpackContentFile").set(updater, directory.resolve(GlobalVariables.hostModpackContentFile.getFileName()));
        // The old direct call initialized only the path, leaving this null at Files.writeString().
        field("serverModpackContentJson").set(updater, ConfigTools.GSON.toJson(content));
    }

    static boolean runUpdate(Runnable update) {
        Boolean originalPreload = GlobalVariables.preload;
        ScreenService originalScreen = ScreenManager.INSTANCE;
        CompletionScreen screen = new CompletionScreen();
        try {
            // Use the download/apply-only branch. Its restart callback is reached only after a
            // successful install; preload's branch may load new mods or block in its Swing dialog.
            GlobalVariables.preload = false;
            ScreenManager.INSTANCE = screen;
            update.run();
            return screen.completed && !screen.failed;
        } finally {
            ScreenManager.INSTANCE = originalScreen;
            GlobalVariables.preload = originalPreload;
        }
    }

    private void finishUpdate() {
        boolean cooldownSaved = false;
        try {
            PrelaunchCooldown.recordSuccess(configDir, System.currentTimeMillis());
            cooldownSaved = true;
            if (restart == null) throw new IllegalStateException("Restart command unavailable");
            try (GameRestart.Prepared prepared = restart.prepare()) {
                prepared.commit();
            }
            PrelaunchLog.info("Update installed; exiting this JVM and restarting after it fully stops (picker suppressed for 5 minutes)");
        } catch (Exception e) {
            PrelaunchLog.warn("Update installed, but automatic restart is unavailable (%s)", e.getClass().getSimpleName());
            try {
                Ui.noticeBeforeExit("整合包已安装完成，但当前启动方式无法自动重新启动。<br>"
                    + "游戏将在 5 秒后自动退出，请回到你使用的启动器重新启动游戏。<br>"
                    + (cooldownSaved
                    ? "更换 Fabric 版本或使用特殊启动方式时，需要由启动器重新生成启动参数。"
                    : "无法保存 5 分钟免弹窗记录，请检查 config 目录是否可写。"));
            } catch (RuntimeException | LinkageError uiFailure) {
                PrelaunchLog.warn("Could not show the exit notice: %s", uiFailure.getClass().getSimpleName());
            }
        }
        try {
            ProcessSignalIO.post("normal_stop");
        } catch (LinkageError | RuntimeException e) {
            PrelaunchLog.warn("Could not post normal-stop signal: %s", e.getClass().getSimpleName());
        }
        System.exit(0);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = ModpackUpdater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class CompletionScreen extends PreloadScreenImpl {
        private volatile boolean completed;
        private volatile boolean failed;

        @Override
        public Optional<String> getScreenString() {
            return Optional.of("habitrain_prelaunch");
        }

        @Override
        public void restart(Object... args) {
            completed = true;
        }

        @Override
        public void error(String... args) {
            failed = true;
            PrelaunchLog.warn("AutoModpack reported an installation error (see AutoModpack logs)");
        }
    }
}

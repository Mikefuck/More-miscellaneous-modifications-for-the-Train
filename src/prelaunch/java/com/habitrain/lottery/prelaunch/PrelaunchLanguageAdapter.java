package com.habitrain.lottery.prelaunch;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;

/**
 * Early hook, runs before AutoModpack's own preload.
 *
 * <p>Fabric instantiates language adapters in mod-id order (production builds sort the resolved
 * mod list by id), and AutoModpack runs its whole preload inside the constructor of the adapter
 * declared by mod {@code automodpack}. This nested mod is therefore named
 * {@code a_habitrain_prelaunch}. It only switches AutoModpack's own launch update off for this
 * launch; the picker and the actual update run in {@code habitrain_prelaunch_late}, which sorts
 * after AutoModpack and can use its initialised state. Minecraft classes must not be touched here,
 * mixins are not ready yet.
 */
public final class PrelaunchLanguageAdapter implements LanguageAdapter {

    /** Set when the late adapter has to show the picker and take over AutoModpack's update. */
    public static final String ACTIVE_PROPERTY = "habitrain.prelaunch.active";
    /** AutoModpack internals the late half is compiled against. */
    public static final String SUPPORTED_AUTOMODPACK = "4.0.";

    public PrelaunchLanguageAdapter() {
        try {
            run();
        } catch (Throwable t) {
            // Never block the game from starting because of the picker.
            PrelaunchLog.error("Prelaunch server picker failed, continuing without it", t);
        }
    }

    private static void run() throws Exception {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.getEnvironmentType() != EnvType.CLIENT || !loader.isModLoaded("automodpack")
                || !loader.isModLoaded("habitrain_prelaunch_late")) {
            return;
        }
        // A previous launch may have crashed between this adapter and the restore.
        AutoModpackState.restoreFlagFile();

        String version = loader.getModContainer("automodpack")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
        if (!version.startsWith(SUPPORTED_AUTOMODPACK)) {
            // The late half drives AutoModpack internals and is only built against 4.0.x.
            PrelaunchLog.warn("AutoModpack %s is not supported by the prelaunch picker (built for %sx)",
                    version, SUPPORTED_AUTOMODPACK);
            return;
        }

        PrelaunchConfig config = PrelaunchConfig.load(loader.getConfigDir());
        if (!config.enabled) {
            PrelaunchLog.info("Prelaunch server picker disabled in config/habitrain_prelaunch.json");
            return;
        }
        if (!hasAwtSupport()) {
            PrelaunchLog.info("No usable AWT, skipping prelaunch server picker");
            return;
        }
        boolean hasServers = !ServerListReader.read(loader.getGameDir().resolve("servers.dat")).isEmpty();
        if (!hasServers && !AutoModpackState.hasInstalledModpack()) {
            PrelaunchLog.info("Multiplayer list is empty and no AutoModpack modpack installed, nothing to pick");
            return;
        }
        AutoModpackState.suppressLaunchUpdate();
        System.setProperty(ACTIVE_PROPERTY, "true");
    }

    private static boolean hasAwtSupport() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        // On macOS with -XstartOnFirstThread (LWJGL) AWT windows deadlock; AutoModpack checks the same.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            for (String key : System.getenv().keySet()) {
                if (key.startsWith("JAVA_STARTED_ON_FIRST_THREAD_")) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) {
        throw new UnsupportedOperationException("habitrain_prelaunch adapter has no entrypoints");
    }
}

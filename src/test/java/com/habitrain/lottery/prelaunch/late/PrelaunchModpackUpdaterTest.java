package com.habitrain.lottery.prelaunch.late;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

class PrelaunchModpackUpdaterTest {
    @TempDir Path root;

    @Test void directDownloadInitializesTheManifestJsonAndCreatesTheDirectory() throws Exception {
        var content = new Jsons.ModpackContentFields();
        content.modpackName = "test-pack";
        var address = new InetSocketAddress("localhost", 25565);
        Path directory = root.resolve("new-pack");
        var updater = new ModpackUpdater(content, new Jsons.ModpackAddresses(address, address, true), null, directory);
        Path original = GlobalVariables.hostModpackContentFile;
        try {
            GlobalVariables.hostModpackContentFile = Path.of("modpack-content.json");
            assertNull(value(updater, "serverModpackContentJson"));
            PrelaunchModpackUpdater.initializeDownload(updater);
            assertTrue(Files.isDirectory(directory));
            assertEquals(directory.resolve("modpack-content.json"), value(updater, "modpackContentFile"));
            assertTrue(((String) value(updater, "serverModpackContentJson")).contains("test-pack"));
        } finally {
            GlobalVariables.hostModpackContentFile = original;
        }
    }

    @Test void successfulApplyIsRequiredAndGlobalsAreAlwaysRestored() {
        Boolean preload = GlobalVariables.preload;
        var original = ScreenManager.INSTANCE;
        assertFalse(PrelaunchModpackUpdater.runUpdate(() -> { })); // early return/network failure
        assertTrue(PrelaunchModpackUpdater.runUpdate(() -> {
            assertEquals(Boolean.FALSE, GlobalVariables.preload);
            new ScreenManager().restart();
        }));
        assertFalse(PrelaunchModpackUpdater.runUpdate(() -> {
            new ScreenManager().error("failed");
            new ScreenManager().restart();
        }));
        assertThrows(IllegalStateException.class, () -> PrelaunchModpackUpdater.runUpdate(() -> {
            throw new IllegalStateException("apply failed");
        }));
        assertSame(original, ScreenManager.INSTANCE);
        assertEquals(preload, GlobalVariables.preload);
    }

    @Test void loadOnlyBridgeIncludesCachedModsAndExcludesStandardFolderDuplicates() throws Exception {
        Path pack = root.resolve("pack");
        Path mods = root.resolve("mods");
        Files.createDirectories(pack.resolve("mods"));
        Files.createDirectories(mods);
        Path cached = Files.writeString(pack.resolve("mods/cached.jar"), "cached");
        Files.writeString(pack.resolve("mods/duplicate.jar"), "duplicate");
        Files.writeString(mods.resolve("duplicate.jar"), "duplicate");
        Files.writeString(pack.resolve("mods/readme.txt"), "ignored");
        Files.createDirectories(pack.resolve("mods/folder.jar"));
        var original = GlobalVariables.MODPACK_LOADER;
        AtomicReference<List<Path>> loaded = new AtomicReference<>();
        try {
            GlobalVariables.MODPACK_LOADER = new ModpackLoaderService() {
                public void loadModpack(List<Path> paths) { loaded.set(paths); }
                public List<FileInspection.Mod> getModpackNestedConflicts(Path directory) { return List.of(); }
            };
            PrelaunchModpackUpdater.loadInstalledMods(pack, mods);
            assertEquals(List.of(cached), loaded.get());
            PrelaunchModpackUpdater.loadInstalledMods(root.resolve("missing-pack"), null);
            assertEquals(List.of(), loaded.get());
        } finally {
            GlobalVariables.MODPACK_LOADER = original;
        }
    }

    private static Object value(ModpackUpdater updater, String name) throws Exception {
        Field field = ModpackUpdater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(updater);
    }
}

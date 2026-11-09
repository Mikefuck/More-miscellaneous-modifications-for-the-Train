package com.habitrain.lottery.prelaunch.late;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;

/**
 * Regression coverage for Audit B-14 (AutoModpack removeDupeMods + refreshed serverModpackContent),
 * I-18 (InvalidPathException -> IOException in ModpackFileAudit.resolve), and N-10 (Ui.escape).
 */
class ModpackFileAuditDupeModsTest {

    @TempDir
    Path root;

    @Test
    void deduplicatedModInGlobalModsDirSatisfiesAuditBySha1EvenWhenRenamed() throws Exception {
        Path packDir = root.resolve("pack");
        Path globalModsDir = root.resolve("mods");
        Files.createDirectories(packDir.resolve("mods"));
        Files.createDirectories(globalModsDir);

        byte[] jarBytes = createFabricModJar("automodpack", "4.0.6");
        Files.write(globalModsDir.resolve("AutoModpack-CustomRenamed.jar"), jarBytes);

        var expectedItem = item("/mods/automodpack-mc1.21.1-fabric-4.0.6.jar", jarBytes, false);
        var result = ModpackFileAudit.check(content(expectedItem), packDir, globalModsDir);
        assertFalse(result.requiresUpdate(), "Global mod with matching SHA-1 must satisfy deduplicated modpack item");
        assertTrue(result.filesToUpdate().isEmpty());
    }

    @Test
    void sameIdAndVersionWithDifferentContentsStillRequiresServerJar() throws Exception {
        Path packDir = root.resolve("pack");
        Path globalModsDir = root.resolve("mods");
        Files.createDirectories(packDir.resolve("mods"));
        Files.createDirectories(globalModsDir);

        byte[] serverJarBytes = createFabricModJarWithExtra("habitrain_lottery", "1.1.63", "server-repackaged");
        byte[] localSameVersionBytes = createFabricModJarWithExtra("habitrain_lottery", "1.1.63", "local-build");
        assertNotEquals(sha1(serverJarBytes), sha1(localSameVersionBytes));

        Path localJar = globalModsDir.resolve("local-dev.jar");
        Files.write(localJar, localSameVersionBytes);

        var expectedItem = item("/mods/habitrain-lottery-1.1.63.jar", serverJarBytes, false);
        var matchResult = ModpackFileAudit.check(content(expectedItem), packDir, globalModsDir);
        assertTrue(matchResult.requiresUpdate(),
                "The server's exact non-editable content is required even for the same ID/version");
        assertEquals(Set.of(expectedItem), matchResult.filesToUpdate());

        // Older version in global mods/ must NOT satisfy a newer server version.
        Files.write(localJar, createFabricModJarWithExtra("habitrain_lottery", "1.1.62", "older-build"));
        var mismatchResult = ModpackFileAudit.check(content(expectedItem), packDir, globalModsDir);
        assertTrue(mismatchResult.requiresUpdate());
        assertEquals(Set.of(expectedItem), mismatchResult.filesToUpdate());
    }

    @Test
    void nonModFilesAndSubdirectoryJarsAreNeverSatisfiedByGlobalModsDir() throws Exception {
        Path packDir = root.resolve("pack");
        Path globalModsDir = root.resolve("mods");
        Files.createDirectories(packDir);
        Files.createDirectories(globalModsDir);

        byte[] payload = createFabricModJar("some_mod", "1.0.0");
        Files.write(globalModsDir.resolve("some_mod-1.0.0.jar"), payload);

        var configItem = item("/config/some_mod-1.0.0.jar", payload, false);
        var nestedModItem = item("/mods/nested/some_mod-1.0.0.jar", payload, false);

        var result = ModpackFileAudit.check(content(configItem, nestedModItem), packDir, globalModsDir);
        assertTrue(result.requiresUpdate());
        assertEquals(Set.of(configItem, nestedModItem), result.filesToUpdate());
    }

    @Test
    void effectiveContentPrefersRefreshedServerModpackContentOverStaleConstructorManifest() throws Exception {
        var stale = content(item("/mods/old.jar", "old".getBytes(StandardCharsets.UTF_8), false));
        var refreshed = content(item("/mods/new.jar", "new".getBytes(StandardCharsets.UTF_8), false));
        var address = new InetSocketAddress("127.0.0.1", 25565);
        var updater = new ModpackUpdater(stale, new Jsons.ModpackAddresses(address, address, true), null, root.resolve("pack"));

        Field serverContentField = ModpackUpdater.class.getDeclaredField("serverModpackContent");
        serverContentField.setAccessible(true);
        serverContentField.set(updater, refreshed);

        assertSame(refreshed, PrelaunchModpackUpdater.effectiveContent(updater, stale));

        serverContentField.set(updater, null);
        assertSame(stale, PrelaunchModpackUpdater.effectiveContent(updater, stale));
        assertSame(stale, PrelaunchModpackUpdater.effectiveContent(null, stale));
    }

    @Test
    void resolveConvertsInvalidPathCharactersToIoExceptionAndUiEscapesNullAndQuotes() {
        assertThrows(IOException.class, () -> ModpackFileAudit.resolve(root, "/mods/bad\0name.jar"));
        assertThrows(IOException.class, () -> ModpackFileAudit.resolve(root, "/mods/drive:colon.jar"));
        assertThrows(IOException.class, () -> ModpackFileAudit.resolve(root, "   "));

        assertEquals("", Ui.escape(null));
        assertEquals("&lt;tag attr=&quot;a&quot; single=&#39;b&#39;&gt;&amp;",
                Ui.escape("<tag attr=\"a\" single='b'>&"));

        assertDoesNotThrow(() -> PrelaunchUpdater.rethrowIfFatalJvmError(new StackOverflowError("deep json")));
        assertDoesNotThrow(() -> PrelaunchUpdater.rethrowIfFatalJvmError(new NoClassDefFoundError("optional")));
        assertThrows(OutOfMemoryError.class,
                () -> PrelaunchUpdater.rethrowIfFatalJvmError(new OutOfMemoryError("fatal")));
    }

    private static byte[] createFabricModJar(String id, String version) throws IOException {
        return createFabricModJarWithExtra(id, version, "");
    }

    private static byte[] createFabricModJarWithExtra(String id, String version, String extraComment) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                    + "\",\"description\":\"" + extraComment + "\"}";
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Jsons.ModpackContentFields content(Jsons.ModpackContentFields.ModpackContentItem... items) {
        return new Jsons.ModpackContentFields(Set.of(items));
    }

    private static Jsons.ModpackContentFields.ModpackContentItem item(String name, byte[] bytes, boolean editable) throws Exception {
        return new Jsons.ModpackContentFields.ModpackContentItem(
                name, String.valueOf(bytes.length), "mod", editable, false, sha1(bytes), "");
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }
}

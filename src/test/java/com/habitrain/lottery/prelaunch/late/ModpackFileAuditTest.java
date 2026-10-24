package com.habitrain.lottery.prelaunch.late;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

class ModpackFileAuditTest {
    @TempDir Path root;

    @Test void identicalFilesNeedNoDownloadEvenWithoutALocalManifest() throws Exception {
        var file = item("/mods/中文 mod.jar", "same", false);
        write(file.file, "same");
        var result = ModpackFileAudit.check(content(file), root);
        assertFalse(result.requiresUpdate());
        assertTrue(result.filesToUpdate().isEmpty());
    }

    @Test void changedContentsAreFoundEvenWithIdenticalSizeAndTimestamp() throws Exception {
        var file = item("/mods/a.jar", "new!", false);
        Path installed = write(file.file, "old!");
        FileTime time = Files.getLastModifiedTime(installed);
        Files.writeString(installed, "bad!");
        Files.setLastModifiedTime(installed, time);
        assertEquals(Set.of(file), ModpackFileAudit.check(content(file), root).filesToUpdate());
        Files.writeString(installed, "new!");
        Files.setLastModifiedTime(installed, time);
        assertFalse(ModpackFileAudit.check(content(file), root).requiresUpdate());
    }

    @Test void editableFilesKeepPlayerChangesButMissingFilesStillDownload() throws Exception {
        var editable = item("/config/player.json", "default", true);
        var missing = item("/config/missing.json", "default", true);
        write(editable.file, "player's configuration");
        assertEquals(Set.of(missing), ModpackFileAudit.check(content(editable, missing), root).filesToUpdate());
    }

    @Test void actualDeletionNeedsApplyButStaleManifestAloneDoesNot() throws Exception {
        var old = item("/mods/old.jar", "old", false);
        saveManifest(content(old));
        Path installed = write(old.file, "old");
        var result = ModpackFileAudit.check(content(), root);
        assertTrue(result.requiresUpdate());
        assertTrue(result.filesToUpdate().isEmpty(), "deletion-only updates must not need downloads");
        Files.delete(installed);
        assertFalse(ModpackFileAudit.check(content(), root).requiresUpdate());
    }

    @Test void renamedModIsDetectedEvenWhenItsContentsMatch() throws Exception {
        var old = item("/mods/old.jar", "same", false);
        var renamed = item("/mods/new.jar", "same", false);
        write(old.file, "same");
        saveManifest(content(old));
        assertEquals(Set.of(renamed), ModpackFileAudit.check(content(renamed), root).filesToUpdate());
    }

    @Test void callbackAndManifestRewriteCannotMasqueradeAsAnInstalledFileChange() throws Exception {
        var file = item("/mods/a.jar", "same", false);
        write(file.file, "same");
        saveManifest(content(file));
        var before = ModpackFileAudit.snapshot(root, null);
        Files.writeString(manifest(), "\n" + Files.readString(manifest()) + "\n");
        assertEquals(before, ModpackFileAudit.snapshot(root, null));
        Files.setLastModifiedTime(ModpackFileAudit.resolve(root, file.file), FileTime.fromMillis(1));
        assertEquals(before, ModpackFileAudit.snapshot(root, null));
    }

    @Test void snapshotDetectsAddedChangedAndRemovedModsIncludingTheStandardModsFolder() throws Exception {
        Path pack = root.resolve("pack");
        Path mods = root.resolve("mods");
        Files.createDirectories(pack);
        Files.createDirectories(mods);
        Path file = mods.resolve("a.jar");
        var empty = ModpackFileAudit.snapshot(pack, mods);
        Files.writeString(file, "old");
        var old = ModpackFileAudit.snapshot(pack, mods);
        assertNotEquals(empty, old);
        Files.writeString(file, "new");
        assertNotEquals(old, ModpackFileAudit.snapshot(pack, mods));
        Files.delete(file);
        assertEquals(empty, ModpackFileAudit.snapshot(pack, mods));
    }

    @Test void corruptOrIncompleteDownloadCannotPassPostInstallVerification() throws Exception {
        var file = item("/mods/a.jar", "complete", false);
        saveManifest(content(file));
        write(file.file, "partial");
        assertTrue(ModpackFileAudit.check(content(file), root).requiresUpdate());
        Files.delete(ModpackFileAudit.resolve(root, file.file));
        assertTrue(ModpackFileAudit.check(content(file), root).requiresUpdate());
    }

    @Test void rejectsInvalidHashesTraversalAndDuplicateNormalizedPaths() throws Exception {
        assertThrows(Exception.class, () -> ModpackFileAudit.resolve(root, "/../escape.jar"));
        assertThrows(Exception.class, () -> ModpackFileAudit.resolve(root, "C:/escape.jar"));
        var invalid = new Jsons.ModpackContentFields.ModpackContentItem("/mods/a.jar", "4", "mod", false, false, "bad", "");
        assertThrows(Exception.class, () -> ModpackFileAudit.check(content(invalid), root));
        assertThrows(Exception.class, () -> ModpackFileAudit.check(
                content(item("mods/a.jar", "same", false), item("/mods/a.jar", "same", false)), root));
    }

    private Path write(String name, String text) throws Exception {
        Path path = ModpackFileAudit.resolve(root, name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }

    private Path manifest() {
        return root.resolve(GlobalVariables.hostModpackContentFile.getFileName());
    }

    private void saveManifest(Jsons.ModpackContentFields content) throws Exception {
        Files.writeString(manifest(), ConfigTools.GSON.toJson(content));
    }

    private static Jsons.ModpackContentFields content(Jsons.ModpackContentFields.ModpackContentItem... items) {
        return new Jsons.ModpackContentFields(Set.of(items));
    }

    private static Jsons.ModpackContentFields.ModpackContentItem item(String name, String text, boolean editable) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        return new Jsons.ModpackContentFields.ModpackContentItem(name, String.valueOf(bytes.length), "mod", editable, false, hash, "");
    }
}

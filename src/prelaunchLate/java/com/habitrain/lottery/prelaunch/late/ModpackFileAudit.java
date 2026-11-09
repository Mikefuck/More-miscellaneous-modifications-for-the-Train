package com.habitrain.lottery.prelaunch.late;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;

/** Reads file contents, independently of AutoModpack's cached hashes and completion callback. */
final class ModpackFileAudit {
    private ModpackFileAudit() { }

    static ModpackUtils.UpdateCheckResult check(Jsons.ModpackContentFields content, Path directory) throws IOException {
        return check(content, directory, GlobalVariables.MODS_DIR);
    }

    static ModpackUtils.UpdateCheckResult check(Jsons.ModpackContentFields content, Path directory,
                                                Path globalModsDir) throws IOException {
        if (content == null || content.list == null) throw new IOException("Missing server file list");
        Path base = directory.toAbsolutePath().normalize();
        Set<Jsons.ModpackContentFields.ModpackContentItem> downloads = new HashSet<>();
        Set<Path> expected = new HashSet<>();
        Set<String> globalHashes = Set.of();
        boolean globalIndexed = false;
        for (var item : content.list) {
            Path file = resolve(directory, item.file);
            if (!expected.add(file)) throw new IOException("Duplicate modpack file path");
            if (item.sha1 == null || !item.sha1.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("Invalid server file hash");
            }
            if (Files.isRegularFile(file)) {
                if (!item.editable && !item.sha1.equalsIgnoreCase(hash(file))) {
                    downloads.add(item);
                }
                continue;
            }
            if (!item.editable && isTopLevelModJar(base, file)) {
                if (!globalIndexed) {
                    globalHashes = scanGlobalHashes(globalModsDir, base.resolve("mods"));
                    globalIndexed = true;
                }
                // Deduplication may rename a global JAR; only identical contents satisfy the manifest.
                // ID/version alone cannot distinguish a same-version server hotfix.
                if (globalHashes.contains(item.sha1.toLowerCase(Locale.ROOT))) continue;
            }
            downloads.add(item);
        }
        boolean removed = false;
        Path manifest = directory.resolve(GlobalVariables.hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields previous = Files.isRegularFile(manifest) ? ConfigTools.loadModpackContent(manifest) : null;
        if (previous != null && previous.list != null) {
            for (var item : previous.list) {
                Path file = resolve(directory, item.file);
                if (!expected.contains(file) && Files.isRegularFile(file)) removed = true;
            }
        }
        return new ModpackUtils.UpdateCheckResult(!downloads.isEmpty() || removed, Set.copyOf(downloads));
    }

    private static boolean isTopLevelModJar(Path base, Path file) {
        String rel = base.relativize(file).toString().replace('\\', '/');
        return rel.startsWith("mods/")
                && rel.indexOf('/', 5) < 0
                && rel.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    static Path resolve(Path directory, String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Missing modpack file path");
        // AutoModpack manifests use game-relative paths, commonly with a leading slash.
        String relative = name.replace('\\', '/').replaceFirst("^/+", "");
        if (relative.indexOf('\0') >= 0 || relative.indexOf(':') >= 0) {
            throw new IOException("Invalid modpack file path");
        }
        Path base = directory.toAbsolutePath().normalize();
        final Path path;
        try {
            path = base.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Invalid modpack file path", e);
        }
        if (path.equals(base) || !path.startsWith(base)) throw new IOException("Invalid modpack file path");
        return path;
    }

    static Map<String, String> snapshot(Path directory, Path modsDirectory) throws IOException {
        Map<String, String> files = new HashMap<>();
        collect(files, "pack/", directory, false);
        if (modsDirectory != null) collect(files, "mods/", modsDirectory, true);
        return Map.copyOf(files);
    }

    private static void collect(Map<String, String> files, String prefix, Path directory, boolean jarsOnly) throws IOException {
        if (!Files.exists(directory)) return;
        String manifest = GlobalVariables.hostModpackContentFile.getFileName().toString();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = directory.relativize(path).toString().replace('\\', '/');
                if ((jarsOnly && !name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                        || (!jarsOnly && name.equals(manifest))) continue;
                files.put(prefix + name, hash(path));
            }
        }
    }

    static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> scanGlobalHashes(Path globalModsDir, Path packModsDir) throws IOException {
        if (globalModsDir == null || !Files.isDirectory(globalModsDir)) return Set.of();
        Path normalizedGlobal = globalModsDir.toAbsolutePath().normalize();
        if (normalizedGlobal.equals(packModsDir.toAbsolutePath().normalize())) return Set.of();
        Set<String> hashes = new HashSet<>();
        try (var stream = Files.list(normalizedGlobal)) {
            for (Path candidate : stream.filter(Files::isRegularFile).toList()) {
                if (candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    hashes.add(hash(candidate));
                }
            }
        }
        return Set.copyOf(hashes);
    }
}

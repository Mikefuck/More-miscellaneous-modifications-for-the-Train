package com.habitrain.lottery.prelaunch.late;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
        if (content == null || content.list == null) throw new IOException("Missing server file list");
        Set<Jsons.ModpackContentFields.ModpackContentItem> downloads = new HashSet<>();
        Set<Path> expected = new HashSet<>();
        for (var item : content.list) {
            Path file = resolve(directory, item.file);
            if (!expected.add(file)) throw new IOException("Duplicate modpack file path");
            if (item.sha1 == null || !item.sha1.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("Invalid server file hash");
            }
            if (!Files.isRegularFile(file) || (!item.editable && !item.sha1.equalsIgnoreCase(hash(file)))) {
                downloads.add(item);
            }
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

    static Path resolve(Path directory, String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Missing modpack file path");
        // AutoModpack manifests use game-relative paths, commonly with a leading slash.
        String relative = name.replace('\\', '/').replaceFirst("^/+", "");
        Path base = directory.toAbsolutePath().normalize();
        Path path = base.resolve(relative).normalize();
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
                if ((jarsOnly && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        || (!jarsOnly && name.equals(manifest))) continue;
                files.put(prefix + name, hash(path));
            }
        }
    }

    private static String hash(Path file) throws IOException {
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
}

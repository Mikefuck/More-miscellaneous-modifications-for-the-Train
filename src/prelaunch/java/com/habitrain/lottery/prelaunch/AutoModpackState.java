package com.habitrain.lottery.prelaunch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File-level access to AutoModpack's client config, usable before AutoModpack initialised itself
 * (paths are relative to the working directory, exactly like AutoModpack resolves them).
 *
 * <p>The early adapter turns {@code updateSelectedModpackOnLaunch} off for one launch so that
 * AutoModpack's preload only initialises; the original value is kept in a restore marker and put
 * back by the late adapter, or by {@link PrelaunchRestore} if the late adapter never ran.
 */
public final class AutoModpackState {

    public static final Path AUTOMODPACK_DIR = Path.of("automodpack");
    public static final Path CLIENT_CONFIG = AUTOMODPACK_DIR.resolve("automodpack-client.json");
    static final Path RESTORE_MARKER = AUTOMODPACK_DIR.resolve("habitrain-prelaunch-restore.json");
    public static final String UPDATE_ON_LAUNCH = "updateSelectedModpackOnLaunch";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private AutoModpackState() {
    }

    /** Whether AutoModpack's client config lists at least one installed modpack. */
    static boolean hasInstalledModpack() {
        JsonObject config = readObject(CLIENT_CONFIG);
        return config != null && config.get("installedModpacks") instanceof JsonObject installed && installed.size() > 0;
    }

    /** Disables AutoModpack's own launch update for this launch, remembering the original value. */
    static void suppressLaunchUpdate() throws IOException {
        suppressLaunchUpdate(CLIENT_CONFIG, RESTORE_MARKER);
    }

    static void suppressLaunchUpdate(Path configFile, Path markerFile) throws IOException {
        JsonObject config = readObject(configFile);
        if (config == null) {
            // AutoModpack creates the file with defaults (update on launch = true) during its preload.
            config = new JsonObject();
        }
        JsonElement value = config.get(UPDATE_ON_LAUNCH);
        boolean original = value == null || !value.isJsonPrimitive() || value.getAsBoolean();
        JsonObject previous = readObject(markerFile);
        if (Files.exists(markerFile)) {
            if (previous == null || !previous.has(UPDATE_ON_LAUNCH))
                throw new IOException("Unreadable AutoModpack restore marker; refusing to replace it");
            original = previous.get(UPDATE_ON_LAUNCH).getAsBoolean();
        }
        JsonObject marker = new JsonObject();
        marker.addProperty(UPDATE_ON_LAUNCH, original);
        writeObject(markerFile, marker);
        if (config.size() > 0) {
            config.addProperty(UPDATE_ON_LAUNCH, false);
            writeObject(configFile, config);
        }
    }

    /**
     * Puts the remembered flag back into the client config file.
     *
     * @return the restored value, or {@code null} when there was nothing to restore
     */
    public static Boolean restoreFlagFile() {
        return restoreFlagFile(CLIENT_CONFIG, RESTORE_MARKER);
    }

    static Boolean restoreFlagFile(Path configFile, Path markerFile) {
        JsonObject marker = readObject(markerFile);
        if (marker == null) {
            return null;
        }
        boolean original = !marker.has(UPDATE_ON_LAUNCH) || marker.get(UPDATE_ON_LAUNCH).getAsBoolean();
        try {
            JsonObject config = readObject(configFile);
            if (config == null) {
                PrelaunchLog.warn("AutoModpack client config unavailable; keeping restore marker for retry");
                return original;
            }
            config.addProperty(UPDATE_ON_LAUNCH, original);
            writeObject(configFile, config);
            Files.deleteIfExists(markerFile);
        } catch (IOException e) {
            PrelaunchLog.error("Failed to restore AutoModpack client config", e);
        }
        return original;
    }

    public static JsonObject readObject(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return element instanceof JsonObject object ? object : null;
        } catch (Exception e) {
            PrelaunchLog.warn("Ignoring unreadable %s: %s", file, e.toString());
            return null;
        }
    }

    public static void writeObject(Path file, JsonObject object) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(object), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

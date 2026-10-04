package com.habitrain.lottery.prelaunch;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

/** {@code config/habitrain_prelaunch.json}; written with defaults on first run. */
public final class PrelaunchConfig {

    static final int DEFAULT_AUTO_CONTINUE_SECONDS = 20;

    public final boolean enabled;
    /** Seconds before the dialog continues with AutoModpack's default; 0 waits forever. */
    public final int autoContinueSeconds;
    private final Path file;

    private PrelaunchConfig(Path file, boolean enabled, int autoContinueSeconds) {
        this.file = file;
        this.enabled = enabled;
        this.autoContinueSeconds = autoContinueSeconds;
    }

    public static PrelaunchConfig load(Path configDir) {
        Path file = configDir.resolve("habitrain_prelaunch.json");
        JsonObject json = AutoModpackState.readObject(file);
        boolean enabled = true;
        int seconds = DEFAULT_AUTO_CONTINUE_SECONDS;
        if (json != null) {
            if (json.has("enabled") && json.get("enabled").isJsonPrimitive()) {
                enabled = json.get("enabled").getAsBoolean();
            }
            if (json.has("autoContinueSeconds") && json.get("autoContinueSeconds").isJsonPrimitive()) {
                seconds = Math.max(0, json.get("autoContinueSeconds").getAsInt());
            }
        }
        PrelaunchConfig config = new PrelaunchConfig(file, enabled, seconds);
        if (json == null) {
            config.save(enabled);
        }
        return config;
    }

    public void save(boolean newEnabled) {
        JsonObject json = new JsonObject();
        json.addProperty("_comment", "启动前服务器更新选择窗口。enabled=false 关闭窗口；autoContinueSeconds=0 表示一直等待选择。");
        json.addProperty("enabled", newEnabled);
        json.addProperty("autoContinueSeconds", autoContinueSeconds);
        try {
            AutoModpackState.writeObject(file, json);
        } catch (IOException e) {
            PrelaunchLog.error("Failed to save " + file, e);
        }
    }
}

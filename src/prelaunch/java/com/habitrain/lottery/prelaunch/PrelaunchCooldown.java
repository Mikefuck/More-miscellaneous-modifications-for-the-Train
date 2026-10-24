package com.habitrain.lottery.prelaunch;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** A successful installation suppresses the picker across JVM restarts for five minutes. */
public final class PrelaunchCooldown {
    public static final long DURATION_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final String COMPLETED_AT = "lastSuccessfulUpdateMillis";

    private PrelaunchCooldown() { }

    public static boolean isActive(Path configDir, long now) {
        JsonObject state = AutoModpackState.readObject(file(configDir));
        if (state == null || !state.has(COMPLETED_AT)) return false;
        try {
            long completed = state.get(COMPLETED_AT).getAsLong();
            // A future/corrupt timestamp must not disable updates indefinitely.
            return completed > 0 && now >= completed && now - completed < DURATION_MILLIS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void recordSuccess(Path configDir, long now) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty(COMPLETED_AT, now);
        AutoModpackState.writeObject(file(configDir), state);
    }

    private static Path file(Path configDir) {
        return configDir.resolve("habitrain_prelaunch_state.json");
    }
}

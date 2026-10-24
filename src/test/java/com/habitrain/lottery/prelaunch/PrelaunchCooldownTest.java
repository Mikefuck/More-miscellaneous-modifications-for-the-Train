package com.habitrain.lottery.prelaunch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrelaunchCooldownTest {
    @TempDir Path config;

    @Test void successSurvivesReloadAndExpiresAtFiveMinutesWithoutExtendingOnLaunch() throws Exception {
        long completed = 1_800_000_000_000L;
        assertFalse(PrelaunchCooldown.isActive(config, completed));
        PrelaunchCooldown.recordSuccess(config, completed);
        assertTrue(PrelaunchCooldown.isActive(config, completed));
        assertTrue(PrelaunchCooldown.isActive(config, completed + 299_999));
        assertFalse(PrelaunchCooldown.isActive(config, completed + 300_000));
        assertFalse(PrelaunchCooldown.isActive(config, completed + 600_000));
    }

    @Test void corruptAndFutureTimestampsCannotDisableThePickerIndefinitely() throws Exception {
        Path state = config.resolve("habitrain_prelaunch_state.json");
        for (String invalid : new String[] {"{broken", "{}", "{\"lastSuccessfulUpdateMillis\":null}",
                "{\"lastSuccessfulUpdateMillis\":\"bad\"}", "{\"lastSuccessfulUpdateMillis\":-1}"}) {
            Files.writeString(state, invalid);
            assertFalse(PrelaunchCooldown.isActive(config, 1_000), invalid);
        }
        PrelaunchCooldown.recordSuccess(config, 2_000);
        assertFalse(PrelaunchCooldown.isActive(config, 1_000));
    }

    @Test void completionStateDoesNotOverwritePickerPreferences() throws Exception {
        Path preferences = config.resolve("habitrain_prelaunch.json");
        String original = "{\"enabled\":false,\"autoContinueSeconds\":0}";
        Files.writeString(preferences, original);
        PrelaunchCooldown.recordSuccess(config, 1_000);
        assertEquals(original, Files.readString(preferences));
    }
}

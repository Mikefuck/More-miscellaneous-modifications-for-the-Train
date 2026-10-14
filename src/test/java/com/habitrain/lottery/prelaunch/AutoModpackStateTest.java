package com.habitrain.lottery.prelaunch;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AutoModpackStateTest {
    @TempDir Path root;

    @Test void unavailableConfigKeepsOriginalFalseAcrossAnotherLaunchThenRestores() throws Exception {
        Path config = root.resolve("client.json"), marker = root.resolve("restore.json");
        JsonObject original = new JsonObject();
        original.addProperty(AutoModpackState.UPDATE_ON_LAUNCH, false);
        AutoModpackState.writeObject(marker, original);
        assertEquals(Boolean.FALSE, AutoModpackState.restoreFlagFile(config, marker));
        assertTrue(Files.exists(marker));
        Files.writeString(config, "{damaged");
        assertEquals(Boolean.FALSE, AutoModpackState.restoreFlagFile(config, marker));
        AutoModpackState.suppressLaunchUpdate(config, marker);
        assertFalse(AutoModpackState.readObject(marker).get(AutoModpackState.UPDATE_ON_LAUNCH).getAsBoolean());
        Files.writeString(config, "{\"updateSelectedModpackOnLaunch\":true,\"keep\":7}");
        assertEquals(Boolean.FALSE, AutoModpackState.restoreFlagFile(config, marker));
        assertFalse(Files.exists(marker));
        var restored = AutoModpackState.readObject(config);
        assertFalse(restored.get(AutoModpackState.UPDATE_ON_LAUNCH).getAsBoolean());
        assertEquals(7, restored.get("keep").getAsInt());
    }
}

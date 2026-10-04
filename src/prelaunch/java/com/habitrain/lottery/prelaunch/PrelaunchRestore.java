package com.habitrain.lottery.prelaunch;

import java.lang.reflect.Field;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs after every language adapter, i.e. after AutoModpack's preload finished, and undoes the
 * one-launch override of {@code updateSelectedModpackOnLaunch} made by the picker.
 */
public final class PrelaunchRestore implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        try {
            Boolean original = AutoModpackState.restoreFlagFile();
            if (original != null) {
                restoreInMemory(original);
            }
        } catch (Throwable t) {
            PrelaunchLog.error("Failed to restore AutoModpack launch-update flag", t);
        }
    }

    /** AutoModpack keeps the loaded config in memory and may save it again later in this session. */
    private static void restoreInMemory(boolean original) {
        try {
            Class<?> globals = Class.forName("pl.skidam.automodpack_core.GlobalVariables", false,
                    PrelaunchRestore.class.getClassLoader());
            Object clientConfig = globals.getField("clientConfig").get(null);
            if (clientConfig != null) {
                Field flag = clientConfig.getClass().getField(AutoModpackState.UPDATE_ON_LAUNCH);
                flag.setBoolean(clientConfig, original);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            PrelaunchLog.warn("Could not restore AutoModpack in-memory config: %s", e.toString());
        }
    }
}

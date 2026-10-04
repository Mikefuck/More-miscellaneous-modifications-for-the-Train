package com.habitrain.lottery.prelaunch;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class PrelaunchLog {

    private static final LogCategory CATEGORY = LogCategory.createCustom("HabiTrain", "Prelaunch");

    private PrelaunchLog() {
    }

    public static void info(String message, Object... args) {
        Log.info(CATEGORY, message, args);
    }

    public static void warn(String message, Object... args) {
        Log.warn(CATEGORY, message, args);
    }

    public static void error(String message, Throwable t) {
        Log.error(CATEGORY, message, t);
    }
}

package com.habitrain.lottery.prelaunch.late;

import com.habitrain.lottery.prelaunch.AutoModpackState;
import com.habitrain.lottery.prelaunch.PrelaunchLanguageAdapter;
import com.habitrain.lottery.prelaunch.PrelaunchLog;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;

/**
 * Late hook, declared by mod {@code habitrain_prelaunch_late} which sorts after {@code automodpack},
 * so it is constructed right after AutoModpack's preload finished initialising its globals.
 */
public final class LateLanguageAdapter implements LanguageAdapter {

    public LateLanguageAdapter() {
        // Only set by the early adapter after it checked client side, config and AutoModpack version.
        if (!Boolean.getBoolean(PrelaunchLanguageAdapter.ACTIVE_PROPERTY)) {
            return;
        }
        try {
            PrelaunchUpdater.run();
        } catch (Throwable t) {
            PrelaunchLog.error("Prelaunch picker failed, continuing without it", t);
            AutoModpackState.restoreFlagFile();
        }
    }

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) {
        throw new UnsupportedOperationException("habitrain_prelaunch_late adapter has no entrypoints");
    }
}

package com.habitrain.lottery.client;

import com.habitrain.lottery.client.gui.LotteryConfigRootScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LotteryConfigRootScreen::new;
    }
}

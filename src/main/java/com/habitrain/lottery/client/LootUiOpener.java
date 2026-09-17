package com.habitrain.lottery.client;

import com.habitrain.lottery.HabiLotteryMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.agmas.noellesroles.client.screen.LootInfoScreen;

/**
 * Opens the SRE skin gacha hub ({@link LootInfoScreen}) from this mod's terminal block / command.
 */
@Environment(EnvType.CLIENT)
public final class LootUiOpener {
    private LootUiOpener() {
    }

    public static void openFromServerPacket(int coinNumber, int lotteryChance) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            try {
                // ConfigSnapshot already rebuilds the client pool cache; do not send
                // LootPoolsInfoCheckC2S (that path is redundant and can open extra UI).
                if (client.screen instanceof LootInfoScreen) {
                    client.setScreen(new LootInfoScreen(0, coinNumber, lotteryChance, null));
                } else {
                    client.setScreen(new LootInfoScreen(0, coinNumber, lotteryChance, client.screen));
                }
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.error("Failed opening LootInfoScreen", t);
            }
        });
    }
}

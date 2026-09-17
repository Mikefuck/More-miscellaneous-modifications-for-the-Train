package com.habitrain.lottery.backpack;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Redeems persistent virtual cards for additional daily faction-card uses. */
public final class LimitBreakCardService {
    private LimitBreakCardService() {}

    /** One card in the virtual backpack grants exactly one additional use today. */
    public static boolean useFromBackpack(ServerPlayer player) {
        if (player == null) return false;
        if (LocalBackpackStore.limitBreakCards(player.getUUID()) < 1) return false;
        if (!DailyFactionCardService.grantBonusUse(player)) return false;
        if (!LocalBackpackStore.addLimitBreakCards(player.getUUID(), -1)) {
            if (!DailyFactionCardService.revokeBonusUse(player)) {
                HabiLotteryMod.LOGGER.error("Limit break use rollback failed for {}", player.getUUID());
            }
            return false;
        }
        player.sendSystemMessage(Component.literal("§a[突破上限卡] 今日角色卡可用次数 +1，当前剩余 "
                + DailyFactionCardService.remaining(player) + " 次"));
        return true;
    }
}

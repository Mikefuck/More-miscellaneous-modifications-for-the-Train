package com.habitrain.lottery.bridge;

import com.habitrain.lottery.skin.SkinLotteryRewards;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.utils.Pair;
import org.agmas.noellesroles.utils.lottery.LotteryManager;

public final class SkinLotteryBridge {
    private SkinLotteryBridge() {}
    public static Pair<Integer, Integer> roll(LotteryManager.LotteryPool pool, ServerPlayer player) {
        var bands = pool.getQualityListGroupConfigs();
        int quality = SkinLotteryRewards.chooseBand(bands.stream().map(p -> p.first).toList(), player.getRandom().nextDouble());
        if (quality < 0 || bands.get(quality).second.isEmpty()) return new Pair<>(-1, -1);
        var entries = bands.get(quality).second;
        int index = player.getRandom().nextInt(entries.size());
        SkinLotteryRewards.award(player, entries.get(index), quality);
        return new Pair<>(quality, index);
    }
}

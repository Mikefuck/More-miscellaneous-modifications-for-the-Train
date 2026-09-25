package com.habitrain.lottery.mixin;

import io.wifi.starrailexpress.progression.ProgressionDataManager;
import io.wifi.starrailexpress.progression.ProgressionState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让进度通行证页面的「金币奖励」统计不再虚增。
 *
 * <p>自 {@link AccountCoinRewardMixin} 起，游戏内已经不发账户金币了；但
 * {@code ProgressionDataManager} 仍会在两条路径上把「已领取金币奖励」计数加上去，界面就会报出
 * 玩家从未拿到的数字。本 mixin 把这两处 {@code ProgressionState.claimedCoinRewards} 的写入
 * 就地丢掉（字段值保持原样）。</p>
 *
 * <h2>注入点（按运行期 jar 的字节码核对）</h2>
 * <p>{@code io.wifi.starrailexpress.progression.ProgressionDataManager} 里对
 * {@code ProgressionState.claimedCoinRewards} 的写入<b>只有这两处</b>：</p>
 * <ol>
 *   <li>{@code onRoundSettled(ServerPlayer, SRERole, boolean)} 的获胜分支（偏移 42，原 {@code += 20}）；</li>
 *   <li>{@code grantExperience(ServerPlayer, int)} 的升级循环（偏移 125，原 {@code += 20 + 等级×2}）。</li>
 * </ol>
 * <p>该字段在整个上游里没有任何逻辑读取方（唯一读取方是
 * {@code ProgressionPassScreen.renderSummaryCards} 的「金币奖励」摘要卡），跳过写入不改变任何行为。
 * {@code claimedLootRewards}（抽数统计）不在本 mixin 范围内。</p>
 *
 * <h2>为什么两条都用 {@code require = 0}</h2>
 * <p>这只是显示口径修正：目标一旦失配（上游改了写入方式），静默跳过即可，绝不能因为一个统计注入
 * 失败而把 {@link AccountCoinRewardMixin} 或其它 mixin 一起拖下水。本类只含这两条软注入，
 * 不存在「一个失败连带另一个失效」的耦合。</p>
 */
@Mixin(value = ProgressionDataManager.class, remap = false)
public class ProgressionCoinRewardStatMixin {

    /** 对局结算胜利分支的统计（1.1.30 起由闸门接手发币，这里只修显示）。 */
    @Redirect(
            method = "onRoundSettled(Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lio/wifi/starrailexpress/api/SRERole;Z)V",
            require = 0,
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.PUTFIELD,
                    target = "Lio/wifi/starrailexpress/progression/ProgressionState;claimedCoinRewards:I"))
    private static void habi$skipRoundWinCoinStat(ProgressionState state, int value) {
        // 不写回：这一笔金币从未发放。
    }

    /** 升级奖励分支的统计。 */
    @Redirect(
            method = "grantExperience(Lnet/minecraft/server/level/ServerPlayer;I)V",
            require = 0,
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.PUTFIELD,
                    target = "Lio/wifi/starrailexpress/progression/ProgressionState;claimedCoinRewards:I"))
    private static void habi$skipLevelUpCoinStat(ProgressionState state, int value) {
        // 不写回：这一笔金币从未发放。
    }
}

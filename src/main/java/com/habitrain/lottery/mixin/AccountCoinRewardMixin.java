package com.habitrain.lottery.mixin;

import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 账户金币（{@code EconomyState.coinNum}）<b>不再有任何游戏内来源</b>：拦住
 * {@link PlayerEconomyManager#addCoinNum(Player, int)} 的每一笔正向发放。
 *
 * <h2>为什么在闸门上拦，而不是逐个调用点</h2>
 * <p>本模组（1.1.30）曾在上游胜利结算的调用点上做定点移除，但「游戏内不再能赚金币」是<b>一条策略</b>，
 * 不是若干处巧合：逐个调用点既拦不全（上游还有休眠组件、其它下游模组的新职业），也会随上游重构而漏。
 * 经核对运行期 {@code libs/star_rail_express-4.3.0.jar}，游戏内所有正向发放都汇聚到
 * {@code PlayerEconomyManager.addCoinNum} 这一个静态方法，包括：
 * {@link io.wifi.starrailexpress.util.ItemSkinManager#addCoinNum(Player, Integer)} 这层包装
 * （所以抽奖重复皮肤折算 {@code LotteryManager.rollOnce} 也在此闸门内）。因此在这里按
 * {@code delta > 0} 统一取消，等价于「游戏内金币只减不增」，且对后续新增角色/奖励自动生效。</p>
 *
 * <h2>被本 mixin 掐掉的来源（Mike 2026-09-24 指定）</h2>
 * <ul>
 *   <li>巫师：诅咒目标在诅咒期内死亡 +40（{@code WarlockPlayerComponent}）</li>
 *   <li>亡灵之主：每次成功注入感染 +25（{@code UndeadLordPlayerComponent}，数值本身可配置）</li>
 *   <li>操纵师：盯视完成后的首次成功标记 +15（{@code ManipulatorPlayerComponent}）</li>
 *   <li>操纵师：被操纵目标死亡 +75（{@code NRDeathEvents}）</li>
 *   <li>抽奖重复皮肤折算（{@code LotteryManager.rollOnce} 的重复皮肤 / 金币卡两条分支；
 *       该链路在上游 1.1.27 基线上已无消费者，此处属于把死路径也一并封口）</li>
 *   <li>升级奖励 {@code 20 + 等级×2}（{@code ProgressionDataManager.grantExperience}）</li>
 *   <li>对局结算胜利 +20（{@code ProgressionDataManager.onRoundSettled}，1.1.30 的定点注入已由本闸门取代）</li>
 *   <li>休眠的 {@code SREPlayerProgressionComponent} 任务/等级金币（其事件入口无调用方，同样被封口）</li>
 * </ul>
 * <p>升级时每 5 级 +1 的<b>账户抽数</b>（{@code addLootChance}）不在本 mixin 范围内，未被改动。</p>
 *
 * <h2>明确保留：扣费</h2>
 * <p>只取消 {@code delta > 0}。唯一的负向调用是维修模式职业解锁
 * （{@code RepairRoleShopPurchaseC2SPacket}，{@code -UNLOCK_PRICE} = -5000），它必须继续生效，
 * 否则购买职业会变成免费。代价是：既然游戏内不再产币，这笔扣费只能由「网站端写入的余额」支撑
 * （网站端 MySQL 的 {@code skins} 分区仍是账户金币的权威源，{@code reloadFromDatabase} 走
 * {@code fromJson} 整体替换 {@code Entry.state}，<b>不经过</b>本方法，因此网站端发放的余额照常生效）。</p>
 *
 * <h2>失败模式（必须知道）</h2>
 * <p>本 mixin 沿用 {@code defaultRequire = 1}：若上游把 {@code addCoinNum} 改名或改签名，本 mixin
 * 会整体失效（配置为 {@code "required": false}，由 {@link HabiLotteryMixinPlugin} 记一条 ERROR 后跳过，
 * 游戏照常启动）——此时<b>所有游戏内金币奖励会一起回来</b>。回归守卫
 * {@code AccountCoinRewardMixinsTargetTest} 会先一步失败：它读运行期 jar，既校验
 * {@code addCoinNum(Player,int)} 仍然存在，也校验上面那几个奖励调用点确实仍走这个方法（一旦上游改成
 * 直写字段，闸门就漏了）。</p>
 */
@Mixin(value = PlayerEconomyManager.class, remap = false)
public class AccountCoinRewardMixin {

    @Inject(
            method = "addCoinNum(Lnet/minecraft/world/entity/player/Player;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void habi$blockAccountCoinRewards(Player player, int delta, CallbackInfo ci) {
        if (delta > 0) {
            // 正向发放一律丢弃：账户余额、脏标记与玩家组件镜像都不再被写入。
            // 负向（维修职业解锁 -5000）继续放行。
            ci.cancel();
        }
    }
}

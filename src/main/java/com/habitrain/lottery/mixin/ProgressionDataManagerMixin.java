package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.card.CardUseService;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 上游「分配失败退款」的归一化入口（审核 B-16：已核对上游源码，行为保持不变，文档改成事实）。
 *
 * <h2>注入点</h2>
 * <p>HEAD 处 cancel 掉
 * {@code ProgressionDataManager.addFactionCard(ServerPlayer, FactionCardType, int)}
 * （上游 4.3.0，该方法已委托 {@code BackpackManager.addCard}），并转成一次
 * {@link CardUseService#refundForcedCard}。</p>
 *
 * <h2>已核对的上游调用点（源码 {@code 哈比列车dlc/20260805/StarRailExpress-master}，共 5 处）</h2>
 * <p>审计声明「全部调用点都是分配失败后的退款」<b>经逐一阅读上下文证实为真</b>，
 * 5 处全部是「玩家已消耗阵营卡 → 强制分配失败 / 被限额裁剪 → 把卡还回去」：</p>
 * <ol>
 *   <li>{@code io/wifi/starrailexpress/game/modes/SREMurderGameMode.java:632} —
 *       强制分配时 {@code roleSelector.selectRandomKeyBasedOnWeightsAndRemoved()} 返回 null
 *       （该阵营已无可用职业），随后广播 {@code message.sre.pass.faction.assign_failed}。</li>
 *   <li>{@code io/wifi/starrailexpress/game/modes/funny/SRECustomRoleGameMode.java:192} —
 *       同上（自定义职业局），同样是 {@code assign_failed} 退款。</li>
 *   <li>{@code io/wifi/starrailexpress/game/modes/funny/SREHideAndSeekGameMode.java:336} —
 *       同上（躲猫猫局），同样是 {@code assign_failed} 退款。</li>
 *   <li>{@code io/wifi/starrailexpress/game/modes/funny/rotation/LightningDraftState.java:245}
 *       （{@code initializeCardTracking}）— 某阵营的强制卡数量超过
 *       {@code cardMaxPerType} 上限，超出部分被移除并要求退卡 + {@code card_limit} 提示。</li>
 *   <li>{@code io/wifi/starrailexpress/game/modes/funny/rotation/LightningDraftState.java:385}
 *       （轮次抽选）— 抽选池里没有该阵营的职业，源码注释即「无法提供匹配职业，移除强制要求，
 *       <b>退还卡片</b>」。</li>
 * </ol>
 *
 * <h2>为什么保留「无条件 cancel」而不是按调用点区分</h2>
 * <p>5 个调用点编译出的字节码形状完全相同（同一个 {@code INVOKESTATIC}，参数都是
 * {@code (player, cardType, 1)}），<b>无法用注入器手段区分</b>「退款」与「发卡」；
 * 而运行期状态（{@code ActiveCardForces} 里有没有待退记录）只能说明「这张卡是不是抽奖侧扣的」，
 * 不能说明「这次调用该不该由抽奖侧接管」——若据此放行上游写入，卡片会落到上游背包/CCA 一侧，
 * 而抽奖的世界权威库（{@code LocalBackpackStore}）并不知情，反而会引入两边账目分叉。
 * 因此这里保持 cancel，并把 {@link CardUseService#refundForcedCard} 的返回值当成探针：
 * 返回 {@code false} 表示「这次调用不是抽奖侧的待退退款」，会被 {@code WARN} 记录下来
 * （此前是完全静默吞掉）。</p>
 *
 * <h2>副作用 / 上游若新增正向调用点会怎样</h2>
 * <p><b>任何</b>第三方模组或未来版本的上游若用这个入口给玩家<b>发</b>阵营卡，
 * 发卡都会被静默取消（只在没有待退记录时留下一条 WARN）：玩家不会拿到卡，
 * 而 {@code refundForcedCard} 也不会退款（没有待退记录）。发新卡请改用
 * {@code BackpackManager.addCard(player, type, count)} +
 * {@link com.habitrain.lottery.backpack.LocalBackpackStore#saveFromEnumMap}。</p>
 *
 * <p>上游若要动这块，必须同步修改本 mixin：新增任何 {@code addFactionCard} 正向调用点，
 * 或把退款改走别的方法（如 {@code setFactionCard}），都会让「退款归一化」失效或误吞发卡。</p>
 *
 * <h2>不在本 mixin 范围内的同名入口（未被拦截，属正常）</h2>
 * <ul>
 *   <li>{@code ProgressionDataManager.addFactionCard(Player, ...)}（:115）只是转发到上面那个
 *       {@code ServerPlayer} 重载，上游没有调用点。</li>
 *   <li>{@code ProgressionDataManager} <b>没有</b> {@code setFactionCard} / {@code removeFactionCard}。</li>
 *   <li>{@code SREPlayerProgressionComponent.setFactionCard/addFactionCard}（旧 CCA 计数表）
 *       是另一个类的方法，本 mixin 不拦：其正向调用点（{@code :514} 任务奖励卡、
 *       {@code :570/572/574} 等级奖励卡）写的是遗留 CCA 字段，不是背包。</li>
 * </ul>
 */
@Mixin(value = ProgressionDataManager.class, remap = false)
public class ProgressionDataManagerMixin {
    @Inject(
            method = "addFactionCard(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/progression/ProgressionState$FactionCardType;I)V",
            at = @At("HEAD"), cancellable = true)
    private static void habi$refundOnce(ServerPlayer player, FactionCardType type, int count, CallbackInfo ci) {
        if (player == null || type == null || type == FactionCardType.NONE || count <= 0) return;
        // 只处理「已扣费但没兑现」的退款：refundForcedCard 是幂等的，没有待退记录时直接返回 false。
        ci.cancel();
        if (!CardUseService.refundForcedCard(player.getUUID())) {
            // 审核 B-16：上游 5 处调用点都应当命中待退记录；返回 false 说明这次 addFactionCard
            // 并不是抽奖侧的退款（第三方发卡 / 上游新增的正向调用点），而它已经被上面的 cancel
            // 吞掉了——必须留痕，否则这种账目丢失只能靠玩家反馈发现。
            HabiLotteryMod.LOGGER.warn(
                    "Cancelled ProgressionDataManager.addFactionCard but no pending forced card existed "
                            + "(player={}, type={}, count={}): the call was swallowed; see ProgressionDataManagerMixin",
                    player.getUUID(), type, count);
        }
    }
}

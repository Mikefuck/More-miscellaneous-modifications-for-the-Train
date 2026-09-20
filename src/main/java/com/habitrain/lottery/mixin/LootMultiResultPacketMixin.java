package com.habitrain.lottery.mixin;

import com.habitrain.lottery.grant.LootBatchPolicy;
import org.agmas.noellesroles.packet.Loot.LootMultiResultS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raises the batch-result decode cap from SRE's hard-coded 10 to
 * {@link LootBatchPolicy#MAX_ROLLS} so a 50-roll batch can actually be read.
 *
 * <p><b>审核 M-03（一致性）</b>：本注入此前是 {@code require = 1}（缺失即启动崩溃），
 * 而本模组的 mixin 配置已改为 {@code "required": false}、角色旋转相关的注入也已降到
 * {@code require = 0}（失败即跳过 + ERROR 日志）。这里统一到同一策略：
 * SRE 改了那个常量或方法名时，本 mixin 会被跳过并打出明确日志，
 * 表现为「批量上限退回 10」而不是「游戏无法启动」。
 */
@Mixin(value = LootMultiResultS2CPacket.class, remap = false)
public abstract class LootMultiResultPacketMixin {
    @ModifyConstant(method = "read", constant = @Constant(intValue = 10), require = 0, allow = 1)
    private static int habi$batchResultLimit(int original) {
        return LootBatchPolicy.MAX_ROLLS;
    }
}

package com.habitrain.lottery.mixin;

import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把玩家当前佩戴的称号（NameTag）追加到原版 Tab 玩家列表显示。
 *
 * <p>原版 Tab 列表服务端走 {@link ServerPlayer#getTabListDisplayName()}（默认返回 {@code null}，
 * 客户端回退到 profile 名）。这里让佩戴了称号的玩家返回 {@code [称号]玩家名}，
 * 配合 {@link com.habitrain.lottery.title.TitleService#forceResync} 广播的
 * {@code UPDATE_DISPLAY_NAME} 包自动推送给所有客户端。
 *
 * <p>base 用 {@code getName()}（profile 名）而非 {@code getDisplayName()}，避免与
 * SRE {@code PlayerPrefixMixin} 以及本模组 {@code ClientPlayerTitlePrefixMixin}
 * 对 {@code getDisplayName()} 的前缀注入产生双前缀。
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerTabTitleMixin {

    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void habi$tabTitle(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            NameTagInventoryComponent tags = NameTagInventoryComponent.KEY.get(self);
            MutableComponent prefix = tags.generate();
            if (prefix == null) {
                return; // 无称号且非旁观者 → 保持原版 null 行为
            }
            Component base = self.getName().copy();
            cir.setReturnValue(prefix.copy().append(base));
        } catch (Throwable ignored) {
            // CCA 缺失 / generate 失败 → 保持原版 Tab 名
        }
    }

    /**
     * SRE {@code PlayerPrefixMixin} maps {@code generate()==null} to {@code ""} and
     * concatenates that with the profile name. Empty titles must use the profile
     * name only.
     */
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void habi$emptyTitleUsesProfileName(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        try {
            NameTagInventoryComponent tags = NameTagInventoryComponent.KEY.get(self);
            String current = tags.CurrentNameTag;
            if (current != null && !current.isBlank()) {
                return;
            }
            MutableComponent prefix = tags.generate();
            if (prefix == null) {
                cir.setReturnValue(self.getName().copy());
            }
        } catch (Throwable ignored) {
        }
    }
}

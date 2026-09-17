package com.habitrain.lottery.mixin.client;

import io.wifi.starrailexpress.client.gui.screen.MapIntroduceScreen;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Show a clear empty-state message when map intro payload has no maps.
 */
@Mixin(value = MapIntroduceScreen.class, remap = false)
public class MapIntroduceEmptyMixin {

    @Inject(method = "updateFromPacket", at = @At("RETURN"))
    private void habi$emptyHint(MapIntroSyncPayload payload, CallbackInfo ci) {
        if (payload == null) {
            return;
        }
        boolean emptyMaps = payload.maps() == null || payload.maps().isEmpty();
        boolean emptyVotes = payload.voteMaps() == null || payload.voteMaps().isEmpty();
        if (emptyMaps && emptyVotes) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§c暂无地图介绍数据。请检查世界目录 train_maps/ 与地图配置。"),
                        false);
            }
        }
    }
}

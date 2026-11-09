package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Persist faction cards to world JSON; load on join via public API overlay.
 * MySQL reload/flush is forced off so DB cannot overwrite local JSON.
 */
@Mixin(value = BackpackManager.class, remap = false)
public class BackpackManagerMixin {

    @Inject(method = "isDatabaseEnabled()Z", at = @At("HEAD"), cancellable = true)
    private static void habi$noMysql(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(
            method = "reloadFromDatabase(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/backpack/BackpackManager$Entry;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void habi$noReloadFromDatabase(ServerPlayer player, @Coerce Object entry, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(
            method = "flushAsync(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/backpack/BackpackManager$Entry;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void habi$noFlushAsync(ServerPlayer player, @Coerce Object entry, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "activateCard", at = @At("HEAD"), cancellable = true)
    private static void habi$enforceDailyUseLimit(
            ServerPlayer player,
            FactionCardType type,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(CardUseService.activateFactionCard(player, type));
    }

    @Inject(method = "addCard", at = @At("RETURN"))
    private static void habi$persistAdd(ServerPlayer player, FactionCardType type, int count, CallbackInfo ci) {
        persist(player);
    }

    @Inject(method = "flushBlocking", at = @At("HEAD"), cancellable = true)
    private static void habi$flushLocal(UUID playerUuid, CallbackInfoReturnable<Boolean> cir) {
        if (!WorldLotteryPaths.ready() || playerUuid == null) {
            return;
        }
        try {
            var server = HabiLotteryMod.getServer();
            if (server != null) {
                ServerPlayer online = server.getPlayerList().getPlayer(playerUuid);
                if (online != null) {
                    boolean saved = LocalBackpackStore.saveFromEnumMap(playerUuid, BackpackManager.getCards(online));
                    if (!saved) {
                        HabiLotteryMod.LOGGER.error("Failed persisting backpack for {}", playerUuid);
                    }
                    cir.setReturnValue(saved);
                    return;
                }
            }
            cir.setReturnValue(true);
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed persisting backpack for {}", playerUuid, e);
            cir.setReturnValue(false);
        }
    }

    private static void persist(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready() || LocalBackpackStore.isApplyingCommitted()) {
            return;
        }
        try {
            if (!LocalBackpackStore.saveFromEnumMap(player.getUUID(), BackpackManager.getCards(player))) {
                HabiLotteryMod.LOGGER.error("Failed persisting backpack for {}", player.getUUID());
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed persisting backpack for {}", player.getUUID(), e);
        }
    }
}

package com.habitrain.lottery.mixin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.bridge.EconomyMirror;
import com.habitrain.lottery.bridge.EconomyTakeoverGates;
import com.habitrain.lottery.bridge.SkinStateCoordinator;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.data.PlayerEconomyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * World-authoritative economy for chance/coins/type unlocks/equip.
 * Dual-writes CCA skins so SkinManagementScreen (which reads SREPlayerSkinsComponent) stays in sync.
 * Logical-client calls fall through so upstream can use ClientPlayerDataCache; only ServerPlayer mutates the store.
 */
@Mixin(value = PlayerEconomyManager.class, remap = false)
public class PlayerEconomyManagerMixin {
    private static final AtomicBoolean WARNED_WRITE_WITHOUT_TAKEOVER = new AtomicBoolean();

    /**
     * Integrated LocalPlayer shares a UUID with ServerPlayer; do not treat client-thread
     * calls as world-authoritative. Dedicated-client JVMs never set takeover, so this is already false there.
     */
    private static boolean shouldTakeOver(Player player) {
        if (EconomyMirror.isSuppressed()) {
            return false;
        }
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            return false;
        }
        if (player == null) {
            return false;
        }
        if (player.level() != null && player.level().isClientSide()) {
            return false;
        }
        if (PlayerLotteryStore.get().isLoadFailed(player.getUUID())) {
            return false;
        }
        return true;
    }

    /**
     * When JSON takeover is off, cancel logical-server writes so SRE memory is not mutated.
     * Client-side LocalPlayer must still fall through.
     */
    private static boolean shouldBlockWrite(Player player) {
        if (player == null || EconomyMirror.isSuppressed()) {
            return false;
        }
        boolean clientSide = player.level() != null && player.level().isClientSide();
        return EconomyTakeoverGates.blockServerWrite(
                PlayerLotteryStore.get().isTakeoverActive(),
                clientSide,
                player instanceof ServerPlayer);
    }

    private static boolean cancelWriteIfNoTakeover(Player player, CallbackInfo ci) {
        if (!shouldBlockWrite(player)) {
            return false;
        }
        if (WARNED_WRITE_WITHOUT_TAKEOVER.compareAndSet(false, true)) {
            HabiLotteryMod.LOGGER.error(
                    "JSON economy takeover is not active; cancelling server-side PlayerEconomyManager write");
        }
        ci.cancel();
        return true;
    }

    @Inject(method = "getLootChance", at = @At("HEAD"), cancellable = true)
    private static void habi$getLootChance(Player player, CallbackInfoReturnable<Integer> cir) {
        if (!shouldTakeOver(player)) {
            return;
        }
        cir.setReturnValue(PlayerLotteryStore.get().getLootChance(player.getUUID()));
    }

    @Inject(method = "addLootChance", at = @At("HEAD"), cancellable = true)
    private static void habi$addLootChance(Player player, int amount, CallbackInfo ci) {
        if (!shouldTakeOver(player)) {
            cancelWriteIfNoTakeover(player, ci);
            return;
        }
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        PlayerLotteryStore.get().addLootChance(player.getUUID(), amount);
        if (player instanceof ServerPlayer sp) {
            EconomyMirror.syncChanceAndCoins(sp, PlayerLotteryStore.get().getOrLoad(sp));
        }
        ci.cancel();
    }

    @Inject(method = "getCoinNum", at = @At("HEAD"), cancellable = true)
    private static void habi$getCoinNum(Player player, CallbackInfoReturnable<Integer> cir) {
        if (!shouldTakeOver(player)) {
            return;
        }
        cir.setReturnValue(PlayerLotteryStore.get().getCoinNum(player.getUUID()));
    }

    @Inject(method = "addCoinNum", at = @At("HEAD"), cancellable = true)
    private static void habi$addCoinNum(Player player, int amount, CallbackInfo ci) {
        if (!shouldTakeOver(player)) {
            cancelWriteIfNoTakeover(player, ci);
            return;
        }
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        PlayerLotteryStore.get().addCoinNum(player.getUUID(), amount);
        if (player instanceof ServerPlayer sp) {
            EconomyMirror.syncChanceAndCoins(sp, PlayerLotteryStore.get().getOrLoad(sp));
        }
        ci.cancel();
    }

    @Inject(method = "isSkinUnlockedForItemType", at = @At("HEAD"), cancellable = true)
    private static void habi$isUnlockedType(Player player, String type, String skin, CallbackInfoReturnable<Boolean> cir) {
        if (!shouldTakeOver(player)) {
            return;
        }
        cir.setReturnValue(PlayerLotteryStore.get().isSkinUnlocked(player.getUUID(), type, skin));
    }

    @Inject(method = "unlockSkinForItemType", at = @At("HEAD"), cancellable = true)
    private static void habi$unlockType(Player player, String type, String skin, CallbackInfo ci) {
        if (!shouldTakeOver(player)) {
            cancelWriteIfNoTakeover(player, ci);
            return;
        }
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        PlayerLotteryStore.get().unlockSkin(player.getUUID(), type, skin);
        if (player instanceof ServerPlayer sp) {
            EconomyMirror.syncUnlockedSkin(sp, type, skin);
        }
        ci.cancel();
    }

    @Inject(method = "setEquippedSkinForItemType", at = @At("HEAD"), cancellable = true)
    private static void habi$setEquipped(Player player, String type, String skin, CallbackInfo ci) {
        if (!shouldTakeOver(player)) {
            cancelWriteIfNoTakeover(player, ci);
            return;
        }
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        SkinStateCoordinator.commitEquipped((ServerPlayer) player, type, skin);
        ci.cancel();
    }

    @Inject(method = "getEquippedSkinForItemType", at = @At("HEAD"), cancellable = true)
    private static void habi$getEquippedType(Player player, String type, CallbackInfoReturnable<String> cir) {
        if (!shouldTakeOver(player)) {
            return;
        }
        String eq = PlayerLotteryStore.get().getEquipped(player.getUUID(), type);
        cir.setReturnValue(eq == null || eq.isBlank() ? "default" : eq);
    }

    @Inject(method = "lockSkinForItemType", at = @At("HEAD"), cancellable = true)
    private static void habi$lock(Player player, String type, String skin, CallbackInfo ci) {
        if (!shouldTakeOver(player)) {
            cancelWriteIfNoTakeover(player, ci);
            return;
        }
        if (!(player instanceof ServerPlayer)) {
            return;
        }
        PlayerLotteryStore.get().lockSkin(player.getUUID(), type, skin);
        if (player instanceof ServerPlayer sp) {
            EconomyMirror.runSuppressed(() ->
                    PlayerEconomyManager.lockSkinForItemType(sp, type, skin));
            EconomyMirror.lockCca(sp, type, skin);
            EconomyMirror.syncCca(sp);
        }
        PlayerLotteryStore.get().flush(player.getUUID());
        ci.cancel();
    }
}

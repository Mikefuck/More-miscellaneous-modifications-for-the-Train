package com.habitrain.lottery.mixin;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.grant.SelfSelectRoleHook;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import io.wifi.starrailexpress.progression.ProgressionDataManager;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Deduplicates upstream murder-mode faction-card refunds ({@code B-12}).
 *
 * <p>When more players activate the same faction card than slots exist in a round,
 * {@link SREMurderGameMode} refunds overflow holders via
 * {@link ProgressionDataManager#addFactionCard(ServerPlayer, FactionCardType, int)}
 * while leaving {@link ActiveCardForces} populated until {@code OnGameTrueStarted}.
 * Routing tracked reservations directly into {@link CardUseService#refundForcedCard}
 * atomically clears {@link ActiveCardForces} and refunds both the exact card and the
 * daily-use quota at most once.
 */
@Mixin(value = SREMurderGameMode.class, remap = false)
public abstract class MurderFactionCardRefundMixin {

    @Redirect(
            method = "assignRolesToPlayers",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/progression/ProgressionDataManager;addFactionCard(Lnet/minecraft/server/level/ServerPlayer;Lio/wifi/starrailexpress/progression/ProgressionState$FactionCardType;I)V"
            ),
            require = 0
    )
    private static void habi$dedupeMurderCardRefund(
            ServerPlayer player,
            FactionCardType cardType,
            int amount) {
        if (player != null && ActiveCardForces.get(player.getUUID()) != null) {
            CardUseService.refundForcedCard(player.getUUID());
            return;
        }
        ProgressionDataManager.addFactionCard(player, cardType, amount);
    }

    /**
     * Snapshots genuine pre-queued {@code RoleForceApi} holders BEFORE
     * {@link SREMurderGameMode#assignRolesToPlayers} inserts unpaid streak-pity
     * ({@code 保底}) players into {@code PlayerRoleWeightManager.ForcePlayerTeam} ({@code I-15}).
     */
    @Inject(method = "assignRolesToPlayers", at = @At("HEAD"), require = 0)
    private static void habi$captureGenuineRoleForcesBeforePity(
            ServerLevel serverWorld,
            List<ServerPlayer> players,
            CallbackInfoReturnable<Map<Player, SRERole>> cir) {
        SelfSelectRoleHook.capturePrePityForces(players);
    }

    @Inject(method = "assignRole", at = @At("RETURN"), require = 0)
    private static void habi$clearPrePityForceSnapshot(
            ServerLevel serverWorld,
            SREGameWorldComponent gameWorldComponent,
            List<ServerPlayer> players,
            CallbackInfo ci) {
        SelfSelectRoleHook.clearPrePityForces();
    }
}

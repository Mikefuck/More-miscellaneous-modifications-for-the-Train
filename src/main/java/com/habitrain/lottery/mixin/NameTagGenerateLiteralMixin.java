package com.habitrain.lottery.mixin;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * When CurrentNameTag is a display string (contains § or starts with '['),
 * use {@link Component#literal} instead of {@link Component#translatable}.
 * Resting players are not treated as spectators (match 3D nametag / adventure).
 */
@Mixin(value = NameTagInventoryComponent.class, remap = false)
public class NameTagGenerateLiteralMixin {

    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void habi$literalDisplay(CallbackInfoReturnable<MutableComponent> cir) {
        NameTagInventoryComponent self = (NameTagInventoryComponent) (Object) this;
        String current = self.CurrentNameTag;
        Player player = self.getPlayer();
        boolean resting = habi$isResting(player);
        boolean trueSpectator = habi$isTrueSpectator(player, resting);

        if (current == null || current.isEmpty() || current.isBlank()) {
            // Empty title: true SPECTATOR still falls through to upstream generate().
            // Resting must not — upstream uses isSpectator() which rest mixin spoofs.
            if (trueSpectator) {
                return;
            }
            cir.setReturnValue(null);
            return;
        }
        if (!habi$needsLiteral(current) && !resting) {
            return;
        }

        List<MutableComponent> parts = new ArrayList<>();
        if (trueSpectator) {
            parts.add(Component.translatable("starrailexpress.tag.spectator"));
        }
        if (habi$needsLiteral(current)) {
            parts.add(Component.literal(current.replace('&', '§')));
        } else {
            parts.add(Component.translatable(current));
        }

        if (parts.isEmpty()) {
            cir.setReturnValue(null);
            return;
        }
        MutableComponent joined = ComponentUtils.formatList(parts, Component.literal(" "), c -> c);
        cir.setReturnValue(joined.copy().append(" "));
    }

    @Unique
    private static boolean habi$isResting(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return false;
        }
        try {
            return EliminatedRestAreaService.isResting(sp);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Unique
    private static boolean habi$isTrueSpectator(Player player, boolean resting) {
        if (player == null || resting) {
            return false;
        }
        if (player instanceof ServerPlayer sp) {
            try {
                return sp.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
            } catch (Throwable t) {
                return player.isSpectator();
            }
        }
        return player.isSpectator();
    }

    /**
     * Prefer literal for display strings; keep true translation keys as translatable.
     * Keys look like {@code namespace:path} or pure snake_case tokens without color/brackets.
     */
    @Unique
    private static boolean habi$needsLiteral(String s) {
        if (s.indexOf('§') >= 0 || s.indexOf('&') >= 0 || s.indexOf('[') >= 0 || s.indexOf(' ') >= 0) {
            return true;
        }
        // Chinese / non-ascii display text
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) {
                return true;
            }
        }
        // path-like keys stay translatable; anything else is treated as display text
        if (s.indexOf(':') >= 0) {
            return ResourceLocation.tryParse(s) == null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            if (!ok) {
                return true;
            }
        }
        return false;
    }
}

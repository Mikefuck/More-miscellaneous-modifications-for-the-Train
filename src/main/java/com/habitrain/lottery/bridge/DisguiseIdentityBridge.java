package com.habitrain.lottery.bridge;

import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerPsychoComponent;
import io.wifi.starrailexpress.game.roles.SpecialGameModeRoles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.agmas.noellesroles.game.roles.killer.embalmer.EmbalmerPlayerComponent;
import org.agmas.noellesroles.game.roles.killer.morphling.MorphlingPlayerComponent;
import org.agmas.noellesroles.game.roles.killer.skincrawler.SkincrawlerPlayerComponent;
import org.agmas.noellesroles.game.roles.neutral.amon.AmonPlayerComponent;
import org.agmas.noellesroles.init.ModEffects;
import org.agmas.noellesroles.utils.RoleUtils;
import pro.fazeclan.river.stupid_express.modifier.split_personality.cca.SkinSplitPersonalityComponent;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Resolves the effective visual identity of a player across disguise, morph, skin-swap,
 * and anonymous fixed-skin mechanics so personal lottery cosmetics never leak real
 * identities during an active match ({@code I-13}).
 *
 * <p>The outer class is strictly dedicated-server safe; all client-only rendering hooks
 * live inside {@link Client}.
 */
public final class DisguiseIdentityBridge {

    public enum Kind {
        /** Render or apply the subject's own cosmetics. */
        SELF,
        /** Subject visually appears as another real player ({@link Resolution#targetUuid()}). */
        DISGUISED_AS,
        /** Subject wears an anonymous / fixed role skin; hide all personal cosmetics. */
        HIDDEN_ANONYMOUS
    }

    public record Resolution(Kind kind, UUID targetUuid) {
        private static final Resolution SELF_INSTANCE = new Resolution(Kind.SELF, null);
        private static final Resolution HIDDEN_INSTANCE = new Resolution(Kind.HIDDEN_ANONYMOUS, null);

        public static Resolution self() {
            return SELF_INSTANCE;
        }

        public static Resolution hidden() {
            return HIDDEN_INSTANCE;
        }

        public static Resolution disguisedAs(UUID targetUuid) {
            return targetUuid == null ? SELF_INSTANCE : new Resolution(Kind.DISGUISED_AS, targetUuid);
        }
    }

    private static final Field AMON_DISGUISE_FIELD = findAmonDisguiseField();

    private DisguiseIdentityBridge() {
    }

    private static Field findAmonDisguiseField() {
        try {
            Field f = AmonPlayerComponent.class.getDeclaredField("disguiseTarget");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Resolves a player's displayed identity on the logical server (e.g. thrown grenade
     * skin inheritance and explosion impact effects).
     */
    public static Resolution resolveServer(Player subject) {
        if (subject == null || subject.level() == null) {
            return Resolution.self();
        }
        try {
            if (SRE.isLobby) {
                return Resolution.self();
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.getNullable(subject.level());
            if (game == null || game.gameStatus == SREGameWorldComponent.GameStatus.INACTIVE) {
                return Resolution.self();
            }
            if (subject.hasEffect(ModEffects.DISGUISE)) {
                return Resolution.hidden();
            }
            SREPlayerPsychoComponent psycho = SREPlayerPsychoComponent.KEY.getNullable(subject);
            if (psycho != null && psycho.psychoTicks > 0) {
                return Resolution.hidden();
            }
            if (RoleUtils.isPlayerTheJob(subject, TMMRoles.LOOSE_END)
                    || RoleUtils.isPlayerTheJob(subject, SpecialGameModeRoles.SUPER_LOOSE_END)) {
                return Resolution.hidden();
            }
            SRERole role = game.getRole(subject);
            if (role != null
                    && (role.getNormalSkin(subject, false) != null || role.getNormalSkin(subject, true) != null)) {
                return Resolution.hidden();
            }

            UUID target = resolveServerDisguiseTarget(subject);
            if (target != null && !target.equals(subject.getUUID())) {
                return Resolution.disguisedAs(target);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Upstream component unavailable: keep gameplay running without crashing.
        }
        return Resolution.self();
    }

    private static UUID resolveServerDisguiseTarget(Player subject) {
        SkinSplitPersonalityComponent split = SkinSplitPersonalityComponent.KEY.getNullable(subject);
        if (split != null && split.getSkinToAppearAs() != null) {
            return split.getSkinToAppearAs();
        }
        MorphlingPlayerComponent morph = MorphlingPlayerComponent.KEY.getNullable(subject);
        if (morph != null && morph.getMorphTicks() > 0 && morph.disguise != null) {
            return morph.disguise;
        }
        for (Player candidate : subject.level().players()) {
            EmbalmerPlayerComponent embalmer = EmbalmerPlayerComponent.KEY.getNullable(candidate);
            if (embalmer != null
                    && embalmer.masqueradeActive
                    && embalmer.masqueradeTicksLeft > 0
                    && embalmer.skinSwaps != null) {
                UUID swapped = embalmer.skinSwaps.get(subject.getUUID());
                if (swapped != null) {
                    return swapped;
                }
            }
        }
        SkincrawlerPlayerComponent skincrawler = SkincrawlerPlayerComponent.KEY.getNullable(subject);
        if (skincrawler != null && skincrawler.stolenSkin != null) {
            return skincrawler.stolenSkin;
        }
        if (AMON_DISGUISE_FIELD != null) {
            AmonPlayerComponent amon = AmonPlayerComponent.KEY.getNullable(subject);
            if (amon != null) {
                try {
                    Object raw = AMON_DISGUISE_FIELD.get(amon);
                    if (raw instanceof UUID hostUuid) {
                        return hostUuid;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    @Environment(EnvType.CLIENT)
    public static final class Client {
        private Client() {
        }

        public static Resolution resolve(LivingEntity entity) {
            if (!(entity instanceof net.minecraft.client.player.AbstractClientPlayer player)) {
                return Resolution.self();
            }
            try {
                if (SRE.isLobby || io.wifi.starrailexpress.client.SREClient.isInLobby) {
                    return Resolution.self();
                }
                if (player.hasEffect(ModEffects.DISGUISE) || isConcealedByFixedSkin(player)) {
                    return Resolution.hidden();
                }
                UUID ownerUuid = io.wifi.starrailexpress.hat.HatEquipmentApi.resolveDisplayedOwnerUuid(player);
                if (ownerUuid != null && !ownerUuid.equals(player.getUUID())) {
                    return Resolution.disguisedAs(ownerUuid);
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
            return Resolution.self();
        }

        private static boolean isConcealedByFixedSkin(net.minecraft.client.player.AbstractClientPlayer player) {
            if (io.wifi.starrailexpress.client.SREClient.PLAYER_PSYCHO_CACHE
                    .getOrDefault(player.getUUID(), false)) {
                return true;
            }
            if (io.wifi.starrailexpress.client.SREClient.getLooseEndPenalty()) {
                return true;
            }
            if (RoleUtils.isPlayerTheJob(player, TMMRoles.LOOSE_END)
                    || RoleUtils.isPlayerTheJob(player, SpecialGameModeRoles.SUPER_LOOSE_END)) {
                return true;
            }
            if (io.wifi.starrailexpress.client.SREClient.gameComponent != null) {
                SRERole role = io.wifi.starrailexpress.client.SREClient.gameComponent.getRole(player.getUUID());
                if (role != null) {
                    boolean slim = player.getSkin().model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM;
                    if (role.getNormalSkin(player, slim) != null) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
